/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wm.shell.windowdecor;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.SurfaceSyncGroup;
import android.window.TaskConstants;
import android.window.InputTransferToken;
import android.window.WindowContainerTransaction;

import java.util.ArrayList;

import com.android.wm.shell.windowdecor.aura.AuraSmallWindowBottomBarView;

/**
 * Transparent bottom handle overlay. Its host is inverse-scaled to keep physical sizing.
 * Outer-corner resizing is observed by AuraSmallWindowPointerMonitor, outside app content.
 * It only forwards events: gesture policy and surface ownership belong to the coordinator.
 */
final class AuraSmallWindowBottomDecoration {
    static final int REGION_BOTTOM = 0;
    static final int REGION_LOWER_LEFT = 1;
    static final int REGION_LOWER_RIGHT = 2;
    private static final float HEIGHT_DP = 24f;

    interface Listener {
        void onEvent(int region, int phase, float rawX, float rawY, int pointerId,
                long eventTimeMillis, int pointerCount);
        boolean onAccessibilityAction(int actionId);
        default void onHandleFrameCommitted(AuraSmallWindowBottomDecoration bottom) {}
    }

    private final Listener mListener;
    private final SurfaceControl mSurface;
    private final ArrayList<SurfaceControlViewHost> mHosts = new ArrayList<>();
    private final ArrayList<SurfaceControl> mSlots = new ArrayList<>();
    private final SurfaceControl mBarSurface;
    private final AuraSmallWindowBottomBarView mBar;
    private final int mBarWidth;
    private final int mBarHeight;
    private final int mWidth;
    private final int mHeight;
    private boolean mReleased;

    AuraSmallWindowBottomDecoration(Context context, Display display, RunningTaskInfo info,
            SurfaceControl parent, float displayDensity, Listener listener,
            SurfaceControl.Transaction transaction, SurfaceSyncGroup syncGroup) {
        if (!Float.isFinite(displayDensity) || displayDensity <= 0f
                || !(info.auraSmallWindowScale > 0f && info.auraSmallWindowScale < 1f)) {
            throw new IllegalArgumentException("Invalid bottom decoration snapshot");
        }
        mListener = listener;
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        final Point displaySize = new Point();
        display.getRealSize(displaySize);
        // The fixed handle buffer moves with the task in the same native transaction.
        mWidth = Math.max(Math.max(1, displaySize.x),
                (int) Math.ceil(bounds.width() * info.auraSmallWindowScale));
        mHeight = Math.round(HEIGHT_DP * displayDensity);
        mSurface = new SurfaceControl.Builder().setName("Aura bottom of task " + info.taskId)
                .setContainerLayer().setParent(parent)
                .setCallsite("AuraSmallWindowBottomDecoration").build();
        mBar = new AuraSmallWindowBottomBarView(context, displayDensity);
        mBar.setStandaloneHost(true);
        mBar.setOnBottomBarEventListener((phase, x, y, pointer, time, count) ->
                listener.onEvent(REGION_BOTTOM, phase, x, y, pointer, time, count));
        mBar.setOnBottomBarActionListener(listener::onAccessibilityAction);
        mBarWidth = Math.round(104f * displayDensity);
        mBarHeight = Math.round(AuraSmallWindowBottomBarView.TOUCH_HEIGHT_DP * displayDensity);
        mBarSurface = addSlot(context, display, info, mBar, mBarWidth, mBarHeight,
                "handle", transaction, syncGroup);
        updateVisualGeometry(transaction, bounds, info.auraSmallWindowScale);
        transaction.setLayer(mSurface, TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS)
                .show(mSurface);
    }

