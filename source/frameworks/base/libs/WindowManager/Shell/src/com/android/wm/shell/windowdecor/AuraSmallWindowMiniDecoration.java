/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.android.wm.shell.windowdecor;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.InputTransferToken;
import android.window.SurfaceSyncGroup;
import android.window.TaskConstants;

/** Transparent, task-clipped Shell input host; app windows have an empty input region in Core. */
final class AuraSmallWindowMiniDecoration {
    private final SurfaceControl mSurface;
    private final SurfaceControlViewHost mHost;
    private boolean mReleased;
    private boolean mActionSent;

    AuraSmallWindowMiniDecoration(Context context, Display display, RunningTaskInfo info,
            SurfaceControl parent, Runnable restore, Runnable fullscreen) {
        final Rect canvas = info.configuration.windowConfiguration.getBounds();
        mSurface = new SurfaceControl.Builder().setName("Aura mini input task " + info.taskId)
                .setContainerLayer().setParent(parent).build();
        final MiniWindowManager manager = new MiniWindowManager(info, mSurface);
        mHost = new SurfaceControlViewHost(context, display, manager, "Aura mini input");
        final GestureDetector detector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                        if (!mReleased && !mActionSent) { mActionSent = true; restore.run(); }
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent event) {
                        if (!mReleased && !mActionSent) { mActionSent = true; fullscreen.run(); }
                        return true;
                    }
                });
        final View view = new View(context);
        view.setContentDescription("Mini window. Tap to restore; double tap for fullscreen.");
        view.setOnTouchListener((target, event) -> {
            // Windowless local coordinates are inverse-scaled. Feed physical screen coordinates
            // to Android's tap detector so its touch slop stays physical-sized at mini scale.
            final MotionEvent physical = MotionEvent.obtain(event);
            physical.setLocation(event.getRawX(), event.getRawY());
            detector.onTouchEvent(physical);
            physical.recycle();
            return true; // Consume every sequence, including drags/cancels/multiple pointers.
        });
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                canvas.width(), canvas.height(), WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.setTitle("Aura mini input task " + info.taskId);
        params.setTrustedOverlay();
        final SurfaceSyncGroup group = new SurfaceSyncGroup("AuraMiniHost");
        group.add(mHost.getRootSurfaceControl(), () -> {
            if (mReleased) return;
            mHost.setView(view, params);
            manager.touchRegion(mHost.getWindowToken().asBinder(),
                    new Region(0, 0, canvas.width(), canvas.height()));
        });
        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        transaction.setLayer(mSurface, TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS + 10)
                .setWindowCrop(mSurface, canvas.width(), canvas.height()).show(mSurface);
        group.addTransaction(transaction);
        group.markSyncReady();
    }

    void release() {
        if (mReleased) return;
        mReleased = true;
        mHost.release();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.remove(mSurface).apply();
        }
        mSurface.release();
    }

    private static final class MiniWindowManager extends WindowlessWindowManager {
        MiniWindowManager(RunningTaskInfo info, SurfaceControl parent) {
            super(info.configuration, parent, (InputTransferToken) null);
        }
        void touchRegion(IBinder token, Region region) { setTouchRegion(token, region); }
    }
}
