/* Copyright 2026 Aura. Licensed under the Apache License, Version 2.0. */
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.WindowAnimationState;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.concurrent.atomic.AtomicBoolean;

/** A destination of the existing nav gesture, never an independent global input monitor. */
final class AuraSmallWindowSwipeController {
    private static final String TAG = "AuraWindowSwipe";
    private final Context mContext;
    private final float mDensity;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mRemoveCover = this::removeCover;
    private boolean mRejected;
    private boolean mCandidate;
    private boolean mCommitted;
    private int mTaskId = -1;
    private TaskInfo mLaunchTaskInfo;
    private QuickstepLauncher mLauncher;
    private TextView mHint;
    private FrameLayout mCover;
    private FrameLayout mCoverCard;
    private ImageView mCoverIcon;
    private Drawable mVeilIcon;
    private int mVeilColor;
    private final RectF mCoverRect = new RectF();
    private float mCoverProgress = 1f;
    private boolean mCoverRequested;
    private final java.util.ArrayList<Runnable> mCoverContinuations = new java.util.ArrayList<>();
    private WindowManager mCoverWindowManager;
    private boolean mCoverFading;
    private boolean mReadyReceiverRegistered;
    private Rect mPendingReadyBounds;
    private float mPendingReadyScale;
    private final BroadcastReceiver mReadyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!mCommitted || mCover == null
                    || intent.getIntExtra("task_id", -1) != mTaskId
                    || intent.getIntExtra("user_id", -1) != UserHandle.myUserId()) return;
            final Rect bounds = intent.getParcelableExtra("bounds", Rect.class);
            final float scale = intent.getFloatExtra("scale", -1f);
            if (bounds == null || bounds.isEmpty() || !Float.isFinite(scale)
                    || scale <= 0f || scale >= 1f) return;
            if (!mBounds.equals(bounds) || Math.abs(scale - mScale) > .001f) {
                // A protected ready signal can beat the remote handoff's main-thread post.
                // Retain it, but fade only after matching WM's authoritative committed pose.
                mPendingReadyBounds = new Rect(bounds);
                mPendingReadyScale = scale;
                return;
            }
            Log.i(TAG, "chrome drawn; fade cover task=" + mTaskId);
            fadeCover();
        }
    };
    private final Rect mBounds = new Rect();
    private final RectF mVisible = new RectF();
    private float mScale;
    private boolean mDropLeft;

    AuraSmallWindowSwipeController(Context context) {
        mContext = context;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    void onMotion(MotionEvent event, float downX, float downY, boolean rejected,
            TaskInfo task, QuickstepLauncher launcher) {
        if (mCommitted) return;
        mLauncher = launcher;
        mRejected |= rejected;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || mRejected || !eligible(task)
                || launcher == null) {
            mCandidate = false;
            dismissHint();
            return;
        }
        final View root = launcher.getRootView();
        final int width = root.getWidth(), height = root.getHeight();
        if (width <= 0 || height <= width || downY < height - dp(100)) {
            mCandidate = false;
            dismissHint();
            return;
        }
        final float x = event.getX(), y = event.getY();
        final float up = downY - y, sideways = Math.abs(downX - x);
        mTaskId = task.taskId;
        mDropLeft = x < width / 2f;
        mCandidate = x >= 0 && x <= width && y >= 0
                && up > height * .45f && sideways > dp(56)
                && (x < width * .32f || x > width * .68f) && y < height * .45f;
        if (action == MotionEvent.ACTION_UP) {
            dismissHint();
        } else if (up > height * .18f && sideways > dp(24)) {
            showHint(root, mCandidate);
        } else {
            dismissHint();
        }
    }

    private boolean eligible(TaskInfo task) {
        return SystemProperties.getBoolean("ro.aura.small_window", false)
                && task != null && task.displayId == 0 && task.userId == UserHandle.myUserId()
                && task.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD
                && task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                && task.isResizeable && task.supportsMultiWindow && task.topActivity != null
                && (task.pictureInPictureParams == null
                        || !task.pictureInPictureParams.isAutoEnterEnabled());
    }

    boolean isCandidate() { return mCandidate && !mRejected; }
    boolean isCommitted() { return mCommitted; }
    RectF getVisibleTarget() { return new RectF(mVisible); }
    float getLogicalCornerRadius() { return dp(24) / mScale; }

    boolean commit(RemoteAnimationTarget target, QuickstepLauncher launcher) {
        if (!isCandidate() || target == null || target.taskId != mTaskId
                || !eligible(target.taskInfo) || launcher == null) return false;
        final View root = launcher.getRootView();
        final WindowInsets windowInsets = root.getRootWindowInsets();
        final Insets bars = windowInsets == null ? Insets.NONE
                : windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
        final int width = root.getWidth() - bars.left - bars.right;
        final int height = root.getHeight() - bars.top - bars.bottom;
        if (width <= 0 || height <= width) return false;
        mScale = Math.max(50, Math.min(90,
                SystemProperties.getInt("ro.aura.small_window_scale", 74))) / 100f;
        final int canvasHeight = Math.min(height, Math.round(width * 16f / 9f));
        final int left = mDropLeft ? bars.left + dp(8)
                : root.getWidth() - bars.right - Math.round(width * mScale) - dp(8);
        final int top = bars.top + dp(8);
        mBounds.set(left, top, left + width, top + canvasHeight);
        mVisible.set(left, top, left + width * mScale, top + canvasHeight * mScale);
        mCoverRect.set(mVisible);
        mLauncher = launcher;
        mCommitted = true;
        mLaunchTaskInfo = target.taskInfo;
        prepareVeilAssets(mLaunchTaskInfo);
        dismissHint();
        Log.i(TAG, "commit task=" + mTaskId + " visible=" + mVisible);
        return true;
    }

    private int dp(float value) { return Math.round(value * mDensity); }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private void showHint(View root, boolean active) {
        if (mHint == null) {
            mHint = new TextView(mContext);
            mHint.setText("松手切换为小窗");
            mHint.setTextColor(Color.WHITE);
            mHint.setTextSize(14);
            mHint.setGravity(Gravity.CENTER);
            mHint.setPadding(dp(12), dp(18), dp(12), dp(18));
            mHint.setBackground(background(0xDC20242A));
            mHint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(148),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            WindowInsets insets = root.getRootWindowInsets();
            lp.leftMargin = dp(8);
            lp.topMargin = dp(8) + (insets == null ? 0 : insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top);
            ((ViewGroup) root).addView(mHint, lp);
        }
        mHint.setAlpha(active ? 1f : .55f);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mHint.getLayoutParams();
        final int left = mDropLeft ? dp(8) : root.getWidth() - dp(156);
        if (lp.leftMargin != left) {
            lp.leftMargin = left;
            mHint.setLayoutParams(lp);
        }
        mHint.setScaleX(active ? 1.04f : 1f);
        mHint.setScaleY(active ? 1.04f : 1f);
    }

    private void dismissHint() {
        if (mHint != null && mHint.getParent() instanceof ViewGroup parent) {
            parent.removeView(mHint);
        }
        mHint = null;
    }

    private int veilFallbackColor(TaskInfo task) {
        final int color = task != null && task.taskDescription != null
                ? task.taskDescription.getBackgroundColor() : Color.TRANSPARENT;
        if (Color.alpha(color) == 255) return color;
        final int uiMode = task == null ? mContext.getResources().getConfiguration().uiMode
                : task.configuration.uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? 0xff202124 : 0xfffafafa;
    }

    private int loadVeilColor(TaskInfo task) {
        final int fallback = veilFallbackColor(task);
        if (task == null || task.topActivityInfo == null) return fallback;
        try {
            final Context app = mContext.createPackageContext(task.topActivityInfo.packageName, 0)
                    .createConfigurationContext(task.configuration);
            app.setTheme(task.topActivityInfo.getThemeResource());
            final TypedArray values = app.obtainStyledAttributes(
                    new int[] {android.R.attr.windowBackground});
            final Drawable background;
            try { background = values.getDrawable(0); }
            finally { values.recycle(); }
            final int color = background instanceof ColorDrawable solid ? solid.getColor()
                    : background instanceof GradientDrawable gradient && gradient.getColor() != null
                            ? gradient.getColor().getDefaultColor() : Color.TRANSPARENT;
            return Color.alpha(color) == 255 ? color : fallback;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
            return fallback;
        }
    }

    /** Launch-style veil for the nav handoff; never depends on obtaining an app snapshot. */
    void captureCover(RecentsAnimationController controller, Runnable finish) {
        if (controller != null) {
            mCoverRect.set(mVisible);
            mCoverProgress = 1f;
            applyCoverPose();
        }
        if (mCover != null) {
            afterCoverDraw(finish);
            return;
        }
        mCoverContinuations.add(finish);
        if (mCoverRequested) return;
        mCoverRequested = true;
        mMain.postDelayed(mRemoveCover, 3000);
        // The fallback is always drawable; asset I/O must not postpone the early veil.
        createLaunchCover(mVeilIcon, mVeilColor);
    }

    private void prepareVeilAssets(TaskInfo task) {
        mVeilColor = veilFallbackColor(task);
        final int taskId = mTaskId;
        UI_HELPER_EXECUTOR.execute(() -> {
            Drawable icon = null;
            try {
                if (task != null && task.topActivityInfo != null) {
                    icon = task.topActivityInfo.loadIcon(mContext.getPackageManager());
                }
            } catch (RuntimeException error) { Log.w(TAG, "Veil icon unavailable", error); }
            final Drawable result = icon;
            final int color = loadVeilColor(task);
            MAIN_EXECUTOR.execute(() -> {
                if (!mCommitted || taskId != mTaskId) return;
                mVeilIcon = result;
                mVeilColor = color;
                if (mCoverCard != null) mCoverCard.setBackground(background(color));
                if (mCoverIcon != null && result != null) mCoverIcon.setImageDrawable(result);
            });
        });
    }

    /** Start during the committed nav spring and follow its exact visible pose. */
    void updateCover(RectF rect, float progress) {
        if (!mCommitted || mCoverFading) return;
        mCoverRect.set(rect);
        mCoverProgress = Math.max(0f, Math.min(1f, (progress - .10f) / .20f));
        if (progress >= .10f && !mCoverRequested && mCover == null) {
            captureCover(null, () -> { });
        }
        applyCoverPose();
    }

    private void applyCoverPose() {
        if (mCoverCard == null) return;
        mCoverCard.setPivotX(0f); mCoverCard.setPivotY(0f);
        mCoverCard.setTranslationX(mCoverRect.left);
        mCoverCard.setTranslationY(mCoverRect.top);
        final float sx = mCoverRect.width() / Math.max(1f, mVisible.width());
        final float sy = mCoverRect.height() / Math.max(1f, mVisible.height());
        mCoverCard.setScaleX(sx);
        mCoverCard.setScaleY(sy);
        // The spring changes aspect ratio via crop. Keep the app icon circular as
        // the card itself follows both edges, rather than stretching its artwork.
        if (mCoverIcon != null) mCoverIcon.setScaleY(sx / Math.max(.001f, sy));
        mCoverCard.setAlpha(mCoverProgress);
    }

    private void createLaunchCover(Drawable icon, int color) {
        if (!mCommitted || mLauncher == null || mLauncher.isDestroyed()) {
            finishCoverPreparation(); return;
        }
        try {
            final Context coverContext = mLauncher.createWindowContext(
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG, null);
            mCoverWindowManager = coverContext.getSystemService(WindowManager.class);
            mCover = new FrameLayout(coverContext);
            mCoverCard = new FrameLayout(coverContext);
            mCoverCard.setBackground(background(color));
            mCoverCard.setClipToOutline(true);
            mCover.addView(mCoverCard, new FrameLayout.LayoutParams(
                    Math.round(mVisible.width()), Math.round(mVisible.height())));
            ImageView image = new ImageView(coverContext);
            mCoverIcon = image;
            image.setImageDrawable(icon != null ? icon
                    : mContext.getPackageManager().getDefaultActivityIcon());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mCoverCard.addView(image,
                    new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
            applyCoverPose();
            // Its own trusted window is unaffected by Home's recents transforms.
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setFitInsetsTypes(0); lp.setTrustedOverlay();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("Aura small window launch veil " + mTaskId);
            mCoverWindowManager.addView(mCover, lp);
            Log.i(TAG, "launch veil task=" + mTaskId);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Launch veil unavailable", unavailable);
            removeCover();
        }
        finishCoverPreparation();
    }

    private void finishCoverPreparation() {
        final java.util.ArrayList<Runnable> callbacks =
                new java.util.ArrayList<>(mCoverContinuations);
        mCoverContinuations.clear();
        for (Runnable callback : callbacks) afterCoverDraw(callback);
    }

    private void afterCoverDraw(Runnable finish) {
        if (mCover == null) finish.run();
        else {
            // Leave recents alive until the launch-style veil has had a chance to draw.
            final FrameLayout cover = mCover;
            final AtomicBoolean resumed = new AtomicBoolean();
            final Runnable proceed = () -> {
                if (resumed.compareAndSet(false, true)) finish.run();
            };
            mMain.postDelayed(proceed, 160);
            cover.postOnAnimation(() -> cover.postOnAnimation(() -> {
                mMain.removeCallbacks(proceed);
                proceed.run();
            }));
        }
    }

    void launch() {
        if (!mCommitted) { removeCover(); return; }
        if (mLaunchTaskInfo == null || mLaunchTaskInfo.topActivity == null) { cancel(); return; }
        prepareReplacement(mContext, mLaunchTaskInfo.userId,
                mLaunchTaskInfo.topActivity.getPackageName(), mMain, () -> {
                    if (mCommitted && !mRejected) launchPrepared();
                }, () -> {
                    removeCover();
                    Log.w(TAG, "Previous small window could not finish exit");
                });
    }

    /** Shared by the navigation drop and the recents small-window shortcut. */
    static void prepareReplacement(Context context, int userId, String pkg, Handler handler,
            Runnable ready, Runnable failure) {
        if (!SystemProperties.getBoolean("ro.aura.small_window", false)) { ready.run(); return; }
        final AtomicBoolean completed = new AtomicBoolean();
        final Runnable timeout = () -> { if (completed.compareAndSet(false, true)) failure.run(); };
        handler.postDelayed(timeout, 4500);
        context.sendOrderedBroadcastAsUser(new Intent("com.android.systemui.AURA_PREPARE_SMALL_WINDOW")
                .setPackage("com.android.systemui").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("user_id", userId).putExtra("package", pkg), UserHandle.of(userId), null,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        if (!completed.compareAndSet(false, true)) return;
                        handler.removeCallbacks(timeout);
                        if (getResultCode() == 1 && !context.getSystemService(
                                android.app.KeyguardManager.class).isKeyguardLocked()) ready.run();
                        else failure.run();
                    }
                }, handler, 0, null, null);
    }

    private void launchPrepared() {
        if (!mCommitted) { removeCover(); return; }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        options.setLaunchDisplayId(0);
        options.setLaunchBounds(new Rect(mBounds));
        options.setRemoteTransition(new RemoteTransition(new Handoff(), "AuraWindowSwipe"));
        if (mCover != null && !mReadyReceiverRegistered) {
            mContext.registerReceiver(mReadyReceiver,
                    new IntentFilter("com.android.launcher3.AURA_WINDOW_CHROME_READY"),
                    "android.permission.STATUS_BAR_SERVICE", mMain, Context.RECEIVER_EXPORTED);
            mReadyReceiverRegistered = true;
        }
        // A missing/rejected task must not leave the cover or hint on the desktop.
        mMain.postDelayed(mRemoveCover, 3000);
        UI_HELPER_EXECUTOR.execute(() -> {
            final boolean started = ActivityManagerWrapper.getInstance()
                    .startActivityFromRecents(mTaskId, options);
            if (!started) MAIN_EXECUTOR.execute(() -> {
                removeCover();
                Log.w(TAG, "Task could not enter small window: " + mTaskId);
            });
        });
    }

    void cancel() {
        mCandidate = false;
        mCommitted = false;
        mRejected = true;
        dismissHint();
        removeCover();
    }

    private void removeCover() {
        mMain.removeCallbacks(mRemoveCover);
        if (mReadyReceiverRegistered) {
            mContext.unregisterReceiver(mReadyReceiver);
            mReadyReceiverRegistered = false;
        }
        if (mCover != null && mCoverWindowManager != null) {
            mCover.animate().withEndAction(null).cancel();
            try { mCoverWindowManager.removeViewImmediate(mCover); }
            catch (RuntimeException detached) { Log.w(TAG, "Cover already removed", detached); }
        }
        mCover = null; mCoverWindowManager = null;
        mCoverCard = null;
        mCoverIcon = null;
        mCoverRequested = false;
        mCoverFading = false;
        mPendingReadyBounds = null;
    }

    private void adoptCommittedPose(int taskId, Rect bounds, float scale) {
        if (!mCommitted || taskId != mTaskId || mCover == null || mCoverFading) return;
        mBounds.set(bounds);
        mScale = scale;
        mCoverRect.set(bounds.left, bounds.top, bounds.left + bounds.width() * scale,
                bounds.top + bounds.height() * scale);
        applyCoverPose();
        if (mBounds.equals(mPendingReadyBounds)
                && Math.abs(mPendingReadyScale - mScale) <= .001f) fadeCover();
        mPendingReadyBounds = null;
    }

    private void fadeCover() {
        if (mCover == null || mCoverFading) return;
        mCoverFading = true;
        mMain.removeCallbacks(mRemoveCover);
        final FrameLayout cover = mCover;
        // A frame-commit callback precedes presentation. Keep the opaque veil over
        // that boundary, then blend into the ready app/chrome instead of removing a layer.
        cover.postOnAnimation(() -> {
            if (mCover != cover) return;
            cover.animate().alpha(0f).setDuration(160).setStartDelay(0)
                    .setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f))
                    .withEndAction(() -> { if (mCover == cover) removeCover(); }).start();
        });
    }

    private void retireCoverAfterDraw() {
        // Transition completion is not a buffer-ready signal. Shell removes the cover
        // through the protected receiver when caption/footer and task pose are committed.
        // Keep launch()'s timeout for a removed/rejected task, including empty transitions.
    }

    /** The nav spring already reached this rectangle. Commit without a second open animation. */
    private final class Handoff extends IRemoteTransition.Stub {
        @Override public void startAnimation(IBinder token, TransitionInfo info,
                SurfaceControl.Transaction transaction, IRemoteTransitionFinishedCallback callback) {
            final SurfaceControl.Transaction finish = new SurfaceControl.Transaction();
            try {
                for (TransitionInfo.Change change : info.getChanges()) {
                    TaskInfo task = change.getTaskInfo();
                    if (task == null || task.taskId != mTaskId || task.displayId != 0
                            || task.userId != UserHandle.myUserId() || !task.isFreeform()
                            || change.getParent() != null || !change.getLeash().isValid()) continue;
                    Rect bounds = change.getEndAbsBounds();
                    final float scale = change.getAuraSmallWindowEndScale();
                    if (bounds.isEmpty() || scale <= 0f || scale >= 1f) continue;
                    final int rootIndex = info.findRootIndex(task.displayId);
                    if (rootIndex < 0) continue;
                    TransitionInfo.Root root = info.getRoot(rootIndex);
                    if (!root.getLeash().isValid()) continue;
                    transaction.reparent(change.getLeash(), root.getLeash());
                    for (SurfaceControl.Transaction pose : new SurfaceControl.Transaction[] {
                            transaction, finish}) {
                        pose
                            .setPosition(change.getLeash(), bounds.left - root.getOffset().x,
                                    bounds.top - root.getOffset().y)
                            .setMatrix(change.getLeash(), scale, 0f, 0f, scale)
                            .setWindowCrop(change.getLeash(), bounds.width(), bounds.height())
                            .setCornerRadius(change.getLeash(), dp(24) / scale)
                            .setAlpha(change.getLeash(), 1f).show(change.getLeash());
                    }
                    final int committedTaskId = task.taskId;
                    final Rect committedBounds = new Rect(bounds);
                    MAIN_EXECUTOR.execute(() -> adoptCommittedPose(
                            committedTaskId, committedBounds, scale));
                    Log.i(TAG, "ready task=" + mTaskId + " bounds=" + bounds + " scale=" + scale);
                }
                transaction.apply();
            } catch (RuntimeException error) {
                // A task removed/rotated during the handoff must still finish its transition.
                Log.w(TAG, "Handoff surface changed", error);
            } finally {
                try { callback.onTransitionFinished(null, finish); }
                catch (RemoteException error) { Log.w(TAG, "Handoff finish failed", error); }
                finish.close();
                transaction.close();
                info.releaseAllSurfaces();
                MAIN_EXECUTOR.execute(AuraSmallWindowSwipeController.this::retireCoverAfterDraw);
            }
        }
        @Override public void mergeAnimation(IBinder token, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder target,
                IRemoteTransitionFinishedCallback callback) {
            // No running animation to merge; release this client's parcel copies only.
            t.close();
            info.releaseAllSurfaces();
        }
        @Override public void takeOverAnimation(IBinder token, TransitionInfo info,
                SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback callback,
                WindowAnimationState[] states) { startAnimation(token, info, t, callback); }
        @Override public void onTransitionConsumed(IBinder token, boolean aborted) {
            MAIN_EXECUTOR.execute(AuraSmallWindowSwipeController.this::retireCoverAfterDraw);
        }
    }
}
