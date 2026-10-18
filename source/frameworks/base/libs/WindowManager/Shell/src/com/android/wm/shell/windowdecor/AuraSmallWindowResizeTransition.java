/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wm.shell.windowdecor;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.freeform.AuraSmallWindowTransitionAnimation;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;

/**
 * Main-thread P2 surface owner. One unchanged CHANGE keeps Core's task geometry frozen until UP.
 * Both resize corners and the bottom bar share this surface owner and IME lease. Corners commit
 * same-canvas geometry; bottom previews release the CHANGE before the normal close/mode action.
 *
 * <p>Lifecycle guarantees this owner provides to the coordinator:
 * <ul>
 *   <li><b>UP/CANCEL before the CHANGE is ready.</b> Events are accepted and latched in the
 *       session while the transition is still pending; the first frame after the CHANGE becomes
 *       ready renders the newest state, including a completed commit.</li>
 *   <li><b>At most one native surface pose per frame.</b> MOVEs are coalesced through the
 *       Choreographer; the applied application bounds are never changed per MOVE.</li>
 *   <li><b>Exactly one completion.</b> Commit, cancel, refusal, consume/abort, merge and task
 *       invalidation all funnel through {@link #complete} / {@link #release} /
 *       {@link #abandon}, which are idempotent and notify {@link Hooks#onFinished} once.</li>
 *   <li><b>Immediate cancel.</b> There is no timed rebound this round: a terminal cancel restores
 *       the frozen pose in the very next frame transaction and completes without any geometry
 *       WCT. The planned 180ms rebound is deferred with the animation work.</li>
 *   <li><b>No permanent ownership.</b> If the owned CHANGE never becomes ready, the owner gives up
 *       after {@link #MAX_PENDING_FRAMES} frames so a later DOWN can start a new interaction.</li>
 * </ul>
 */
final class AuraSmallWindowResizeTransition implements Transitions.TransitionHandler {
    /**
     * Frames an owned CHANGE may stay pending before the owner abandons it. At 60Hz this is about
     * two seconds, far beyond a healthy empty-config CHANGE; it exists only so a stuck transition
     * cannot block this task's gestures forever.
     */
    static final int MAX_PENDING_FRAMES = 120;

    private static final String TAG = "AuraResizeTransition";

    interface Hooks {
        /**
         * Lease plus structural baseline check for every frame: the IME lease must still be the
         * valid one taken at DOWN ({@code isInteractionValid}) and parent/rotation/visible/canvas
         * must be unchanged. False means "end and release", never "write the old position back".
         */
        boolean isBaselineCurrent();
        /**
         * Registers the endpoint with the lease BEFORE the final geometry WCT is submitted, because
         * Core may confirm the new TaskInfo synchronously. False forbids the commit.
         */
        boolean onPrepareCommit(int logicalLeft, int logicalTop, int logicalWidth,
                int logicalHeight, float scale);
        /** Include physical-size caption/footer compensation in this exact surface transaction. */
        void onPreview(SurfaceControl.Transaction transaction, float scale);
        /** Completion was dispatched. Reconcile IME after Core/TaskInfo confirms the final state. */
        void onFinished(boolean committed);
    }

    private final Transitions mTransitions;
    private final Choreographer mChoreographer;
    private final AuraSmallWindowGestureSession mSession;
    private final Hooks mHooks;
    private final Choreographer.FrameCallback mFrame = this::renderLatest;
    private IBinder mToken;
    private TransitionInfo.Change mChange;
    private SurfaceControl mLeash;
    private Point mRootOffset;
    private SurfaceControl.Transaction mFrameTransaction;
    private SurfaceControl.Transaction mFinishTransaction;
    private Transitions.TransitionFinishCallback mFinish;
    private int mPendingFrames;
    private boolean mScheduled;
    private boolean mFinished;
    private boolean mBottomHandoff;
    private float mPoseLeft, mPoseTop, mPoseScale;
    private float mReturnLeft, mReturnTop, mReturnScale;
    private long mReturnStartNanos = -1L;
    private long mDismissStartNanos = -1L;
    private float mDismissLeft, mDismissTop, mDismissScale;
    private static final long RETURN_DURATION_NANOS = 160_000_000L;

    AuraSmallWindowResizeTransition(Transitions transitions, Choreographer choreographer,
            AuraSmallWindowGestureSession session, Hooks hooks) {
        if (session == null) {
            throw new IllegalArgumentException("Surface owner requires a gesture session");
        }
        mTransitions = transitions;
        mChoreographer = choreographer;
        mSession = session;
        mHooks = hooks;
    }

