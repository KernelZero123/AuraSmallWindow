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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.PackageManager.FEATURE_PC;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.window.DesktopExperienceFlags.ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS;

import static com.android.wm.shell.windowdecor.DragPositioningCallbackUtility.getInputMethodFromMotionEvent;

import android.annotation.NonNull;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.KeyguardManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.freeform.AuraSmallWindowImeController;
import com.android.wm.shell.shared.FocusTransitionListener;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.shared.desktopmode.DesktopConfig;
import com.android.wm.shell.shared.desktopmode.DesktopState;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.FocusTransitionObserver;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */
public class CaptionWindowDecorViewModel implements WindowDecorViewModel, FocusTransitionListener {
    private static final String TAG = "CaptionWindowDecorViewModel";

    private final ShellTaskOrganizer mTaskOrganizer;
    private final IWindowManager mWindowManager;
    private final Context mContext;
    private final Handler mMainHandler;
    private final @ShellBackgroundThread ShellExecutor mBgExecutor;
    private final ShellExecutor mMainExecutor;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Transitions mTransitions;
    private final Region mExclusionRegion = Region.obtain();
    private final InputManager mInputManager;
    private final WindowDecorViewHostSupplier<WindowDecorViewHost> mWindowDecorViewHostSupplier;
    private final DesktopConfig mDesktopConfig;
    private final DesktopState mDesktopState;
    private TaskOperations mTaskOperations;
    private AuraSmallWindowImeController mAuraImeController;
    private final SparseArray<CaptionWindowDecoration> mAuraCaptions = new SparseArray<>();
    // Retain this through decoration destruction: Core can report the closing task as
    // visible until its organizer vanish arrives, after Shell has already become idle.
    private final SparseBooleanArray mAuraClosingTasks = new SparseBooleanArray();

    @Override
    public void onAuraTaskAppeared(RunningTaskInfo taskInfo) {
        mAuraClosingTasks.delete(taskInfo.taskId);
    }

    @Override
    public void setAuraSmallWindowImeController(AuraSmallWindowImeController controller) {
        mAuraImeController = controller;
    }
    private java.util.function.BiConsumer<Integer, Boolean> mTaskDragStateListener;

    @Override
    public void setTaskDragStateListener(
            java.util.function.BiConsumer<Integer, Boolean> listener) {
        mTaskDragStateListener = listener;
    }

    private FocusTransitionObserver mFocusTransitionObserver;

    /**
     * Whether to pilfer the next motion event to send cancellations to the windows below.
     * Useful when the caption window is spy and the gesture should be handled by the system
     * instead of by the app for their custom header content.
     */
    private boolean mShouldPilferCaptionEvents;

    private final SparseArray<WindowDecorationWrapper> mWindowDecorByTaskId = new SparseArray<>();