    private SurfaceControl addSlot(Context context, Display display, RunningTaskInfo info,
            View view, int width, int height, String name, SurfaceControl.Transaction transaction,
            SurfaceSyncGroup syncGroup) {
        final SurfaceControl surface = new SurfaceControl.Builder()
                .setName("Aura bottom " + name + " of task " + info.taskId)
                .setContainerLayer().setParent(mSurface).build();
        mSlots.add(surface);
        final BottomWindowManager manager = new BottomWindowManager(
                info.configuration, surface, null /* hostInputToken */);
        final SurfaceControlViewHost host = new SurfaceControlViewHost(context, display, manager,
                "AuraSmallWindowBottomDecoration " + name);
        mHosts.add(host);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.setTitle("Aura bottom " + name + " of task " + info.taskId);
        params.setTrustedOverlay();
        // This is an in-process host. Avoid allocating a SurfacePackage copy whose native
        // reference would otherwise keep the detached slot alive until Java GC after close.
        syncGroup.add(host.getRootSurfaceControl(), () -> {
            if (mReleased) return;
            if (view == mBar) {
                view.getViewTreeObserver().registerFrameCommitCallback(() -> {
                    if (!mReleased) mListener.onHandleFrameCommitted(this);
                });
            }
            host.setView(view, params);
            // SF intersects this extent with the actual task rounded clip on every frame.
            // Do not cache a client-side rounded mask derived from a previous task scale.
            manager.setBottomTouchRegion(host.getWindowToken().asBinder(),
                    new Region(0, 0, width, height));
        });
        transaction.setLayer(surface, 1).show(surface);
        return surface;
    }

    /** The transparent handle overlays content: no full-width caption inset. */
    void updateInsets(WindowContainerTransaction transaction, Rect logicalBounds, float scale) {}

    /** Preview-only update; never rebuild/relayout the ViewHost on an animation frame. */
    void updateVisualGeometry(SurfaceControl.Transaction transaction, Rect logicalBounds,
            float scale) {
        updateVisualGeometry(transaction, logicalBounds, scale, scale);
    }

    /** Independent axes also keep controls physical-sized during a fullscreen aspect change. */
    void updateVisualGeometry(SurfaceControl.Transaction transaction, Rect logicalBounds,
            float scaleX, float scaleY) {
        if (mReleased) return;
        if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || scaleX <= 0f || scaleY <= 0f) {
            throw new IllegalArgumentException("Invalid bottom preview scale");
        }
        final float width = logicalBounds.width() * scaleX;
        if (width > mWidth + 1f) {
            throw new IllegalArgumentException("Preview exceeds fixed bottom host width");
        }
        final float y = logicalBounds.height() - mHeight / scaleY;
        // Only the handle owns a native input region; all blank footer pixels pass through.
        transaction.setMatrix(mSurface, 1f / scaleX, 0, 0, 1f / scaleY)
                .setPosition(mSurface, 0f, y)
                .setWindowCrop(mSurface, Math.max(1, (int) Math.ceil(width)), mHeight)
                .setPosition(mBarSurface, (width - mBarWidth) / 2f,
                        mHeight - mBarHeight);
    }

    /** Keep the transparent footer handle readable using the app's caption contrast. */
    void setHandleTint(int color) {
        mBar.setHandleTint(color);
    }

    void setGesturePreview(boolean preview) {
        mBar.setGesturePreview(preview);
    }

    private static final class BottomWindowManager extends WindowlessWindowManager {
        BottomWindowManager(Configuration configuration, SurfaceControl surface,
                InputTransferToken token) {
            super(configuration, surface, token);
        }

        void setBottomTouchRegion(IBinder window, Region region) {
            setTouchRegion(window, region);
        }
    }

    void setVisible(SurfaceControl.Transaction transaction, boolean visible) {
        if (visible) transaction.show(mSurface); else transaction.hide(mSurface);
    }

    void setChromeAlpha(SurfaceControl.Transaction transaction, float alpha) {
        transaction.setAlpha(mSurface, alpha);
    }

    void removeInsets(WindowContainerTransaction transaction) {}

    /** Release the single handle host and its container. */
    void release(WindowContainerTransaction transaction) {
        if (mReleased) return;
        removeInsets(transaction);
        mReleased = true;
        for (SurfaceControlViewHost host : mHosts) host.release();
        try (SurfaceControl.Transaction surfaceTransaction = new SurfaceControl.Transaction()) {
            surfaceTransaction.remove(mSurface).apply();
        }
        for (SurfaceControl slot : mSlots) slot.release();
        mSurface.release();
    }
}