    boolean start() {
        if (mToken != null || mFinished) throw new IllegalStateException("Owner already started");
        if (!Transitions.ENABLE_SHELL_TRANSITIONS || !mHooks.isBaselineCurrent()) {
            release(false);
            return false;
        }
        try {
            mToken = mTransitions.startTransition(WindowManager.TRANSIT_CHANGE,
                    new WindowContainerTransaction().beginAuraSmallWindowInteraction(mSession.token),
                    this);
            if (mToken == null) {
                release(false);
                return false;
            }
            mPendingFrames = 0;
            postFrame();
            return true;
        } catch (RuntimeException stale) {
            // The task can leave freeform between DOWN and the synchronous Core call.
            release(false);
            Log.w(TAG, "Interaction start rejected", stale);
            return false;
        }
    }

    void onEvent(int phase, float rawX, float rawY, int pointerId, long timeMillis, int count) {
        if (mFinished) return;
        mSession.accept(phase, rawX, rawY, pointerId, timeMillis, count);
        postFrame();
    }

    void dismissForReplacement() {
        if (mFinished) return;
        mSession.dismissForReplacement();
        postFrame();
    }

    /**
     * Owner-side termination for cross-host interference or lifecycle events: the session becomes
     * terminal now and the frozen pose is restored on the next frame, before or after the CHANGE
     * becomes ready. No geometry is committed.
     */
    void cancelByOwner() {
        if (mFinished) return;
        mSession.cancelByOwner();
        postFrame();
    }

    /**
     * Give up without restoring anything. Used when the task or its surface is already gone, so
     * the owner must not write to the leash any more; Shell keeps handling the CHANGE it was given.
     */
    void abandon() {
        if (mFinished) return;
        Log.w(TAG, "Abandoning interaction after " + mPendingFrames + " pending frames");
        if (mFinish != null) complete(null, false);
        else release(false);
    }

    private void postFrame() {
        if (mScheduled || mFinished) return;
        mScheduled = true;
        mChoreographer.postFrameCallback(mFrame);
    }