    private final ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region systemGestureExclusionUnrestricted) {
                    if (mContext.getDisplayId() != displayId) {
                        return;
                    }
                    mMainExecutor.execute(() -> {
                        mExclusionRegion.set(systemGestureExclusion);
                        onExclusionRegionChanged(displayId, mExclusionRegion);
                    });
                }
            };

    private final WindowDecorationWrapper.Factory mWindowDecorationWrapperFactory =
            new WindowDecorationWrapper.Factory();

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            @ShellMainThread ShellExecutor shellExecutor,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            Choreographer mainChoreographer,
            IWindowManager windowManager,
            ShellInit shellInit,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            FocusTransitionObserver focusTransitionObserver,
            WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            DesktopState desktopState,
            DesktopConfig desktopConfig) {
        mContext = context;
        mMainExecutor = shellExecutor;
        mMainHandler = mainHandler;
        mBgExecutor = bgExecutor;
        mWindowManager = windowManager;
        mMainChoreographer = mainChoreographer;
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        mFocusTransitionObserver = focusTransitionObserver;
        mWindowDecorViewHostSupplier = windowDecorViewHostSupplier;
        mInputManager = mContext.getSystemService(InputManager.class);
        mDesktopState = desktopState;
        mDesktopConfig = desktopConfig;

        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        if (android.os.SystemProperties.getBoolean("ro.aura.small_window", false)) {
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    final PendingResult pending = goAsync();
                    final int user = intent.getIntExtra("user_id", -1);
                    final String pkg = intent.getStringExtra("package");
                    mMainExecutor.execute(() -> {
                        if (mAuraReplacementActive || user < 0 || pkg == null || pkg.isEmpty()
                                || mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                            pending.setResultCode(0); pending.finish(); return;
                        }
                        mAuraReplacementActive = true;
                        new ReplacementRequest(user, pkg, pending).start();
                    });
                }
            }, UserHandle.ALL, new IntentFilter("com.android.systemui.AURA_PREPARE_SMALL_WINDOW"),
                    "android.permission.MANAGE_ACTIVITY_TASKS", mMainHandler,
                    Context.RECEIVER_EXPORTED);
        }
        try {
            mWindowManager.registerSystemGestureExclusionListener(mGestureExclusionListener,
                    mContext.getDisplayId());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register window manager callbacks", e);
        }
        mFocusTransitionObserver.setLocalFocusTransitionListener(this, mMainExecutor);
    }

    private boolean mAuraReplacementActive;

    /** Only trusted launchers ask; completion confirms Core is no longer displaying old windows.
     * No incoming activity is started by Shell, and no fixed delay authorizes a launch. */
    private final class ReplacementRequest {
        final int userId;
        final String incomingPackage;
        final BroadcastReceiver.PendingResult result;
        final Runnable timeout = () -> finish(false);
        final Runnable retry = () -> mTransitions.runOnIdle(this::dismissNext);
        boolean finished;
        ReplacementRequest(int user, String pkg, BroadcastReceiver.PendingResult pending) {
            userId = user; incomingPackage = pkg; result = pending;
        }
        void start() {
            mMainExecutor.executeDelayed(timeout, 4000);
            mTransitions.runOnIdle(this::dismissNext);
        }
        void finish(boolean success) {
            if (finished) return;
            finished = true;
            mMainExecutor.removeCallbacks(timeout);
            mMainExecutor.removeCallbacks(retry);
            mAuraReplacementActive = false;
            result.setResultCode(success ? 1 : 0);
            result.finish();
        }
        void dismissNext() {
            if (finished) return;
            if (mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                finish(false); return;
            }
            for (RunningTaskInfo current : mTaskOrganizer.getRunningTasks(Display.DEFAULT_DISPLAY)) {
                final int id = current.taskId;
                if (current == null || !current.isVisible || current.displayId != Display.DEFAULT_DISPLAY
                        || current.userId != userId || !current.isFreeform()
                        || !CaptionWindowDecoration.isAuraSmallWindowTask(current)
                        || current.getActivityType() != ACTIVITY_TYPE_STANDARD
                        || current.topActivity != null && incomingPackage.equals(
                                current.topActivity.getPackageName())) continue;
                final CaptionWindowDecoration caption = mAuraCaptions.get(id);
                final AuraSmallWindowInteractionController interaction = caption == null ? null
                        : caption.getAuraInteractionController();
                if (mAuraClosingTasks.get(id) || interaction == null
                        || !interaction.dismissForReplacement(committed -> {
                            if (finished) return;
                            if (!committed) { finish(false); return; }
                            // requestDismiss has already submitted its state transition. Wait
                            // for organizer callbacks before checking for the next old task.
                            mMainExecutor.execute(() -> mTransitions.runOnIdle(this::dismissNext));
                        })) {
                    mMainExecutor.executeDelayed(retry, 40);
                }
                return;
            }
            Log.i("AuraWindowReplace", "old windows exited; ready package=" + incomingPackage);
            finish(true);
        }
    }

    @Override
    public void onFocusedTaskChanged(RunningTaskInfo taskInfo, boolean isFocusedOnDisplay,
            boolean isFocusedGlobally) {
        if (mAuraClosingTasks.get(taskInfo.taskId)) return;
        final WindowDecorationWrapper decor = mWindowDecorByTaskId.get(taskInfo.taskId);
        final CaptionWindowDecoration aura = mAuraCaptions.get(taskInfo.taskId);
        if (aura != null && aura.getAuraInteractionController() != null
                && aura.getAuraInteractionController().isInteractionActive()) return;
        if (decor != null) {
            decor.relayout(decor.getTaskInfo(), isFocusedGlobally, decor.getExclusionRegion());
        }
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {}

    @Override
    public boolean onTaskOpening(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        mAuraClosingTasks.delete(taskInfo.taskId);
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (mAuraClosingTasks.get(taskInfo.taskId)) return;
        final CaptionWindowDecoration aura = mAuraCaptions.get(taskInfo.taskId);
        if (aura != null && aura.getAuraInteractionController() != null) {
            aura.getAuraInteractionController().onTaskInfoChanged(taskInfo);
            // The owned CHANGE keeps app/chrome fixed until final commit. Metadata callbacks
            // must not reset its preview matrix or force a fresh ViewHost layout mid-drag.
            if (aura.getAuraInteractionController().isInteractionActive()
                    && CaptionWindowDecoration.isAuraSmallWindowTask(taskInfo)
                    && taskInfo.isVisible) return;
        }
        final WindowDecorationWrapper decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (decoration == null) return;

        if (!shouldShowWindowDecor(taskInfo)) {
            destroyWindowDecoration(taskInfo);
            return;
        }

        if (ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS.isTrue()) {
            // Pass the current global focus status to avoid updates outside of a ShellTransition.
            decoration.relayout(
                    taskInfo, decoration.getHasGlobalFocus(), decoration.getExclusionRegion());
        } else {
            decoration.relayout(taskInfo, taskInfo.isFocused, decoration.getExclusionRegion());
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration aura = mAuraCaptions.get(taskInfo.taskId);
        if (aura != null && aura.getAuraInteractionController() != null) {
            aura.getAuraInteractionController().onTaskVanished();
        }
        // A task vanishing doesn't necessarily mean the task was closed, it could also mean its
        // windowing mode changed. We're only interested in closing tasks so checking whether
        // its info still exists in the task organizer is one way to disambiguate.
        final boolean closed = mTaskOrganizer.getRunningTaskInfo(taskInfo.taskId) == null;
        if (closed) {
            mAuraClosingTasks.delete(taskInfo.taskId);
            // Destroying the window decoration is usually handled when a TRANSIT_CLOSE transition
            // changes happen, but there are certain cases in which closing tasks aren't included
            // in transitions, such as when a non-visible task is closed. See b/296921167.
            // Destroy the decoration here in case the lack of transition missed it.
            destroyWindowDecoration(taskInfo);
        }
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (mAuraClosingTasks.get(taskInfo.taskId)) return;
        final WindowDecorationWrapper decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                    false /* setTaskCropAndPosition */,
                    mFocusTransitionObserver.hasGlobalFocus(taskInfo), mExclusionRegion,
                    /* inSyncWithTransition= */ true, taskSurface);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        mAuraClosingTasks.put(taskInfo.taskId, true);
        final CaptionWindowDecoration aura = mAuraCaptions.get(taskInfo.taskId);
        if (aura != null && aura.getAuraInteractionController() != null) {
            aura.getAuraInteractionController().onTaskVanished();
        }
        final WindowDecorationWrapper decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                false /* setTaskCropAndPosition */,
                mFocusTransitionObserver.hasGlobalFocus(taskInfo), mExclusionRegion,
                /* inSyncWithTransition= */ true, /* taskSurface */ null);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        mAuraCaptions.remove(taskInfo.taskId);
        final WindowDecorationWrapper decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
    }

    private void onExclusionRegionChanged(int displayId, @NonNull Region exclusionRegion) {
        final int decorCount = mWindowDecorByTaskId.size();
        for (int i = 0; i < decorCount; i++) {
            final WindowDecorationWrapper decoration = mWindowDecorByTaskId.valueAt(i);
            if (decoration.getTaskInfo().displayId != displayId) continue;
            decoration.onExclusionRegionChanged(exclusionRegion);
        }
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (mDisplayController.getDisplay(taskInfo.displayId) == null) {
            // If DisplayController doesn't have it tracked, it could be a private/managed display.
            return false;
        }
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return true;
        }
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_PINNED) {
            return false;
        }
        if (taskInfo.getActivityType() != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        final DisplayAreaInfo rootDisplayAreaInfo =
                mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId);
        if (rootDisplayAreaInfo != null) {
            return rootDisplayAreaInfo.configuration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_FREEFORM;
        }

        // It is possible that the rootDisplayAreaInfo is null when a task appears soon enough after
        // a new display shows up, because TDA may appear after task appears in WM shell. Instead of
        // fixing the synchronization issues, let's use other signals to "guess" the answer. It is
        // OK in this context because no other captions other than the legacy developer option
        // freeform and Kingyo/CF PC may use this class. WM shell should have full control over the
        // condition where captions should show up in all new cases such as desktop mode, for which
        // we should use different window decor view models. Ultimately Kingyo/CF PC may need to
        // spin up their own window decor view model when they start to care about multiple
        // displays.
        if (isPc()) {
            return true;
        }
        return taskInfo.displayId != Display.DEFAULT_DISPLAY
                && forcesDesktopModeOnExternalDisplays();
    }

    @Override
    public boolean prepareAuraMinimize(int taskId, WindowContainerTransaction wct) {
        if (mAuraClosingTasks.get(taskId)) return false;
        final CaptionWindowDecoration caption = mAuraCaptions.get(taskId);
        return caption != null && caption.prepareAuraMinimize(wct);
    }

    @Override
    public void ensureAuraWindowDecoration(RunningTaskInfo taskInfo, SurfaceControl taskSurface) {
        if (!CaptionWindowDecoration.isAuraSmallWindowTask(taskInfo)
                || mAuraClosingTasks.get(taskInfo.taskId) || taskInfo.numActivities == 0
                || !taskInfo.isVisible || taskSurface == null || !taskSurface.isValid()
                || mWindowDecorByTaskId.get(taskInfo.taskId) != null) return;
        // A launch immediately after recents finishes can change mode before the next
        // transition collects its participants. Its empty TransitionInfo never reaches
        // onTaskChanging. Restore both chrome and the steady task pose after Shell is idle;
        // never reset an existing decoration, drag preview or transition-owned leash.
        final float scale = taskInfo.auraSmallWindowScale;
        final Point position = taskInfo.positionInParent;
        final float density = taskInfo.configuration.densityDpi / 160f;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            createWindowDecoration(taskInfo, taskSurface, t, t);
            t.setPosition(taskSurface, position.x, position.y)
                    .setMatrix(taskSurface, scale, 0f, 0f, scale)
                    .setWindowCrop(taskSurface, AuraSmallWindowViewport.crop(taskInfo))
                    .setCornerRadius(taskSurface, 24f * density / scale)
                    .setAlpha(taskSurface, 1f).show(taskSurface);
            t.apply();
        }
        Log.i("AuraWindowRecovery", "restored task=" + taskInfo.taskId
                + " position=" + position + " scale=" + scale);
    }

    private void createWindowDecoration(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final WindowDecorationWrapper oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration caption = new CaptionWindowDecoration(
                        mContext,
                        mContext.createContextAsUser(UserHandle.of(taskInfo.userId),
                                0 /* flags */),
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mTransitions,
                        mMainExecutor,
                        mBgExecutor,
                        mMainChoreographer,
                        mSyncQueue,
                        mWindowDecorViewHostSupplier,
                        mDesktopConfig);
        final WindowDecorationWrapper windowDecoration = mWindowDecorationWrapperFactory
                .fromCaptionDecoration(caption);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);
        mAuraCaptions.put(taskInfo.taskId, caption);
        caption.setAuraReadyAllowed(() -> !mAuraClosingTasks.get(taskInfo.taskId)
                && mAuraCaptions.get(taskInfo.taskId) == caption);
        if (mAuraImeController != null && CaptionWindowDecoration.isAuraSmallWindowTask(taskInfo)) {
            caption.setAuraInteractionController(new AuraSmallWindowInteractionController(
                    new AuraSmallWindowInteractionController.Ports() {
                        @Override public RunningTaskInfo taskInfo() {
                            return mTaskOrganizer.getRunningTaskInfo(taskInfo.taskId);
                        }
                        @Override public float touchSlopPx(int displayId) {
                            final Display display = mDisplayController.getDisplay(displayId);
                            final Context context = display == null ? mContext
                                    : mContext.createDisplayContext(display);
                            return ViewConfiguration.get(context).getScaledTouchSlop();
                        }
                        @Override public boolean isBaselineCurrent(RunningTaskInfo original,
                                AuraSmallWindowGestureSession session) {
                            final RunningTaskInfo current = taskInfo();
                            return current != null && mAuraCaptions.get(current.taskId) == caption
                                    && current.isVisible && current.token.equals(original.token)
                                    && current.parentTaskId == original.parentTaskId
                                    && current.displayId == original.displayId
                                    && current.configuration.windowConfiguration.getRotation()
                                        == original.configuration.windowConfiguration.getRotation()
                                    && current.configuration.densityDpi
                                        == original.configuration.densityDpi
                                    && current.configuration.windowConfiguration.getBounds().width()
                                        == session.snapshot.logicalWidth
                                    && current.configuration.windowConfiguration.getBounds().height()
                                        == session.snapshot.logicalHeight;
                        }
                        @Override public void applyChromePreview(SurfaceControl.Transaction tx,
                                RunningTaskInfo info, AuraSmallWindowGestureSession session,
                                float scale) {
                            caption.applyAuraChromePreview(tx,
                                    new Rect(0, 0, session.snapshot.logicalWidth,
                                            session.snapshot.logicalHeight), scale);
                        }
                        @Override public void requestDismiss(RunningTaskInfo info) {
                            if (mTaskOperations != null) {
                                mAuraClosingTasks.put(info.taskId, true);
                                try {
                                    mTaskOperations.backgroundAuraTask(info);
                                } finally {
                                    // Suppress stale preview recovery until the mode/back
                                    // transition settles. Unlike a removed task, this task is
                                    // retained and must remain eligible for future openings.
                                    mMainExecutor.execute(() -> mTransitions.runOnIdle(() -> {
                                        final RunningTaskInfo current =
                                                mTaskOrganizer.getRunningTaskInfo(info.taskId);
                                        mAuraClosingTasks.delete(info.taskId);
                                        if (current == null || !shouldShowWindowDecor(current)) {
                                            destroyWindowDecoration(info);
                                        } else {
                                            onTaskInfoChanged(current);
                                        }
                                    }));
                                }
                            }
                        }
                        @Override public void requestFullscreen(RunningTaskInfo info) {
                            final DisplayAreaInfo area = mRootTaskDisplayAreaOrganizer
                                    .getDisplayAreaInfo(info.displayId);
                            if (mTaskOperations != null && area != null) {
                                mTaskOperations.maximizeTask(info,
                                        area.configuration.windowConfiguration.getWindowingMode());
                            }
                        }
                        @Override public void onBottomPreview(RunningTaskInfo info,
                                boolean previewing) { caption.setAuraBottomPreview(previewing); }
                        @Override public void onInteractionFinished() {
                            mMainExecutor.execute(() -> mTransitions.runOnIdle(() -> {
                                final RunningTaskInfo current = taskInfo();
                                if (current != null && mAuraCaptions.get(current.taskId) == caption) {
                                    onTaskInfoChanged(current);
                                }
                            }));
                        }
                    }, mAuraImeController, mTransitions, mMainChoreographer));
        }

        final FluidResizeTaskPositioner taskPositioner =
                new FluidResizeTaskPositioner(mTaskOrganizer, mTransitions, windowDecoration,
                        mDisplayController, mDesktopState);
        final CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener,
                /* onLongClickListener= */ null, /* onGenericMotionListener= */ null);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.setTaskDragResizer(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* setTaskCropAndPosition */,
                mFocusTransitionObserver.hasGlobalFocus(taskInfo), mExclusionRegion,
                /* inSyncWithTransition= */ true, taskSurface);
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;
        private final int mDisplayId;

        private int mDragPointerId = -1;
        private boolean mIsDragging;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this, 0 /* holdToDragMinDurationMs */,
                    ViewConfiguration.get(mContext).getScaledTouchSlop());
            mDisplayId = taskInfo.displayId;
        }

        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.close_window) {
                mAuraClosingTasks.put(mTaskId, true);
                mTaskOperations.closeTask(mTaskToken);
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey(mDisplayId);
            } else if (id == R.id.minimize_window) {
                // This minimize button uses the same effect for any minimization. The last argument
                // doesn't matter.
                mTaskOperations.minimizeTask(mTaskToken, mTaskId, /* isLastTask= */ false);
            } else if (id == R.id.maximize_window) {
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                final DisplayAreaInfo rootDisplayAreaInfo =
                        mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId);
                mTaskOperations.maximizeTask(taskInfo,
                        rootDisplayAreaInfo.configuration.windowConfiguration.getWindowingMode());
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            final RunningTaskInfo touchInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            final boolean aura = CaptionWindowDecoration.isAuraSmallWindowTask(touchInfo);
            // Aura: tap the dots for the menu, drag the same target for movement. The blank
            // caption flanks no longer start a task drag. Other window decorations keep caption.
            if (v.getId() != (aura ? R.id.aura_more_button : R.id.caption)) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if (!mFocusTransitionObserver.hasGlobalFocus(taskInfo)) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    wct.reorder(mTaskToken, true /* onTop */, true /* includingParents */);
                    mSyncQueue.queue(wct);
                }
            }
            final WindowDecorationWrapper decoration = mWindowDecorByTaskId.get(mTaskId);

            final int actionMasked = e.getActionMasked();
            final boolean isDown = actionMasked == MotionEvent.ACTION_DOWN;
            final boolean isUpOrCancel = actionMasked == MotionEvent.ACTION_CANCEL
                    || actionMasked == MotionEvent.ACTION_UP;
            if (isDown) {
                final boolean downInCustomizableCaptionRegion =
                        decoration.checkTouchEventInCustomizableRegion(e);
                final boolean downInExclusionRegion = mExclusionRegion.contains(
                        (int) e.getRawX(), (int) e.getRawY());
                final boolean isTransparentCaption =
                        TaskInfoKt.isTransparentCaptionBarAppearance(decoration.getTaskInfo());
                // MotionEvent's coordinates are relative to view, we want location in window
                // to offset position relative to caption as a whole.
                int[] viewLocation = new int[2];
                v.getLocationInWindow(viewLocation);
                final boolean isResizeEvent = decoration.shouldResizeListenerHandleEvent(e,
                        new Point(viewLocation[0], viewLocation[1]));
                // The caption window may be a spy window when the caption background is
                // transparent, which means events will fall through to the app window. Make
                // sure to cancel these events if they do not happen in the intersection of the
                // customizable region and what the app reported as exclusion areas, because
                // the drag-move or other caption gestures should take priority outside those
                // regions.
                mShouldPilferCaptionEvents = !(downInCustomizableCaptionRegion
                        && downInExclusionRegion && isTransparentCaption) && !isResizeEvent;
            }

            if (!mShouldPilferCaptionEvents) {
                // The event will be handled by a window below or pilfered by resize handler.
                return false;
            }
            // Otherwise pilfer so that windows below receive cancellations for this gesture, and
            // continue normal handling as a caption gesture.
            if (mInputManager != null) {
                // TODO(b/352127475): Only pilfer once per gesture
                mInputManager.pilferPointers(v.getViewRootImpl().getInputToken());
            }
            if (isUpOrCancel) {
                // Gesture is finished, reset state.
                mShouldPilferCaptionEvents = false;
            }
            return mDragDetector.onMotionEvent(e);
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if a drag is happening; or {@code false} if it is not
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo == null || taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = -1;
                    if (CaptionWindowDecoration.isAuraSmallWindowTask(taskInfo)
                            && (mAuraImeController == null
                                || !mAuraImeController.canBeginCaptionDrag(mTaskId))) return false;
                    if (mTaskDragStateListener != null) {
                        mTaskDragStateListener.accept(mTaskId, true);
                    }
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getDisplayId(), e.getRawX(0), e.getRawY(0),
                            getInputMethodFromMotionEvent(e));
                    mIsDragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDragPointerId < 0) return false;
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final WindowDecorationWrapper decoration = mWindowDecorByTaskId.get(mTaskId);
                    // If a decor's resize drag zone is active, don't also try to reposition it.
                    if (decoration.isHandlingDragResize()) break;
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getDisplayId(),
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (mDragPointerId < 0) return false;
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    final Rect newTaskBounds = mDragPositioningCallback.onDragPositioningEnd(
                            e.getDisplayId(),
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(newTaskBounds,
                            mWindowDecorByTaskId.get(mTaskId).getValidDragArea());
                    if (newTaskBounds != taskInfo.configuration.windowConfiguration.getBounds()) {
                        final WindowContainerTransaction wct = new WindowContainerTransaction();
                        wct.setBounds(taskInfo.token, newTaskBounds);
                        mTransitions.startTransition(TRANSIT_CHANGE, wct, null);
                    }
                    if (mTaskDragStateListener != null) {
                        mTaskDragStateListener.accept(mTaskId, false);
                    }
                    final boolean wasDragging = mIsDragging;
                    mIsDragging = false;
                    mDragPointerId = -1;
                    return wasDragging;
                }
            }
            return true;
        }
    }

    /**
     * Returns if this device is a PC.
     */
    private boolean isPc() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_PC);
    }

    private boolean forcesDesktopModeOnExternalDisplays() {
        final ContentResolver resolver = mContext.getContentResolver();
        return Settings.Global.getInt(resolver,
                DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;
    }
}
