/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.windowdecor;

import static android.window.DesktopModeFlags.ENABLE_WINDOWING_SCALED_RESIZING;

import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getFineResizeCornerSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getLargeResizeCornerSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getResizeEdgeHandleSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getResizeHandleEdgeInset;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Size;
import android.view.Choreographer;
import android.view.Display;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.Surface;
import android.view.animation.PathInterpolator;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.DesktopExperienceFlags;
import android.window.DesktopModeFlags;
import android.window.SurfaceSyncGroup;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.shared.desktopmode.DesktopConfig;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer;
import com.android.wm.shell.windowdecor.caption.OccludingElement;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;
import com.android.wm.shell.windowdecor.aura.AuraSmallWindowBottomBarView;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link CaptionWindowDecorViewModel}. The caption bar contains a back button, minimize button,
 * maximize button and close button.
 */
public class CaptionWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private final Handler mHandler;
    private final @ShellMainThread ShellExecutor mMainExecutor;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;
    private final DesktopConfig mDesktopConfig;

    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;

    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final RelayoutResult<WindowDecorLinearLayout> mResult =
            new RelayoutResult<>();

    /** More-menu window for the Aura v2 caption; released together with the decor. */
    private AdditionalViewHostViewContainer mAuraMoreMenuContainer;
    /** Icon tint picked for the current caption background; reused by the more menu. */
    private ColorStateList mAuraIconTint;
    /** Experimental static footer; gesture ownership is attached in a later stage. */
    private AuraSmallWindowBottomDecoration mAuraBottom;
    private AuraSmallWindowMiniDecoration mAuraMini;
    private final Rect mAuraMiniRestoreBounds = new Rect();
    private Transitions mAuraTransitions;
    private AuraSmallWindowInteractionController mAuraInteraction;
    private AuraSmallWindowPointerMonitor mAuraPointerMonitor;
    // App reference color for control contrast and the entry veil; chrome is transparent.
    private int mAuraCaptionColor;
    private String mAuraContentThemeKey;
    private int mAuraContentThemeColor = Color.TRANSPARENT;
    private BooleanSupplier mAuraReadyAllowed = () -> false;
    private View mAuraDrawnCaption;
    private View mAuraPendingCaption;
    private boolean mAuraBottomDrawn;
    private boolean mAuraBottomSynced;
    private boolean mAuraPoseCommitted;
    private boolean mAuraReadySent;
    private boolean mAuraReadyQueued;
    private boolean mAuraClosed;
    private int mAuraPoseVersion;
    private AuraRevealVeil mAuraRevealVeil;

    void setAuraReadyAllowed(BooleanSupplier allowed) {
        mAuraReadyAllowed = allowed;
    }

    private boolean canNotifyAuraReady() {
        return !mAuraClosed && mAuraReadyAllowed.getAsBoolean() && !mAuraReadySent
                && isAuraSmallWindowTask(mTaskInfo) && mTaskInfo.isVisible
                && !mTaskInfo.auraSmallWindowMini && mResult.mRootView != null
                && mAuraDrawnCaption == mResult.mRootView && mAuraPoseCommitted
                && mAuraBottom != null && mAuraBottomDrawn && mAuraBottomSynced
                && (mAuraInteraction == null || !mAuraInteraction.isInteractionActive());
    }

    /** Notify Launcher only after both control buffers and their final surface pose exist. */
    private void notifyAuraReady() {
        if (!canNotifyAuraReady() || mAuraReadyQueued) return;
        mAuraReadyQueued = true;
        // Earlier idle callbacks can enqueue the initial task layer-order transition.
        // Re-enter idle on the next Shell turn instead of exposing a task below Home.
        mMainExecutor.execute(() -> mAuraTransitions.runOnIdle(() ->
                mMainExecutor.execute(() -> mAuraTransitions.runOnIdle(() -> {
            mAuraReadyQueued = false;
            if (!canNotifyAuraReady()) return;
            final RunningTaskInfo current = mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId);
            if (current == null || !current.isVisible || current.auraSmallWindowMini
                    || !isAuraSmallWindowTask(current) || current.numActivities == 0
                    || !current.token.equals(mTaskInfo.token)
                    || !current.configuration.windowConfiguration.getBounds().equals(
                            mTaskInfo.configuration.windowConfiguration.getBounds())
                    || Float.compare(current.auraSmallWindowScale,
                            mTaskInfo.auraSmallWindowScale) != 0) return;
            final Intent ready = new Intent("com.android.launcher3.AURA_WINDOW_CHROME_READY")
                    .setPackage("com.android.launcher3")
                    .putExtra("task_id", current.taskId).putExtra("user_id", current.userId)
                    .putExtra("bounds", new Rect(
                            current.configuration.windowConfiguration.getBounds()))
                    .putExtra("scale", current.auraSmallWindowScale);
            mContext.sendBroadcastAsUser(ready, UserHandle.of(current.userId));
            if (mAuraRevealVeil != null) mAuraRevealVeil.reveal();
            mAuraReadySent = true;
            Log.i("AuraWindowChrome", "drawn task=" + current.taskId);
        }))));
    }

    private void observeAuraCaptionDraw() {
        final View root = mResult.mRootView;
        if (!isAuraSmallWindowTask(mTaskInfo) || root == null
                || root == mAuraDrawnCaption || root == mAuraPendingCaption) return;
        mAuraPendingCaption = root;
        mAuraReadySent = false;
        root.getViewTreeObserver().registerFrameCommitCallback(() -> mMainExecutor.execute(() -> {
            if (mAuraClosed || mResult.mRootView != root) return;
            mAuraDrawnCaption = root;
            mAuraPendingCaption = null;
            notifyAuraReady();
        }));
        root.invalidate();
    }

    void setAuraInteractionController(AuraSmallWindowInteractionController controller) {
        mAuraInteraction = controller;
    }

    AuraSmallWindowInteractionController getAuraInteractionController() {
        return mAuraInteraction;
    }

    static float auraInsetsScale(RunningTaskInfo info, float density) {
        final int width = info.configuration.windowConfiguration.getBounds().width();
        return width <= 0 ? info.auraSmallWindowScale
                : Math.min(info.auraSmallWindowScale, 220f * density / width);
    }

    void applyAuraChromePreview(SurfaceControl.Transaction transaction, Rect logicalBounds,
            float previewScale) {
        final DisplayLayout auraLayout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        final float density = auraLayout == null ? 1f : auraLayout.density();
        final float chromeAlpha = Math.max(0f, Math.min(1f,
                (logicalBounds.width() * previewScale / density - 96f) / 124f));
        final SurfaceControl caption = getCaptionSurfaceForAura();
        if (caption != null && caption.isValid() && mResult.mRootView != null) {
            // Native compensation keeps centered controls physical-sized in the task's TX.
            final float factor = mTaskInfo.auraSmallWindowScale / previewScale;
            final int width = mResult.mCaptionWidth;
            final float visibleLocalWidth = width / factor;
            final int left = Math.max(0, (int) Math.floor((width - visibleLocalWidth) / 2f));
            final int right = Math.min(width, (int) Math.ceil((width + visibleLocalWidth) / 2f));
            transaction.setAlpha(caption, chromeAlpha).setMatrix(caption, factor, 0f, 0f, factor)
                    .setPosition(caption, mResult.mCaptionX + (width - width * factor) / 2f, 0)
                    .setWindowCrop(caption, new Rect(left, 0, right, mResult.mCaptionHeight));
        }
        if (mAuraBottom != null) {
            mAuraBottom.updateVisualGeometry(transaction, logicalBounds, previewScale);
            mAuraBottom.setChromeAlpha(transaction, chromeAlpha);
        }
    }

    void setAuraBottomPreview(boolean previewing) {
        if (mAuraBottom != null) mAuraBottom.setGesturePreview(previewing);
    }

    CaptionWindowDecoration(
            Context context,
            @NonNull Context userContext,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            @NonNull Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            DesktopConfig desktopConfig) {
        super(context, handler, transitions, userContext, displayController, taskOrganizer,
                taskInfo, taskSurface, windowDecorViewHostSupplier, bgExecutor);
        mHandler = handler;
        mAuraTransitions = transitions;
        mMainExecutor = mainExecutor;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
        mDesktopConfig = desktopConfig;
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
    }

    @NonNull
    Rect calculateValidDragArea() {
        final Context displayContext = mDisplayController.getDisplayContext(mTaskInfo.displayId);
        if (displayContext == null) return new Rect();
        final int leftButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.caption_left_buttons_width);

        // On a smaller screen, don't require as much empty space on screen, as offscreen
        // drags will be restricted too much.
        final int requiredEmptySpaceId = displayContext
                .getResources().getConfiguration().smallestScreenWidthDp >= 600
                ? R.dimen.freeform_required_visible_empty_space_in_header :
                R.dimen.small_screen_required_visible_empty_space_in_header;
        final int requiredEmptySpace = loadDimensionPixelSize(mContext.getResources(),
                requiredEmptySpaceId);

        final int rightButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.caption_right_buttons_width);
        final int taskWidth = mTaskInfo.configuration.windowConfiguration.getBounds().width();
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        final int displayWidth = layout.width();
        final Rect stableBounds = new Rect();
        layout.getStableBounds(stableBounds);
        if (isAuraSmallWindowTask(mTaskInfo)) {
            // Keep the whole phone window reachable until explicit side docking is implemented.
            final float scale = getAuraSmallWindowScale(mTaskInfo);
            final int visibleWidth = Math.round(taskWidth * scale);
            final int taskHeight = Math.round(
                    mTaskInfo.configuration.windowConfiguration.getBounds().height() * scale);
            return new Rect(stableBounds.left, stableBounds.top,
                    Math.max(stableBounds.left, stableBounds.right - visibleWidth),
                    Math.max(stableBounds.top, stableBounds.bottom - taskHeight));
        }
        return new Rect(
                determineMinX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth),
                stableBounds.top,
                determineMaxX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace, taskWidth,
                        displayWidth),
                determineMaxY(requiredEmptySpace, stableBounds));
    }


    /**
     * Determine the lowest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMinX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth) {
        // Do not let apps with < 48dp empty header space go off the left edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return 0;
        }
        return -taskWidth + requiredEmptySpace + rightButtonsWidth;
    }

    /**
     * Determine the highest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth, int displayWidth) {
        // Do not let apps with < 48dp empty header space go off the right edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return displayWidth - taskWidth;
        }
        return displayWidth - requiredEmptySpace - leftButtonsWidth;
    }

    /**
     * Determine the highest y coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxY(int requiredEmptySpace, Rect stableBounds) {
        return stableBounds.bottom - requiredEmptySpace;
    }

    @Override
    void relayout(RunningTaskInfo taskInfo, boolean hasGlobalFocus,
            @NonNull Region displayExclusionRegion) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        // The crop and position of the task should only be set when a task is fluid resizing. In
        // all other cases, it is expected that the transition handler positions and crops the task
        // in order to allow the handler time to animate before the task before the final
        // position and crop are set.
        final boolean shouldSetTaskVisibilityPositionAndCrop =
                mTaskDragResizer.isResizingOrAnimating();
        // Use |applyStartTransactionOnDraw| so that the transaction (that applies task crop) is
        // synced with the buffer transaction (that draws the View). Both will be shown on screen
        // at the same, whereas applying them independently causes flickering. See b/270202228.
        // Aura metadata updates only change child chrome; its task pose belongs to the
        // transition/gesture. A retained hidden caption may not produce another buffer,
        // so waiting for its next draw can delay the show/alpha reset indefinitely.
        final boolean applyOnDraw = !isAuraSmallWindowTask(taskInfo)
                || shouldSetTaskVisibilityPositionAndCrop;
        relayout(taskInfo, t, t, applyOnDraw,
                shouldSetTaskVisibilityPositionAndCrop, hasGlobalFocus, displayExclusionRegion,
                /* inSyncWithTransition= */ false);
        if (!applyOnDraw) {
            t.apply();
            t.close();
        }
    }

    @VisibleForTesting
    static void updateRelayoutParams(
            RelayoutParams relayoutParams,
            @NonNull Context context,
            ActivityManager.RunningTaskInfo taskInfo,
            boolean applyStartTransactionOnDraw,
            boolean shouldSetTaskVisibilityPositionAndCrop,
            boolean isStatusBarVisible,
            boolean isKeyguardVisibleAndOccluded,
            InsetsState displayInsetsState,
            boolean hasGlobalFocus,
            @NonNull Region globalExclusionRegion,
            boolean shouldSetBackground,
            boolean inSyncWithTransition) {
        relayoutParams.reset();
        relayoutParams.mRunningTaskInfo = taskInfo;
        final boolean isAuraSmallWindow = isAuraSmallWindowTask(taskInfo);
        relayoutParams.mLayoutResId = isAuraSmallWindow
                ? R.layout.aura_small_window_caption : R.layout.caption_window_decor;
        relayoutParams.mCaptionHeightCalculator = getCaptionHeightCalculator(taskInfo);
        if (DesktopExperienceFlags.ENABLE_DYNAMIC_RADIUS_COMPUTATION_BUGFIX.isTrue()) {
            relayoutParams.mShadowRadiusId = hasGlobalFocus
                    ? R.dimen.freeform_decor_shadow_focused_thickness
                    : R.dimen.freeform_decor_shadow_unfocused_thickness;
        } else {
            relayoutParams.mShadowRadius = hasGlobalFocus
                    ? context.getResources().getDimensionPixelSize(
                    R.dimen.freeform_decor_shadow_focused_thickness)
                    : context.getResources().getDimensionPixelSize(
                            R.dimen.freeform_decor_shadow_unfocused_thickness);
        }
        relayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
        relayoutParams.mSetTaskVisibilityPositionAndCrop = shouldSetTaskVisibilityPositionAndCrop;
        relayoutParams.mIsCaptionVisible = taskInfo.isFreeform()
                || (isStatusBarVisible && !isKeyguardVisibleAndOccluded);
        relayoutParams.mDisplayExclusionRegion.set(globalExclusionRegion);
        relayoutParams.mInSyncWithTransition = inSyncWithTransition;

        if (!isAuraSmallWindow && TaskInfoKt.isTransparentCaptionBarAppearance(taskInfo)) {
            // If the app is requesting to customize the caption bar, allow input to fall
            // through to the windows below so that the app can respond to input events on
            // their custom content.
            relayoutParams.mInputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_SPY;
        }
        if (isAuraSmallWindow) {
            relayoutParams.mCaptionInsetsHeight = (int) Math.ceil(28
                    * context.getResources().getDisplayMetrics().density
                    / AuraSmallWindowViewport.decorScale(taskInfo));
            // Only the centered dots own input. Transparent flanks go directly to the app;
            // task dragging starts on the same 44dp physical dots target as the more menu.
            // OccludingElement only supports START/END alignment, so the centered zone is
            // expressed as two equal empty flanks: START covers up to the zone, END from
            // its right edge; with a symmetric total the zone lands centered.
            final float compensatedDensity = context.getResources().getDisplayMetrics().density
                    / AuraSmallWindowViewport.decorScale(taskInfo);
            final int captionLogicalWidth = Math.max(0,
                    taskInfo.configuration.windowConfiguration.getBounds().width());
            final int systemWidth = Math.min(captionLogicalWidth,
                    Math.round(44 * compensatedDensity));
            relayoutParams.mAuraCaptionSystemRect = new Rect(
                    (captionLogicalWidth - systemWidth) / 2, 0,
                    (captionLogicalWidth + systemWidth) / 2,
                    Math.round(28 * compensatedDensity));
            final int auraCenterSectionWidth = Math.min(Math.round(92 * compensatedDensity),
                    captionLogicalWidth);
            final int auraFlankWidth = (captionLogicalWidth - auraCenterSectionWidth) / 2;
            relayoutParams.mOccludingElementsCalculator = () -> List.of(
                    new OccludingElement(auraFlankWidth, OccludingElement.Alignment.START),
                    new OccludingElement(auraFlankWidth, OccludingElement.Alignment.END)
            );
        } else {
            relayoutParams.mOccludingElementsCalculator = () -> List.of(
                    // First, the left-aligned section (back button).
                    new OccludingElement(context.getResources()
                            .getDimensionPixelSize(R.dimen.caption_left_buttons_width),
                            OccludingElement.Alignment.START),
                    // Then, the right-aligned section (minimize, maximize and close buttons).
                    new OccludingElement(context.getResources()
                            .getDimensionPixelSize(R.dimen.caption_right_buttons_width),
                            OccludingElement.Alignment.END)
            );
        }

        relayoutParams.mCaptionTopPadding = getTopPadding(relayoutParams,
                taskInfo.getConfiguration().windowConfiguration.getBounds(), displayInsetsState);
        // Set opaque background for all freeform tasks to prevent freeform tasks below
        // from being visible if freeform task window above is translucent.
        // Otherwise if fluid resize is enabled, add a background to freeform tasks.
        relayoutParams.mShouldSetBackground = shouldSetBackground;
    }

    @SuppressLint("MissingPermission")
    void relayout(RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw, boolean shouldSetTaskVisibilityPositionAndCrop,
            boolean hasGlobalFocus,
            @NonNull Region globalExclusionRegion, boolean inSyncWithTransition) {
        final boolean isFreeform =
                taskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FREEFORM;
        // Logical edge resizing would reflow the app again. Scale resizing is a separate
        // follow-up; this experiment validates fixed-scale input, movement and fullscreen.
        // The scale now follows the per-task criteria, so freeform tasks that the framework
        // does not scale (other displays, embedded children) keep the original resize mode.
        final boolean isDragResizeable = getAuraSmallWindowScale(taskInfo) == 1f
                && (ENABLE_WINDOWING_SCALED_RESIZING.isTrue()
                ? isFreeform : isFreeform && taskInfo.isResizeable);

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final float oldAuraScale = mTaskInfo.auraSmallWindowScale;
        final boolean restoreMini = mTaskInfo.auraSmallWindowMini
                && !taskInfo.auraSmallWindowMini && isAuraSmallWindowTask(taskInfo)
                && taskInfo.isVisible;
        if (mAuraRevealVeil != null && (taskInfo.auraSmallWindowMini
                || !isAuraSmallWindowTask(taskInfo) || !taskInfo.isVisible)) {
            mAuraRevealVeil.close(); mAuraRevealVeil = null;
        }
        if (restoreMini) {
            if (mAuraRevealVeil != null) mAuraRevealVeil.close();
            mAuraReadySent = false;
            mAuraRevealVeil = new AuraRevealVeil(taskInfo, startT);
        }
        if (!mTaskInfo.auraSmallWindowMini && taskInfo.auraSmallWindowMini) {
            mAuraMiniRestoreBounds.set(mTaskInfo.configuration.windowConfiguration.getBounds());
        }
        final int oldCanvasWidth = mTaskInfo.configuration.windowConfiguration.getBounds().width();
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        updateRelayoutParams(mRelayoutParams, mContext, taskInfo, applyStartTransactionOnDraw,
                shouldSetTaskVisibilityPositionAndCrop, mIsStatusBarVisible,
                mIsKeyguardVisibleAndOccluded,
                mDisplayController.getInsetsState(taskInfo.displayId), hasGlobalFocus,
                globalExclusionRegion, mDesktopConfig.shouldSetBackground(taskInfo),
                inSyncWithTransition);

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, getLeash(), mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            dismissAuraMoreMenu();
            releaseAuraBottom(wct);
            mBgExecutor.execute(() -> mTaskOrganizer.applyTransaction(wct));
            return;
        }
        if (oldRootView != mResult.mRootView
                || Float.compare(oldAuraScale, taskInfo.auraSmallWindowScale) != 0
                || oldCanvasWidth != taskInfo.configuration.windowConfiguration.getBounds().width()) {
            dismissAuraMoreMenu();
            setupRootView();
        }

        bindData(mResult.mRootView, taskInfo);
        observeAuraCaptionDraw();
        notifyAuraReady();
        mBgExecutor.execute(() -> mTaskOrganizer.applyTransaction(wct));

        if (!isDragResizeable) {
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            final ShellExecutor bgExecutor =
                    DesktopModeFlags.ENABLE_DRAG_RESIZE_SET_UP_IN_BG_THREAD.isTrue()
                            ? mBgExecutor : mMainExecutor;
            mDragResizeListener = new DragResizeInputListener(
                    mContext,
                    WindowManagerGlobal.getWindowSession(),
                    mMainExecutor,
                    bgExecutor,
                    mTaskInfo,
                    mHandler,
                    mChoreographer,
                    mDisplay.getDisplayId(),
                    mDecorationContainerSurface,
                    mDragPositioningCallback,
                    mSurfaceControlBuilderSupplier,
                    mSurfaceControlTransactionSupplier,
                    mDisplayController);
        }
        final DragResizeInputListener newListener = mDragResizeListener;
        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        final Resources res = mResult.mRootView.getResources();
        final DragResizeWindowGeometry newGeometry = new DragResizeWindowGeometry(
                0 /* taskCornerRadius */,
                new Size(mResult.mWidth, mResult.mHeight),
                getResizeEdgeHandleSize(res),
                getResizeHandleEdgeInset(res), getFineResizeCornerSize(res),
                getLargeResizeCornerSize(res), DragResizeWindowGeometry.DisabledEdge.NONE);
        newListener.addInitializedCallback(() -> {
            mDragResizeListener.setGeometry(newGeometry, touchSlop);
        });
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        final View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        final View close = caption.findViewById(R.id.close_window);
        close.setOnClickListener(mOnCaptionButtonClickListener);
        final View back = caption.findViewById(R.id.back_button);
        back.setOnClickListener(mOnCaptionButtonClickListener);
        final View minimize = caption.findViewById(R.id.minimize_window);
        if (minimize != null) {
            minimize.setOnClickListener(mOnCaptionButtonClickListener);
        }
        final View maximize = caption.findViewById(R.id.maximize_window);
        maximize.setOnClickListener(mOnCaptionButtonClickListener);
        if (isAuraSmallWindowTask(mTaskInfo)) {
            // Centered-dots revision: the only live control is the 44dp physical more
            // hit target, horizontally centered; a 28dp glyph with 8dp horizontal padding. No
            // app icon or title on the decoration bar (the app keeps its own toolbar);
            // everything beside the button stays caption drag space. Compensation by
            // 1/scale follows the task's actual scale, so the target stays 44dp physical.
            final float density = caption.getResources().getDisplayMetrics().density
                    / getAuraSmallWindowScale(mTaskInfo);
            final View more = caption.findViewById(R.id.aura_more_button);
            if (more != null) {
                final LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) more.getLayoutParams();
                params.width = Math.round(44 * density);
                params.height = LinearLayout.LayoutParams.MATCH_PARENT;
                params.setMargins(0, 0, 0, 0);
                more.setLayoutParams(params);
                final int padding = Math.round(8 * density);
                more.setPadding(padding, 0, padding, 0);
                more.setOnTouchListener(mOnCaptionTouchListener);
                more.setOnClickListener(v -> showAuraMoreMenu(v));
            }
            final View icon = caption.findViewById(R.id.aura_app_icon);
            if (icon != null) {
                // Legacy id from the icon/title revision; GONE when the layout omits it.
                icon.setVisibility(View.GONE);
            }
            final TextView title = caption.findViewById(R.id.aura_app_title);
            if (title != null) {
                title.setVisibility(View.GONE);
            }
        }
    }

    @Override
    void onBeforeViewHierarchyUpdate(RelayoutParams params, SurfaceControl.Transaction startT,
            WindowContainerTransaction wct, WindowDecorLinearLayout oldRootView,
            RelayoutResult<WindowDecorLinearLayout> result) {
        final SurfaceControl caption = getCaptionSurfaceForAura();
        if (caption != null && caption.isValid()) {
            startT.setMatrix(caption, 1f, 0f, 0f, 1f);
            if (isAuraSmallWindowTask(mTaskInfo)) {
                // The mini resize preview fades this retained surface to zero. Relayout
                // must restore alpha as well as visibility; show() alone keeps it invisible.
                startT.setAlpha(caption, 1f);
            }
        }
        updateAuraBottom(wct, startT, oldRootView != result.mRootView);
        final int poseVersion = ++mAuraPoseVersion;
        mAuraPoseCommitted = false;
        if (isAuraSmallWindowTask(mTaskInfo)) {
            if (mTaskInfo.auraSmallWindowMini) mAuraReadySent = false;
            startT.addTransactionCommittedListener(mMainExecutor, () -> {
                if (mAuraClosed || poseVersion != mAuraPoseVersion) return;
                mAuraPoseCommitted = true;
                notifyAuraReady();
            });
        }
        if (mTaskInfo.auraSmallWindowMini && mTaskInfo.isVisible && mDisplay != null) {
            if (mAuraMini == null) {
                mAuraMini = new AuraSmallWindowMiniDecoration(mContext, mDisplay, mTaskInfo,
                        getLeash(), this::restoreAuraMini, () -> {
                            if (mAuraInteraction != null) mAuraInteraction.fullscreenMini();
                        });
            }
            if (caption != null && caption.isValid()) startT.hide(caption);
            startT.setWindowCrop(getLeash(), AuraSmallWindowViewport.crop(mTaskInfo));
        } else if (mAuraMini != null) {
            mAuraMini.release(); mAuraMini = null;
        }
    }

    /** Static layout/insets validation only; keep disabled until the dedicated test is run. */
    private void updateAuraBottom(WindowContainerTransaction wct,
            SurfaceControl.Transaction transaction, boolean rootChanged) {
        if (!isAuraSmallWindowTask(mTaskInfo) || !mTaskInfo.isVisible
                || !SystemProperties.getBoolean("persist.wm.debug.aura_v2_bottom", true)) {
            releaseAuraBottom(wct);
            return;
        }
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        if (layout == null || mDisplay == null || mDecorWindowContext == null) {
            releaseAuraBottom(wct);
            return;
        }
        if (mTaskInfo.auraSmallWindowMini && mAuraBottom != null) {
            mAuraBottom.updateInsets(wct,
                    mTaskInfo.configuration.windowConfiguration.getBounds(),
                    AuraSmallWindowViewport.decorScale(mTaskInfo));
            mAuraBottom.setVisible(transaction, false);
            if (mAuraPointerMonitor != null) {
                mAuraPointerMonitor.close(); mAuraPointerMonitor = null;
            }
            if (mAuraInteraction != null) mAuraInteraction.onTaskVanished();
            return;
        }
        if (rootChanged) releaseAuraBottom(wct);
        if (!mTaskInfo.auraSmallWindowMini && mAuraInteraction != null
                && mAuraPointerMonitor == null) {
            mAuraPointerMonitor = new AuraSmallWindowPointerMonitor(
                    mContext.getSystemService(android.hardware.input.InputManager.class),
                    mTaskInfo.displayId, mHandler, mAuraInteraction,
                    () -> mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId), layout.density());
        }
        if (mAuraBottom == null) {
            if (!mTaskInfo.auraSmallWindowMini && mAuraInteraction != null
                    && mAuraPointerMonitor == null) {
                mAuraPointerMonitor = new AuraSmallWindowPointerMonitor(
                        mContext.getSystemService(android.hardware.input.InputManager.class),
                        mTaskInfo.displayId, mHandler, mAuraInteraction,
                        () -> mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId), layout.density());
            }
            final SurfaceControl.Transaction createT = new SurfaceControl.Transaction();
            final SurfaceSyncGroup group = new SurfaceSyncGroup("AuraBottomLayout");
            mAuraBottom = new AuraSmallWindowBottomDecoration(mDecorWindowContext, mDisplay,
                    mTaskInfo, getLeash(), layout.density(),
                    new AuraSmallWindowBottomDecoration.Listener() {
                        @Override public void onHandleFrameCommitted(
                                AuraSmallWindowBottomDecoration bottom) {
                            mMainExecutor.execute(() -> {
                                if (mAuraClosed || mAuraBottom != bottom) return;
                                mAuraBottomDrawn = true;
                                notifyAuraReady();
                            });
                        }
                        @Override
                        public void onEvent(int region, int phase, float rawX, float rawY,
                                int pointerId, long eventTimeMillis, int pointerCount) {
                            if (mAuraInteraction == null || mAuraPointerMonitor == null) return;
                            if (phase == AuraSmallWindowBottomBarView.PHASE_DOWN
                                    && !mAuraPointerMonitor.canStart(pointerId)) return;
                            mAuraPointerMonitor.onHostEvent(region, phase, pointerId,
                                    eventTimeMillis);
                            mAuraInteraction.onEvent(region, phase, rawX, rawY, pointerId,
                                    eventTimeMillis, pointerCount);
                        }

                        @Override
                        public boolean onAccessibilityAction(int actionId) {
                            if (mAuraInteraction != null) {
                                return mAuraInteraction.onAccessibilityAction(actionId);
                            }
                            if (mAuraBottom == null || mResult.mRootView == null
                                    || !isAuraSmallWindowTask(mTaskInfo)
                                    || !mTaskInfo.isVisible) return false;
                            final int viewId;
                            if (actionId == AuraSmallWindowBottomBarView.ACTION_ID_CLOSE) {
                                viewId = R.id.close_window;
                            } else if (actionId
                                    == AuraSmallWindowBottomBarView.ACTION_ID_FULLSCREEN) {
                                viewId = R.id.maximize_window;
                            } else return false;
                            final View action = mResult.mRootView.findViewById(viewId);
                            return action != null && action.performClick();
                        }
                    }, createT, group);
            final AuraSmallWindowBottomDecoration bottom = mAuraBottom;
            group.addSyncCompleteCallback(mMainExecutor, () -> {
                if (mAuraClosed || mAuraBottom != bottom) return;
                mAuraBottomSynced = true;
                notifyAuraReady();
            });
            group.addTransaction(createT);
            group.markSyncReady();
        }
        final Rect bounds = mTaskInfo.configuration.windowConfiguration.getBounds();
        mAuraBottom.updateInsets(wct, bounds, AuraSmallWindowViewport.decorScale(mTaskInfo));
        mAuraBottom.setChromeAlpha(transaction, 1f);
        mAuraBottom.setVisible(transaction, !mTaskInfo.auraSmallWindowMini);
        mAuraBottom.updateVisualGeometry(transaction, bounds, mTaskInfo.auraSmallWindowScale);
    }

    private void releaseAuraBottom(WindowContainerTransaction wct) {
        mAuraBottomDrawn = false;
        mAuraBottomSynced = false;
        mAuraReadySent = false;
        if (mAuraPointerMonitor != null) {
            mAuraPointerMonitor.close();
            mAuraPointerMonitor = null;
        }
        if (mAuraInteraction != null) mAuraInteraction.onTaskVanished();
        if (mAuraBottom == null) return;
        mAuraBottom.release(wct);
        mAuraBottom = null;
    }

    private void bindData(View rootView, RunningTaskInfo taskInfo) {
        // Set up the tint first so that the drawable can be stylized when loaded.
        setupCaptionColor(taskInfo);

        final boolean isFullscreen =
                taskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        final boolean isAuraSmallWindow = isAuraSmallWindowTask(taskInfo);
        final View maximize = rootView.findViewById(R.id.maximize_window);
        if (isAuraSmallWindow) {
            if (maximize instanceof ImageView) {
                // Pre-v2 layouts used a visible maximize button.
                ((ImageView) maximize).setImageResource(isFullscreen
                        ? R.drawable.aura_small_window_button_restore
                        : R.drawable.aura_small_window_button_fullscreen);
            }
            // The centered-dots layout keeps back/maximize/close as GONE anchors; the
            // fullscreen state instead decides the more-menu entry label when opened.
            bindAuraHeader(rootView, taskInfo);
        } else {
            maximize.setBackgroundResource(isFullscreen ? R.drawable.decor_restore_button_dark
                    : R.drawable.decor_maximize_button_dark);
        }
    }

    /**
     * The centered-dots bar shows no app icon or title; kept only so layouts from the
     * icon/title revision still bind without crashing. Absent ids are a no-op.
     */
    private void bindAuraHeader(View rootView, RunningTaskInfo taskInfo) {
        final ImageView icon = rootView.findViewById(R.id.aura_app_icon);
        final TextView title = rootView.findViewById(R.id.aura_app_title);
        if (icon == null || title == null) {
            return;
        }
        icon.setVisibility(View.GONE);
        title.setVisibility(View.GONE);
    }

    private void setupCaptionColor(RunningTaskInfo taskInfo) {
        if (isAuraSmallWindowTask(taskInfo)) {
            setupAuraSmallWindowCaptionColor(taskInfo);
            return;
        }
        if (TaskInfoKt.isTransparentCaptionBarAppearance(taskInfo)) {
            setCaptionColor(Color.TRANSPARENT);
        } else {
            final int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
            setCaptionColor(statusBarColor);
        }
    }

    /**
     * Icon readability on the 0.74-scaled Aura caption must not depend on task focus or on
     * the luminance of a transparent black. The caption color and icon tint stay constant
     * across focus states (focus stays signaled by the window shadow), and the tint follows
     * the luminance of the app color beneath the transparent bar.
     */
    private void setupAuraSmallWindowCaptionColor(RunningTaskInfo taskInfo) {
        if (mResult.mRootView == null) {
            return;
        }
        final View caption = mResult.mRootView.findViewById(R.id.caption);
        final GradientDrawable captionDrawable = (GradientDrawable) caption.getBackground();

        final int captionColor;
        final int iconColorRes;
        if (TaskInfoKt.isTransparentCaptionBarAppearance(taskInfo)) {
            // The app asked for a caption it paints itself: keep the shell bar transparent
            // (input already falls through via INPUT_FEATURE_SPY) and follow the app's
            // declared bar appearance instead of guessing from a transparent color.
            captionColor = Color.TRANSPARENT;
            iconColorRes = TaskInfoKt.isLightCaptionBarAppearance(taskInfo)
                    ? R.color.aura_small_window_button_icon_on_light
                    : R.color.aura_small_window_button_icon_on_dark;
        } else {
            // Apps that provide no usable statusBar color get a themed fallback bar, and
            // translucent colors are composited onto it, so the luminance test below sees
            // the color the user actually sees instead of a transparent black.
            final int themedFallback = caption.getResources().getColor(
                    R.color.aura_small_window_caption_background, null /* theme */);
            final int statusBarColor = taskInfo.taskDescription != null
                    ? taskInfo.taskDescription.getStatusBarColor() : Color.TRANSPARENT;
            final int taskBackground = taskInfo.taskDescription == null ? Color.TRANSPARENT
                    : taskInfo.taskDescription.getBackgroundColor();
            // Activity reports its resolved colorBackground, including a local night-mode
            // override; prefer that live value to resolving a theme with global task config.
            final int contentColor = Color.alpha(taskBackground) == 255 ? taskBackground
                    : getAuraContentThemeColor(taskInfo);
            captionColor = Color.alpha(contentColor) == 255 ? contentColor
                    : compositeOver(statusBarColor, themedFallback);
            iconColorRes = Color.valueOf(captionColor).luminance() < 0.5
                    ? R.color.aura_small_window_button_icon_on_dark
                    : R.color.aura_small_window_button_icon_on_light;
        }
        captionDrawable.setColor(Color.TRANSPARENT);
        mAuraCaptionColor = captionColor;
        final ColorStateList iconTint = caption.getResources()
                .getColorStateList(iconColorRes, null /* theme */);
        mAuraIconTint = iconTint;
        if (mAuraBottom != null) {
            mAuraBottom.setHandleTint(iconTint.getDefaultColor());
        }
        for (int buttonId : new int[] {R.id.back_button, R.id.maximize_window,
                R.id.close_window, R.id.aura_more_button}) {
            final View view = caption.findViewById(buttonId);
            // The centered-dots action anchors are plain GONE views; only real icon views
            // take the tint (image content fits the compensated 20dp box in the padding).
            if (view instanceof ImageView) {
                ((ImageView) view).setImageTintList(iconTint);
            }
        }
    }

    /** A solid app window background joins the caption to its content instead of its status bar.
     * Read resources once per activity/theme configuration, never capture app pixels. */
    private int getAuraContentThemeColor(RunningTaskInfo taskInfo) {
        final android.content.pm.ActivityInfo activity = taskInfo.topActivityInfo;
        if (activity == null || taskInfo.topActivity == null) return Color.TRANSPARENT;
        final int theme = activity.getThemeResource();
        final String key = taskInfo.topActivity.flattenToString() + ":" + taskInfo.userId + ":"
                + theme + ":" + taskInfo.configuration.assetsSeq + ":"
                + taskInfo.configuration.uiMode + ":"
                + taskInfo.configuration.densityDpi + ":" + taskInfo.configuration.screenWidthDp;
        if (key.equals(mAuraContentThemeKey)) return mAuraContentThemeColor;
        mAuraContentThemeKey = key;
        mAuraContentThemeColor = Color.TRANSPARENT;
        try {
            final Context app = mUserContext.createPackageContext(activity.packageName, 0)
                    .createConfigurationContext(taskInfo.configuration);
            app.setTheme(theme);
            final android.content.res.TypedArray values = app.obtainStyledAttributes(
                    new int[] {android.R.attr.windowBackground});
            final android.graphics.drawable.Drawable drawable;
            try { drawable = values.getDrawable(0); }
            finally { values.recycle(); }
            if (drawable instanceof android.graphics.drawable.ColorDrawable color) {
                mAuraContentThemeColor = color.getColor();
            } else if (drawable instanceof GradientDrawable gradient && gradient.getColor() != null) {
                mAuraContentThemeColor = gradient.getColor().getDefaultColor();
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) {
            // An absent/custom/image background keeps the task-color/theme fallback.
        }
        return mAuraContentThemeColor;
    }

    /**
     * Composites {@code src} over the opaque {@code dst} (alpha blend). Equivalent to
     * ColorUtils#compositeColors, inlined because android.graphics.ColorUtils is not on
     * this module's compile classpath.
     */
    private static int compositeOver(int src, int dst) {
        final float srcAlpha = Color.alpha(src) / 255f;
        return Color.rgb(
                Math.round(Color.red(src) * srcAlpha + Color.red(dst) * (1 - srcAlpha)),
                Math.round(Color.green(src) * srcAlpha + Color.green(dst) * (1 - srcAlpha)),
                Math.round(Color.blue(src) * srcAlpha + Color.blue(dst) * (1 - srcAlpha)));
    }

    private void setCaptionColor(int captionColor) {
        if (mResult.mRootView == null) {
            return;
        }

        final View caption = mResult.mRootView.findViewById(R.id.caption);
        final GradientDrawable captionDrawable = (GradientDrawable) caption.getBackground();
        captionDrawable.setColor(captionColor);

        final int buttonTintColorRes =
                Color.valueOf(captionColor).luminance() < 0.5
                        ? R.color.decor_button_light_color
                        : R.color.decor_button_dark_color;
        final ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);

        final View back = caption.findViewById(R.id.back_button);
        back.setBackgroundTintList(buttonTintColor);

        final View minimize = caption.findViewById(R.id.minimize_window);
        minimize.setBackgroundTintList(buttonTintColor);

        final View maximize = caption.findViewById(R.id.maximize_window);
        maximize.setBackgroundTintList(buttonTintColor);

        final View close = caption.findViewById(R.id.close_window);
        close.setBackgroundTintList(buttonTintColor);
    }

    boolean isHandlingDragResize() {
        return mDragResizeListener != null && mDragResizeListener.isHandlingDragResize();
    }

    /**
     * Opens the Aura v2 "more" menu anchored below the more button. The menu keeps the
     * former primary actions available as alternative entries while gestures are being
     * rolled out: back, fullscreen/exit-fullscreen and close. Selecting an entry simply
     * performs a click on the matching GONE anchor view, which routes through the
     * unchanged {@link CaptionWindowDecorViewModel} listeners into TaskOperations.
     *
     * Hosting note (unverified draft): the menu rides an additional decoration window
     * via {@link WindowDecoration#addWindow}. The anchor position is converted from
     * screen coordinates into the decoration-container frame (task-local logical pixels
     * divided by the task scale). Codex owns final hosting/insets validation and may
     * swap the carrier without touching the entry logic below.
     */
    private void showAuraMoreMenu(View anchor) {
        if (mResult.mRootView == null) {
            return;
        }
        dismissAuraMoreMenu();
        final View caption = mResult.mRootView.findViewById(R.id.caption);
        final Context context = caption.getContext();
        final float scale = getAuraSmallWindowScale(mTaskInfo);
        final float density = caption.getResources().getDisplayMetrics().density / scale;

        final boolean isFullscreen =
                mTaskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        final int[] icons = {R.drawable.aura_small_window_button_back,
                isFullscreen ? R.drawable.aura_small_window_button_restore
                        : R.drawable.aura_small_window_button_fullscreen,
                R.drawable.aura_small_window_button_close};
        final int[] labels = {R.string.aura_small_window_menu_back_text,
                isFullscreen ? R.string.aura_small_window_restore_button_text
                        : R.string.aura_small_window_fullscreen_button_text,
                R.string.aura_small_window_close_button_text};
        final int[] anchors = {R.id.back_button, R.id.maximize_window, R.id.close_window};

        final LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(12 * density);
        final int menuColor = context.getResources().getColor(
                R.color.aura_small_window_caption_background, null /* theme */);
        background.setColor(menuColor);
        // The menu uses a themed background, while the caption may use the app's color.
        // Derive its foreground from its own background rather than the caption tint.
        final ColorStateList menuTint = ColorStateList.valueOf(context.getResources().getColor(
                Color.valueOf(menuColor).luminance() < .5f
                        ? R.color.aura_small_window_button_icon_on_dark
                        : R.color.aura_small_window_button_icon_on_light,
                null /* theme */));
        menu.setBackground(background);
        final int menuWidth = Math.max(1, Math.min(Math.round(168 * density),
                mResult.mCaptionWidth - Math.round(8 * density)));
        final int rowHeight = Math.round(48 * density);

        for (int i = 0; i < anchors.length; i++) {
            final View row = View.inflate(context, R.layout.aura_small_window_menu_item, null);
            final ImageView iconView = row.findViewById(R.id.aura_menu_item_icon);
            iconView.setImageResource(icons[i]);
            iconView.setImageTintList(menuTint);
            final LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    Math.round(20 * density), Math.round(20 * density));
            iconParams.setMarginStart(Math.round(14 * density));
            iconView.setLayoutParams(iconParams);
            final TextView text = row.findViewById(R.id.aura_menu_item_text);
            text.setText(labels[i]);
            text.setTextColor(menuTint);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f / scale);
            final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMarginStart(Math.round(10 * density));
            text.setLayoutParams(textParams);
            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight);
            row.setPadding(0, 0, Math.round(14 * density), 0);
            row.setLayoutParams(rowParams);
            row.setClickable(true);
            final int anchorId = anchors[i];
            row.setOnClickListener(v -> {
                dismissAuraMoreMenu();
                final View actionAnchor = mResult.mRootView.findViewById(anchorId);
                if (actionAnchor != null) {
                    actionAnchor.performClick();
                }
            });
            menu.addView(row);
        }

        // View geometry and the additional window share the task-local logical frame.
        // Windowless getLocationOnScreen cannot be used to reconstruct SF parent scaling.
        // The menu drops from the bar centered on the dots (the anchor now sits mid-bar);
        // it is clamped into the caption so narrow canvases keep it fully on screen.
        final int menuX = Math.max(0, Math.min(
                (mResult.mCaptionWidth - menuWidth) / 2,
                mResult.mCaptionWidth - menuWidth));
        final int menuY = mResult.mCaptionHeight + Math.round(4 * density);
        menu.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismissAuraMoreMenu();
                return true;
            }
            return false;
        });

        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        final SurfaceSyncGroup ssg = new SurfaceSyncGroup("AuraMoreMenu");
        mAuraMoreMenuContainer = addWindow(menu, "AuraMoreMenu", t, ssg, menuX, menuY,
                menuWidth, rowHeight * anchors.length);
        // Same finalization as HandleMenu: queue the transaction, then mark the group
        // ready so the synced set (including the first view draw) is applied together.
        ssg.addTransaction(t);
        ssg.markSyncReady();
    }

    /** Releases the more-menu window if present. */
    private void dismissAuraMoreMenu() {
        if (mAuraMoreMenuContainer != null) {
            mAuraMoreMenuContainer.releaseView();
            mAuraMoreMenuContainer = null;
        }
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    private static int getTopPadding(RelayoutParams params, Rect taskBounds,
            InsetsState insetsState) {
        if (!params.mRunningTaskInfo.isFreeform()) {
            Insets systemDecor = insetsState.calculateInsets(taskBounds, taskBounds,
                    WindowInsets.Type.systemBars() & ~WindowInsets.Type.captionBar(),
                    false /* ignoreVisibility */);
            return systemDecor.top;
        } else {
            return 0;
        }
    }

    /**
     * Checks whether the touch event falls inside the customizable caption region.
     */
    boolean checkTouchEventInCustomizableRegion(MotionEvent ev) {
        return mResult.mCustomizableCaptionRegion.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    boolean shouldResizeListenerHandleEvent(@NonNull MotionEvent e, @NonNull Point offset) {
        return mDragResizeListener != null && mDragResizeListener.shouldHandleEvent(e, offset);
    }

    boolean prepareAuraMinimize(WindowContainerTransaction wct) {
        final RunningTaskInfo info = mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId);
        if (info == null || !isAuraSmallWindowTask(info) || info.auraSmallWindowMini
                || !info.isVisible || info.displayId != Display.DEFAULT_DISPLAY) return false;
        final DisplayLayout layout = mDisplayController.getDisplayLayout(info.displayId);
        if (layout == null) return false;
        if (mAuraInteraction != null) mAuraInteraction.onTaskVanished();
        final Rect canvas = info.configuration.windowConfiguration.getBounds();
        final Rect safe = new Rect(); layout.getStableBounds(safe);
        final int margin = Math.round(8f * layout.density());
        final Rect crop = AuraSmallWindowViewport.miniCrop(canvas.width(), canvas.height(),
                layout.density(), info.auraSmallWindowScale);
        final float scale = Math.min(96f * layout.density() / canvas.width(), Math.min(
                (safe.width() - 2f * margin) / canvas.width(),
                (safe.height() - 2f * margin) / crop.height()));
        if (!(scale > 0f && scale < 1f)) return false;
        final boolean left = canvas.left + canvas.width() * info.auraSmallWindowScale / 2f
                < safe.centerX();
        final int x = left ? safe.left + margin
                : safe.right - margin - Math.round(canvas.width() * scale);
        final int y = safe.top + margin - Math.round(crop.top * scale);
        wct.setAuraSmallWindowGeometry(info.token,
                new Rect(x, y, x + canvas.width(), y + canvas.height()), scale, true);
        wct.setAlwaysOnTop(info.token, true);
        wct.reorder(info.token, true);
        android.util.Log.i("AuraAutoMini", "prepare task=" + info.taskId);
        return true;
    }

    private void restoreAuraMini() {
        if (!mTaskInfo.auraSmallWindowMini) return;
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        if (layout == null) return;
        final Rect safe = new Rect(); layout.getStableBounds(safe);
        final Rect canvas = mTaskInfo.configuration.windowConfiguration.getBounds();
        final float scale = Math.min(.74f, Math.min(safe.width() / (float) canvas.width(),
                safe.height() / (float) canvas.height()));
        final int left = Math.max(safe.left, Math.min(safe.right - Math.round(canvas.width() * scale),
                mAuraMiniRestoreBounds.isEmpty() ? safe.centerX() - Math.round(canvas.width() * scale / 2)
                        : mAuraMiniRestoreBounds.left));
        final int top = Math.max(safe.top, Math.min(safe.bottom - Math.round(canvas.height() * scale),
                mAuraMiniRestoreBounds.isEmpty() ? safe.top + Math.round(48 * layout.density())
                        : mAuraMiniRestoreBounds.top));
        final Rect bounds = new Rect(left, top, left + canvas.width(), top + canvas.height());
        mAuraTransitions.startTransition(android.view.WindowManager.TRANSIT_CHANGE,
                new WindowContainerTransaction().setAuraSmallWindowGeometry(
                        mTaskInfo.token, bounds, scale, false), null);
    }

    @Override
    public void close() {
        mAuraClosed = true;
        if (mAuraRevealVeil != null) { mAuraRevealVeil.close(); mAuraRevealVeil = null; }
        if (mAuraMini != null) { mAuraMini.release(); mAuraMini = null; }
        dismissAuraMoreMenu();
        if (mAuraBottom != null) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            releaseAuraBottom(wct);
            mBgExecutor.execute(() -> mTaskOrganizer.applyTransaction(wct));
        }
        closeDragResizeListener();
        super.close();
    }

    /** Mini restoration changes caption/footer crop before the app has finished relayout.
     * A task-local launch-style veil follows its existing animation, without input windows,
     * app captures, or another task transform. Ready chrome releases it with a short blend. */
    private final class AuraRevealVeil implements AutoCloseable {
        private final SurfaceControl mSurface;
        private final SurfaceControl mBackground;
        private final SurfaceControl mIcon;
        private final Runnable mTimeout = this::reveal;
        private ValueAnimator mFade;
        private boolean mReleased;

        AuraRevealVeil(RunningTaskInfo info, SurfaceControl.Transaction tx) {
            final Rect canvas = info.configuration.windowConfiguration.getBounds();
            final float density = info.configuration.densityDpi / 160f;
            final int size = Math.max(1, Math.min(Math.min(canvas.width(), canvas.height()),
                    Math.round(48f * density / info.auraSmallWindowScale)));
            mSurface = new SurfaceControl.Builder().setName("Aura restore veil " + info.taskId)
                    .setContainerLayer().setParent(getLeash()).build();
            mBackground = new SurfaceControl.Builder().setName("Aura restore veil background")
                    .setColorLayer().setParent(mSurface).build();
            mIcon = new SurfaceControl.Builder().setName("Aura restore veil app icon")
                    .setBufferSize(size, size).setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(false).setParent(mSurface).build();
            final int color = Color.alpha(mAuraCaptionColor) == 255 ? mAuraCaptionColor
                    : mContext.getResources().getColor(
                            R.color.aura_small_window_caption_background, null);
            tx.setLayer(mSurface, android.window.TaskConstants.TASK_CHILD_LAYER_RESIZE_VEIL)
                    .setWindowCrop(mSurface, canvas.width(), canvas.height())
                    .setAlpha(mSurface, 1f).show(mSurface)
                    .setColor(mBackground, new float[] {Color.red(color) / 255f,
                            Color.green(color) / 255f, Color.blue(color) / 255f})
                    .setWindowCrop(mBackground, canvas.width(), canvas.height())
                    .setLayer(mBackground, 0).show(mBackground)
                    .setPosition(mIcon, (canvas.width() - size) / 2f,
                            (canvas.height() - size) / 2f).setLayer(mIcon, 1).show(mIcon);
            final Surface iconSurface = new Surface(mIcon);
            try {
                final Drawable icon = info.topActivityInfo != null
                        ? info.topActivityInfo.loadIcon(mUserContext.getPackageManager())
                        : mUserContext.getPackageManager().getDefaultActivityIcon();
                final Canvas draw = iconSurface.lockCanvas(null);
                try {
                    draw.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    icon.setBounds(0, 0, size, size); icon.draw(draw);
                } finally { iconSurface.unlockCanvasAndPost(draw); }
            } catch (RuntimeException error) {
                Log.w("AuraWindowVeil", "Icon unavailable for task=" + info.taskId, error);
            } finally { iconSurface.release(); }
            mHandler.postDelayed(mTimeout, 3000);
            Log.i("AuraWindowVeil", "show restore task=" + info.taskId);
        }

        void reveal() {
            if (mReleased || mFade != null) return;
            mHandler.removeCallbacks(mTimeout);
            final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            mFade = ValueAnimator.ofFloat(1f, 0f);
            mFade.setDuration(160);
            mFade.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
            mFade.addUpdateListener(value -> {
                if (!mReleased && mSurface.isValid()) {
                    tx.setAlpha(mSurface, (float) value.getAnimatedValue()).apply();
                }
            });
            mFade.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    mFade = null;
                    tx.close(); close();
                    if (mAuraRevealVeil == AuraRevealVeil.this) mAuraRevealVeil = null;
                }
            });
            mFade.start();
        }

        @Override public void close() {
            if (mReleased) return;
            mReleased = true;
            mHandler.removeCallbacks(mTimeout);
            final ValueAnimator fade = mFade;
            mFade = null;
            if (fade != null && fade.isStarted()) fade.cancel();
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                if (mSurface.isValid()) tx.remove(mSurface).apply();
            }
            mIcon.release(); mBackground.release(); mSurface.release();
        }
    }

    @Override
    int getCaptionHeight(@WindowingMode int windowingMode) {
        return getCaptionHeightCalculator(mTaskInfo).apply(mContext, mDisplay);
    }

    /**
     * Keeps the caption at ~28dp physical only for scaled Aura tasks; every other task
     * (other displays, embedded children, non-standard or non-freeform windows) keeps the
     * platform default caption height.
     */
    private static BiFunction<Context, Display, Integer> getCaptionHeightCalculator(
            RunningTaskInfo taskInfo) {
        return (ctx, display) -> isAuraSmallWindowTask(taskInfo)
                && display.getDisplayId() == Display.DEFAULT_DISPLAY
                ? (int) Math.ceil(28 * ctx.getResources().getDisplayMetrics().density
                        / AuraSmallWindowViewport.decorScale(taskInfo))
                : loadDimensionPixelSize(ctx.getResources(), R.dimen.freeform_decor_caption_height);
    }

    /** Use WM's actual transform; only WM knows whether this root task is also a leaf. */
    static boolean isAuraSmallWindowTask(RunningTaskInfo taskInfo) {
        return taskInfo != null && taskInfo.auraSmallWindowScale > 0f
                && taskInfo.auraSmallWindowScale < 1f;
    }

    /** Scale applied by the framework to this task; 1f when the task is not scaled. */
    private static float getAuraSmallWindowScale(RunningTaskInfo taskInfo) {
        if (!isAuraSmallWindowTask(taskInfo)) return 1f;
        return taskInfo.auraSmallWindowScale;
    }

    @Override
    int getCaptionViewId() {
        return R.id.caption;
    }
}