    @Override
    public boolean startAnimation(IBinder token, TransitionInfo info,
            SurfaceControl.Transaction start, SurfaceControl.Transaction finish,
            Transitions.TransitionFinishCallback callback) {
        if (!token.equals(mToken) || mFinished || !mHooks.isBaselineCurrent()
                || info.getType() != WindowManager.TRANSIT_CHANGE) {
            release(false);
            return false;
        }
        TransitionInfo.Change target = null;
        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) continue;
            // The marked root+leaf task is the sole participant. Unexpected participants use
            // Shell's ordinary handler; this owner must not hide or finish their surfaces.
            if (change.getTaskInfo() == null || target != null
                    || change.getTaskInfo().taskId != mSession.taskId
                    || !mSession.token.equals(change.getContainer())) {
                release(false);
                return false;
            }
            target = change;
        }
        if (target == null || target.getMode() != WindowManager.TRANSIT_CHANGE
                || target.getStartRotation() != target.getEndRotation()
                || target.getStartDisplayId() != target.getEndDisplayId()
                || !target.getStartAbsBounds().equals(target.getEndAbsBounds())
                || target.getStartAbsBounds().left != mSession.snapshot.visibleRect.left
                || target.getStartAbsBounds().top != mSession.snapshot.visibleRect.top
                || target.getEndAbsBounds().width() != mSession.snapshot.logicalWidth
                || target.getEndAbsBounds().height() != mSession.snapshot.logicalHeight
                || Float.compare(target.getAuraSmallWindowStartScale(), mSession.snapshot.scale) != 0
                || Float.compare(target.getAuraSmallWindowEndScale(), mSession.snapshot.scale) != 0
                || !target.getLeash().isValid()) {
            release(false);
            return false;
        }
        final int root = info.findRootIndex(target.getTaskInfo().displayId);
        if (root < 0) {
            release(false);
            return false;
        }
        mRootOffset = new Point(info.getRoot(root).getOffset());
        mChange = target;
        mLeash = target.getLeash();
        mFinishTransaction = finish;
        mFinish = callback;
        mFrameTransaction = new SurfaceControl.Transaction();
        applyPose(start, mSession.snapshot.visibleRect.left, mSession.snapshot.visibleRect.top,
                mSession.snapshot.scale, false);
        start.apply();
        mPendingFrames = 0;
        postFrame();
        return true;
    }

    private void renderLatest(long frameTimeNanos) {
        mScheduled = false;
        if (mFinished) return;
        if (mFinish == null) {
            // The CHANGE is still pending. The session already holds the newest input.
            if (mPendingFrames++ >= MAX_PENDING_FRAMES) {
                abandon();
                return;
            }
            postFrame();
            return;
        }
        mPendingFrames = 0;
        if (!mLeash.isValid() || !mHooks.isBaselineCurrent()) {
            complete(null, false);
            return;
        }
        final Result result = mSession.latest();
        if (!mSession.region.isCorner()) {
            renderBottom(result, frameTimeNanos);
            return;
        }
        if (result.intent == Intent.PREVIEW_RESIZE) {
            applyPose(mFrameTransaction, result.visibleRect.left, result.visibleRect.top,
                    result.scale, false);
            mFrameTransaction.apply();
            mSession.onPreviewApplied(result);
        } else if (result.intent == Intent.COMMIT_RESIZE) {
            if (mSession.isMiniCommit()) {
                settleMini(result, frameTimeNanos);
                return;
            }
            applyPose(mFrameTransaction, result.visibleRect.left, result.visibleRect.top,
                    result.scale, false);
            mFrameTransaction.apply();
            commitNow(result);
        } else if (mSession.isTerminal()) {
            restoreNow();
        }
    }

    /** Uniform task-buffer preview, not a relayout. Travel stays continuous past the commit
     * threshold; that threshold decides UP only. The bar and caption share this transaction. */
    private void renderBottom(Result result, long frameTimeNanos) {
        if (result.intent == Intent.PREVIEW_DISMISS
                || result.intent == Intent.PREVIEW_FULLSCREEN) {
            final float dy = mSession.dragDeltaY();
            final float originalScale = mSession.snapshot.scale;
            final float height = mSession.snapshot.logicalHeight * originalScale;
            final float maxScale = Math.max(originalScale, Math.min(1f, Math.min(
                    mSession.snapshot.safeRect.width() / (float) mSession.snapshot.logicalWidth,
                    mSession.snapshot.safeRect.height() / (float) mSession.snapshot.logicalHeight)));
            final boolean dismiss = result.intent == Intent.PREVIEW_DISMISS;
            final float travel = dismiss ? Math.min(0f, dy) : Math.max(0f, dy);
            final float scale = Math.max(originalScale * .5f, Math.min(maxScale,
                    originalScale * (1f + .85f * travel / height)));
            final float width = mSession.snapshot.logicalWidth * scale;
            final float newHeight = mSession.snapshot.logicalHeight * scale;
            final float center = (mSession.snapshot.visibleRect.left
                    + mSession.snapshot.visibleRect.right) / 2f;
            final float left = Math.max(mSession.snapshot.safeRect.left, Math.min(
                    mSession.snapshot.safeRect.right - width, center - width / 2f));
            final float top = Math.max(mSession.snapshot.safeRect.top, Math.min(
                    mSession.snapshot.safeRect.bottom - newHeight,
                    mSession.snapshot.visibleRect.top + travel * .15f));
            applyPose(mFrameTransaction, left, top, scale, false);
            mFrameTransaction.apply();
        } else if (result.intent == Intent.COMMIT_DISMISS) {
            settleDismiss(frameTimeNanos);
        } else if (result.intent == Intent.COMMIT_FULLSCREEN) {
            // The mode transition will obtain newly sized buffers; this CHANGE must not commit
            // a guessed fullscreen geometry into the old fixed logical canvas.
            AuraSmallWindowTransitionAnimation.registerGestureStart(mChange.getTaskInfo(),
                    mPoseLeft, mPoseTop, mSession.snapshot.logicalWidth * mPoseScale,
                    mSession.snapshot.logicalHeight * mPoseScale,
                    result.intent == Intent.COMMIT_DISMISS);
            mBottomHandoff = true;
            // Keep the last finger pose until the ready close/fullscreen handler takes over.
            // Resetting to the DOWN snapshot here creates the visible pop at UP.
            applyPose(mFinishTransaction, mPoseLeft, mPoseTop, mPoseScale, true);
            complete(null, true);
        } else if (mSession.isTerminal()) {
            returnBottom(frameTimeNanos);
        }
    }

    /** Finish on the held, unchanged app buffer. Relayout to fullscreen happens only after
     * the floating surface has disappeared, so entry path and app redraw speed cannot interrupt
     * the exit. The subsequent retained-task transition only changes Core state. */
    private void settleDismiss(long frameTimeNanos) {
        if (mDismissStartNanos < 0L) {
            mDismissStartNanos = frameTimeNanos;
            mDismissLeft = mPoseLeft;
            mDismissTop = mPoseTop;
            mDismissScale = mPoseScale;
        }
        final float progress = Math.min(1f,
                (frameTimeNanos - mDismissStartNanos) / 350_000_000f);
        final float geometry = AuraSmallWindowTransitionAnimation.criticalResponse(progress, .35f);
        final float alpha = AuraSmallWindowTransitionAnimation.criticalResponse(progress, .30f);
        final float scale = mDismissScale * (1f - .7f * geometry);
        final float left = mDismissLeft
                + mSession.snapshot.logicalWidth * (mDismissScale - scale) / 2f;
        final float top = mDismissTop
                + mSession.snapshot.logicalHeight * (mDismissScale - scale) / 2f;
        applyPose(mFrameTransaction, left, top, scale, false);
        mFrameTransaction.setAlpha(mLeash, 1f - alpha).apply();
        if (progress < 1f) {
            postFrame();
            return;
        }
        AuraSmallWindowTransitionAnimation.registerSettledDismiss(mChange.getTaskInfo());
        mBottomHandoff = true;
        applyPose(mFinishTransaction, left, top, scale, true);
        mFinishTransaction.hide(mLeash).setAlpha(mLeash, 1f);
        Log.i(TAG, "Dismiss settled on held buffer task=" + mSession.taskId);
        complete(null, true);
    }

    /** Shrink and snap the same canvas; Core state commits when the dock is reached. */
    private void settleMini(Result result, long frameTimeNanos) {
        if (mReturnStartNanos < 0L) {
            mReturnStartNanos = frameTimeNanos;
            mReturnLeft = mPoseLeft; mReturnTop = mPoseTop; mReturnScale = mPoseScale;
        }
        final float progress = Math.min(1f,
                (frameTimeNanos - mReturnStartNanos) / 300_000_000f);
        final float eased = 1f - (float) Math.pow(1f - progress, 3);
        applyPose(mFrameTransaction,
                mReturnLeft + (result.visibleRect.left - mReturnLeft) * eased,
                mReturnTop + (result.visibleRect.top - mReturnTop) * eased,
                mReturnScale + (result.scale - mReturnScale) * eased, false);
        final Rect miniCrop = AuraSmallWindowViewport.miniCrop(mSession.snapshot.logicalWidth,
                mSession.snapshot.logicalHeight, mSession.snapshot.density, mSession.snapshot.scale);
        mFrameTransaction.setWindowCrop(mLeash, new Rect(0, Math.round(miniCrop.top * eased),
                miniCrop.right, mSession.snapshot.logicalHeight - Math.round(
                        (mSession.snapshot.logicalHeight - miniCrop.bottom) * eased)));
        mFrameTransaction.apply();
        if (progress >= 1f) commitNow(result);
        else postFrame();
    }

    private void returnBottom(long frameTimeNanos) {
        if (mReturnStartNanos < 0L) {
            mReturnStartNanos = frameTimeNanos;
            mReturnLeft = mPoseLeft;
            mReturnTop = mPoseTop;
            mReturnScale = mPoseScale;
        }
        final float progress = Math.min(1f,
                (frameTimeNanos - mReturnStartNanos) / (float) RETURN_DURATION_NANOS);
        final float eased = 1f - (float) Math.pow(1f - progress, 3);
        applyPose(mFrameTransaction,
                mReturnLeft + (mSession.snapshot.visibleRect.left - mReturnLeft) * eased,
                mReturnTop + (mSession.snapshot.visibleRect.top - mReturnTop) * eased,
                mReturnScale + (mSession.snapshot.scale - mReturnScale) * eased, false);
        mFrameTransaction.apply();
        if (progress >= 1f || Math.abs(mReturnScale - mSession.snapshot.scale) < .00001f) {
            applyPose(mFinishTransaction, mSession.snapshot.visibleRect.left,
                    mSession.snapshot.visibleRect.top, mSession.snapshot.scale, true);
            complete(null, false);
        } else {
            postFrame();
        }
    }

    /**
     * Submits the final geometry in the order the IME interaction contract requires: register the
     * endpoint on the lease first, then the matching surface finishT pose and the Core geometry WCT
     * together. A refused preparation or a rejected geometry restores the frozen pose when the
     * lease is still valid, and never emits an illegal size.
     */
    private void commitNow(Result result) {
        final Rect logical = mSession.committedLogicalBounds();
        final boolean prepared = logical != null && mHooks.onPrepareCommit(logical.left,
                logical.top, logical.width(), logical.height(), result.scale);
        WindowContainerTransaction commit = null;
        if (prepared) {
            try {
                commit = mSession.resizeCommit();
            } catch (RuntimeException rejected) {
                Log.w(TAG, "Final geometry rejected before submission", rejected);
                commit = null;
            }
        }
        if (commit == null) {
            if (mHooks.isBaselineCurrent()) {
                // Valid lease: the owner still owns the leash, so put it back on the snapshot.
                restoreNow();
            } else {
                // A system transition owns the leash now; only end and release.
                complete(null, false);
            }
            return;
        }
        // Shell applies finishT BEFORE Core applies this WCT. Rewrite the final pose in
        // original parent coordinates, or finishT briefly restores the old scale/position.
        applyPose(mFinishTransaction, result.visibleRect.left, result.visibleRect.top,
                result.scale, true);
        if (mSession.isMiniCommit()) {
            mFinishTransaction.setWindowCrop(mLeash, AuraSmallWindowViewport.miniCrop(
                    mSession.snapshot.logicalWidth, mSession.snapshot.logicalHeight,
                    mSession.snapshot.density, mSession.snapshot.scale));
        }
        complete(commit, true);
    }

    /**
     * Terminal cancel: put the surface back on the frozen pose immediately, in one frame
     * transaction, and complete without a geometry WCT.
     */
    private void restoreNow() {
        applyPose(mFrameTransaction, mSession.snapshot.visibleRect.left,
                mSession.snapshot.visibleRect.top, mSession.snapshot.scale, false);
        mFrameTransaction.apply();
        complete(null, false);
    }

    private void applyPose(SurfaceControl.Transaction transaction, float left, float top,
            float scale, boolean finalParent) {
        final float x;
        final float y;
        if (finalParent) {
            final Point origin = mChange.getEndRelOffset();
            x = origin.x + left - mSession.snapshot.visibleRect.left;
            y = origin.y + top - mSession.snapshot.visibleRect.top;
        } else {
            x = left - mRootOffset.x;
            y = top - mRootOffset.y;
        }
        if (!finalParent) {
            mPoseLeft = left;
            mPoseTop = top;
            mPoseScale = scale;
        }
        transaction.setPosition(mLeash, x, y).setMatrix(mLeash, scale, 0f, 0f, scale)
                .setAlpha(mLeash, 1f)
                .setWindowCrop(mLeash, mSession.snapshot.logicalWidth, mSession.snapshot.logicalHeight)
                .setCornerRadius(mLeash, 24f * mSession.snapshot.density / scale);
        if (mChange.getTaskInfo().auraSmallWindowMini) {
            // Exit a mini on its existing crop; never flash the hidden top/footer back into view.
            transaction.setWindowCrop(mLeash, AuraSmallWindowViewport.crop(mChange.getTaskInfo()));
        }
        mHooks.onPreview(transaction, scale);
    }

    @Override
    public void mergeAnimation(IBinder token, TransitionInfo info,
            SurfaceControl.Transaction start, SurfaceControl.Transaction finish,
            IBinder mergeTarget, Transitions.TransitionFinishCallback callback) {
        // Another transition supersedes the interaction. Release it without a geometry WCT;
        // do not claim the incoming transition, which Shell can then handle normally.
        if (mergeTarget.equals(mToken)) complete(null, false);
    }

    @Override
    public void onTransitionConsumed(IBinder token, boolean aborted,
            SurfaceControl.Transaction finish) {
        if (token.equals(mToken)) release(false);
    }

    @Override
    public WindowContainerTransaction handleRequest(IBinder token, TransitionRequestInfo request) {
        return null;
    }

    private void complete(WindowContainerTransaction commit, boolean committed) {
        if (mFinished) return;
        final Transitions.TransitionFinishCallback callback = mFinish;
        cleanup();
        boolean submitted = false;
        try {
            if (callback != null) callback.onTransitionFinished(commit);
            submitted = committed;
        } catch (RuntimeException rejected) {
            Log.w(TAG, "Interaction finish rejected", rejected);
        } finally {
            if (mBottomHandoff && !submitted) {
                AuraSmallWindowTransitionAnimation.clearGestureStart(mSession.token);
            }
            mHooks.onFinished(submitted);
        }
    }

    private void release(boolean committed) {
        if (mFinished) return;
        cleanup();
        mHooks.onFinished(committed);
    }

    private void cleanup() {
        mFinished = true;
        mChoreographer.removeFrameCallback(mFrame);
        if (mFrameTransaction != null) mFrameTransaction.close();
    }
}
