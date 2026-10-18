/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceControl;

import com.android.wm.shell.freeform.AuraSmallWindowImeController;
import com.android.wm.shell.freeform.AuraSmallWindowImeController.InteractionSnapshot;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.aura.AuraSmallWindowBottomBarView;
import com.android.wm.shell.windowdecor.aura.AuraWindowGestureRecognizer;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Phase;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Main-thread coordinator for the Aura small-window bottom bar and both resize corners.
 *
 * <p>One instance serves one task and is the single interaction owner for it. It accepts the raw
 * events of the three bottom hosts through {@link AuraSmallWindowBottomDecoration.Listener},
 * decides with the frozen pure-Java protocol what the user asked for, and asks the Codex-owned
 * ports to write the preview transaction and to run the task-level actions. It never touches a
 * leash itself, never removes a task itself, never changes a windowing mode and never animates on
 * a timer.
 *
 * <p>Ownership is the IME interaction lease of
 * {@link AuraSmallWindowImeController}, which Codex already implemented:
 * <ul>
 *   <li>Every {@code DOWN} first calls {@link AuraSmallWindowImeController#tryAcquireInteraction}.
 *       A null lease means "not now" (keyboard animation, pending Core confirmation, recents or
 *       another interaction): this controller refuses the gesture outright and the next
 *       {@code DOWN} may retry. It never calls the old {@code onDragStateChanged(true)} to force
 *       an in-flight IME animation aside.</li>
 *   <li>The frozen operation snapshot is built from {@code logicalBounds()}, {@code safeBounds()},
 *       {@code scale} and {@code density} of that lease. The keyboard-aware safe bounds therefore
 *       come from Core, not from a stable {@code DisplayLayout} guess.</li>
 *   <li>{@link AuraSmallWindowImeController#isInteractionValid} is checked on every preview frame
 *       (through the owner's baseline hook) and again by
 *       {@link AuraSmallWindowImeController#prepareInteractionCommit} before the final geometry
 *       WCT. A lease that went invalid is never followed by a "restore the old position" write,
 *       because a system transition owns that leash by then: the interaction just ends and
 *       releases.</li>
 *   <li>The lease is released exactly once, with the committed logical bounds for a successful
 *       corner commit and with {@code (null, 0)} for cancels and every bottom-bar ending. A bottom
 *       bar close/fullscreen releases the lease <em>before</em> the task action, so no mode
 *       transition is started while this CHANGE is still previewing.</li>
 * </ul>
 *
 * <p>Interference handling: a second pointer on any host, a second {@code DOWN}, a foreign pointer
 * id, a lost pointer or an externally observed pointer (passive display monitor, see
 * {@link #onExternalPointerDown}) terminates the interaction and restores the frozen pose when the
 * lease is still valid; the terminating {@code DOWN} never restarts a snapshot.
 */
public final class AuraSmallWindowInteractionController
        implements AuraSmallWindowBottomDecoration.Listener {

    private static final String TAG = "AuraInteraction";

    /** Why the current interaction ended, or why the last DOWN did not start one. */
    public enum Termination {
        /** No interaction has ended or been refused yet. */
        NONE,
        /** A commit was submitted (dismiss, fullscreen or a resize geometry). */
        COMMITTED,
        /** Tap, below threshold, explicit cancel or an owner-side cancel. */
        CANCELLED,
        /** A second {@code DOWN} arrived while this interaction was live. */
        SECOND_DOWN,
        /** A pointer moved on a host (or region) that does not own this gesture. */
        FOREIGN_HOST,
        /** A foreign pointer id arrived on the owning host. */
        FOREIGN_POINTER,
        /** An event reported more than one pointer. */
        MULTI_POINTER,
        /** An event reported zero pointers, i.e. the active pointer disappeared. */
        POINTER_LOST,
        /** The passive display monitor reported another pointer down. */
        EXTERNAL_POINTER,
        /** The task stopped being the frozen baseline (scale/bounds/visibility changed). */
        BASELINE_LOST,
        /** The task vanished or the decoration was released. */
        TASK_GONE,
        /** The owned CHANGE never became ready and the watchdog gave up. */
        OWNER_ABANDONED,
        /** The IME interaction lease was not available; the DOWN was refused and may be retried. */
        LEASE_UNAVAILABLE,
        /** The frozen snapshot or the gesture session refused this DOWN; the lease was released. */
        SESSION_REJECTED
    }

    /**
     * Everything the coordinator needs from its Codex-owned host beyond the IME lease.
     * Implementations are per task and must run on the main thread.
     */
    public interface Ports {
        /** Live task snapshot of this controller's task, or null when it is gone. */
        RunningTaskInfo taskInfo();

        /** Physical touch slop in pixels for the task's display (ViewConfiguration). */
        float touchSlopPx(int displayId);

        /**
         * Structural baseline check that the IME lease cannot cover: same parent, rotation and
         * committed canvas as at {@code DOWN}. The controller already requires
         * {@link AuraSmallWindowImeController#isInteractionValid} on top of this.
         */
        boolean isBaselineCurrent(RunningTaskInfo info, AuraSmallWindowGestureSession session);

        /**
         * Append the decoration chrome for this preview pose to the <em>same</em> surface
         * transaction as the task pose. Never relayout a ViewHost here.
         */
        void applyChromePreview(SurfaceControl.Transaction transaction, RunningTaskInfo info,
                AuraSmallWindowGestureSession session, float scale);

        /** Exit the floating window and retain its application task in the background. */
        void requestDismiss(RunningTaskInfo info);

        /** Bring this task to normal fullscreen (existing windowing-mode transition). */
        void requestFullscreen(RunningTaskInfo info);

        /** Bottom bar activation feedback; native surface owner supplies the follow-finger pose. */
        void onBottomPreview(RunningTaskInfo info, boolean previewing);
        default void onInteractionFinished() {}
    }

    /** Threshold/limit factory, called once per interaction with the frozen density. */
    public interface Tuning {
        AuraWindowGestureRecognizer.Config recognizer(float density, float touchSlopPx);

        AuraWindowResizeGeometry.Config geometry(float density);

        /** Plan defaults: 48dp/800dp/s/16dp thresholds and the 220dp minimum width. */
        Tuning DEFAULT = new Tuning() {
            @Override
            public AuraWindowGestureRecognizer.Config recognizer(float density,
                    float touchSlopPx) {
                return AuraWindowGestureRecognizer.Config.defaults(density, touchSlopPx);
            }

            @Override
            public AuraWindowResizeGeometry.Config geometry(float density) {
                return AuraWindowResizeGeometry.Config.forDensity(density);
            }
        };
    }

    private final Ports mPorts;
    private final AuraSmallWindowImeController mIme;
    private final Transitions mTransitions;
    private final Choreographer mChoreographer;
    private final Tuning mTuning;
    private final Set<Integer> mExternalPointers = new HashSet<>();

    private AuraSmallWindowGestureSession mSession;
    private AuraSmallWindowResizeTransition mTransition;
    private InteractionSnapshot mLease;
    private RunningTaskInfo mInfo;
    private Region mRegion;
    private int mActivePointerId = -1;
    private boolean mPreviewing;
    private Termination mLastTermination = Termination.NONE;
    private int mRefusedDowns;

    public AuraSmallWindowInteractionController(Ports ports, AuraSmallWindowImeController ime,
            Transitions transitions, Choreographer choreographer, Tuning tuning) {
        if (ports == null || ime == null || transitions == null || choreographer == null
                || tuning == null) {
            throw new IllegalArgumentException("Ports, IME controller, Transitions, Choreographer "
                    + "and Tuning are required");
        }
        mPorts = ports;
        mIme = ime;
        mTransitions = transitions;
        mChoreographer = choreographer;
        mTuning = tuning;
    }

    public AuraSmallWindowInteractionController(Ports ports, AuraSmallWindowImeController ime,
            Transitions transitions, Choreographer choreographer) {
        this(ports, ime, transitions, choreographer, Tuning.DEFAULT);
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onEvent(int rawRegion, int rawPhase, float rawX, float rawY, int pointerId,
            long eventTimeMillis, int pointerCount) {
        final Region region = toRegion(rawRegion);
        if (region == null) {
            Log.w(TAG, "Ignoring event for unknown region " + rawRegion);
            return;
        }
        if (mSession != null) {
            onEventDuringInteraction(region, rawPhase, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (rawPhase != AuraSmallWindowBottomBarView.PHASE_DOWN) {
            // No interaction owns this event: late/duplicate MOVE, UP or CANCEL.
            return;
        }
        beginInteraction(region, rawX, rawY, pointerId, eventTimeMillis, pointerCount);
    }

    @Override
    public boolean onAccessibilityAction(int actionId) {
        if (mSession != null || mTransition != null) {
            return false; // Never fight a live interaction; the caller may retry later.
        }
        final RunningTaskInfo info = mPorts.taskInfo();
        if (info == null) {
            return false;
        }
        final boolean dismiss = actionId == AuraSmallWindowBottomBarView.ACTION_ID_CLOSE;
        final boolean fullscreen = actionId == AuraSmallWindowBottomBarView.ACTION_ID_FULLSCREEN;
        if (!dismiss && !fullscreen) {
            return false;
        }
        // The alternative entry runs through the same lease, so it cannot act while the keyboard
        // is animating or while another interaction owns the task.
        final InteractionSnapshot lease = mIme.tryAcquireInteraction(info);
        if (lease == null) {
            mLastTermination = Termination.LEASE_UNAVAILABLE;
            mRefusedDowns++;
            return false;
        }
        mIme.releaseInteraction(lease, null, 0f);
        mLastTermination = Termination.COMMITTED;
        if (dismiss) {
            mPorts.requestDismiss(info);
        } else {
            mPorts.requestFullscreen(info);
        }
        return true;
    }

    private void onEventDuringInteraction(Region region, int rawPhase, float rawX, float rawY,
            int pointerId, long eventTimeMillis, int pointerCount) {
        if (rawPhase == AuraSmallWindowBottomBarView.PHASE_DOWN) {
            // Split touch: another host's finger went down. Terminate and never restart a snapshot
            // from this DOWN.
            terminate(Termination.SECOND_DOWN, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (region != mRegion) {
            terminate(Termination.FOREIGN_HOST, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (pointerCount > 1) {
            terminate(Termination.MULTI_POINTER, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (pointerCount < 1) {
            terminate(Termination.POINTER_LOST, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (pointerId != mActivePointerId) {
            terminate(Termination.FOREIGN_POINTER, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        if (!mExternalPointers.isEmpty()) {
            terminate(Termination.EXTERNAL_POINTER, rawX, rawY, pointerId, eventTimeMillis,
                    pointerCount);
            return;
        }
        deliver(rawPhase, rawX, rawY, pointerId, eventTimeMillis, pointerCount);
    }

    // ---------------------------------------------------------------- session

    private void beginInteraction(Region region, float rawX, float rawY, int pointerId,
            long eventTimeMillis, int pointerCount) {
        if (mTransition != null) {
            // Defensive: a live session always owns its transition, so this cannot normally be
            // reached (a DOWN during a live session is handled as SECOND_DOWN interference). Kept
            // so a future refactor can never end up with two interaction owners on one task.
            Log.w(TAG, "Refusing DOWN: a previous interaction owner is still registered");
            mLastTermination = Termination.LEASE_UNAVAILABLE;
            mRefusedDowns++;
            return;
        }
        final RunningTaskInfo info = mPorts.taskInfo();
        if (info == null) {
            return;
        }
        if (!mExternalPointers.isEmpty()) {
            // The passive observer already knows another finger is down elsewhere, so this DOWN
            // cannot be the single-pointer gesture the protocol requires.
            Log.w(TAG, "Refusing DOWN: another pointer is already down");
            mLastTermination = Termination.EXTERNAL_POINTER;
            mRefusedDowns++;
            return;
        }
        // One settled lease per gesture. Null means retry on the next DOWN; never force the
        // keyboard animation aside to take the leash.
        final InteractionSnapshot lease = mIme.tryAcquireInteraction(info);
        if (lease == null) {
            Log.w(TAG, "Refusing DOWN: no IME interaction lease for " + region);
            mLastTermination = Termination.LEASE_UNAVAILABLE;
            mRefusedDowns++;
            return;
        }
        final Snapshot snapshot = toProtocolSnapshot(info, lease);
        if (snapshot == null) {
            Log.w(TAG, "Refusing DOWN: unusable lease snapshot for " + region);
            mIme.releaseInteraction(lease, null, 0f);
            mLastTermination = Termination.SESSION_REJECTED;
            mRefusedDowns++;
            return;
        }
        final float touchSlop = mPorts.touchSlopPx(info.displayId);
        final AuraSmallWindowGestureSession session = AuraSmallWindowGestureSession.begin(info,
                region, snapshot, mTuning.recognizer(snapshot.density, touchSlop),
                mTuning.geometry(snapshot.density),
                new PointerEvent(Phase.DOWN, eventTimeMillis, pointerId, pointerCount, rawX,
                        rawY));
        if (session == null) {
            // Not an Aura small window any more, inconsistent snapshot, or an illegal/absent
            // resize interval: release the lease and leave no state behind.
            Log.w(TAG, "Refusing DOWN: session rejected for " + region);
            mIme.releaseInteraction(lease, null, 0f);
            mLastTermination = Termination.SESSION_REJECTED;
            mRefusedDowns++;
            return;
        }
        mSession = session;
        mInfo = info;
        mRegion = region;
        mActivePointerId = pointerId;
        mLease = lease;
        mLastTermination = Termination.NONE;
        final AuraSmallWindowResizeTransition transition = new AuraSmallWindowResizeTransition(
                mTransitions, mChoreographer, session, new SessionHooks(info, session, lease));
        mTransition = transition;
        if (!transition.start()) {
            Log.w(TAG, "Surface interaction owner could not start; gesture refused");
            if (mSession == session) {
                finishInteraction(session, false);
            }
        }
    }

    private java.util.function.Consumer<Boolean> mReplacementCompletion;

    /** Shell-main only. Reuse the held-buffer exit, retaining the app in the background. */
    boolean dismissForReplacement(java.util.function.Consumer<Boolean> completion) {
        if (mSession != null || mTransition != null || !mExternalPointers.isEmpty()) return false;
        final RunningTaskInfo info = mPorts.taskInfo();
        if (info == null) return false;
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        beginInteraction(Region.BOTTOM, bounds.left + bounds.width() * info.auraSmallWindowScale / 2f,
                bounds.top + bounds.height() * info.auraSmallWindowScale, 0, 0L, 1);
        if (mSession == null || mTransition == null) return false;
        mReplacementCompletion = completion;
        mTransition.dismissForReplacement();
        return true;
    }

    private void deliver(int rawPhase, float rawX, float rawY, int pointerId,
            long eventTimeMillis, int pointerCount) {
        final AuraSmallWindowGestureSession session = mSession;
        if (!mIme.isInteractionValid(mLease) || !mPorts.isBaselineCurrent(mInfo, session)) {
            if (mTransition != null) mTransition.abandon();
            else finishInteraction(session, false);
            mLastTermination = Termination.BASELINE_LOST;
            return;
        }
        if (mTransition != null) {
            mTransition.onEvent(rawPhase, rawX, rawY, pointerId, eventTimeMillis, pointerCount);
            // The transition completes the interaction through Hooks.onFinished.
            return;
        }
        final Result result = session.accept(rawPhase, rawX, rawY, pointerId, eventTimeMillis,
                pointerCount);
        handleBottomResult(session, result);
    }

    private void handleBottomResult(AuraSmallWindowGestureSession session, Result result) {
        final boolean previewing = result.intent.isPreview();
        if (previewing != mPreviewing) {
            mPreviewing = previewing;
            mPorts.onBottomPreview(mInfo, previewing);
        }
        if (result.intent == Intent.COMMIT_DISMISS || result.intent == Intent.COMMIT_FULLSCREEN) {
            final boolean dismiss = result.intent == Intent.COMMIT_DISMISS;
            final RunningTaskInfo info = mInfo;
            // Protocol from the IME contract: leave the interaction and release the lease first,
            // then run the normal task action. No mode transition starts while this interaction
            // could still be previewing.
            finishInteraction(session, false);
            mLastTermination = Termination.COMMITTED;
            if (dismiss) {
                mPorts.requestDismiss(info);
            } else {
                mPorts.requestFullscreen(info);
            }
        } else if (!result.active) {
            mLastTermination = Termination.CANCELLED;
            finishInteraction(session, false);
        }
    }

    // ---------------------------------------------------------------- termination

    /**
     * Ends the current interaction because something interfered or the baseline went away. A
     * corner interaction with a still-valid lease restores the frozen pose immediately and
     * completes without any geometry commit; an invalid lease only ends and releases, because a
     * system transition owns that leash by then.
     */
    private void terminate(Termination reason, float rawX, float rawY, int pointerId,
            long eventTimeMillis, int pointerCount) {
        final AuraSmallWindowGestureSession session = mSession;
        if (session == null) {
            return;
        }
        mLastTermination = reason;
        if (mTransition != null) {
            // Owner-side cancel: the session becomes terminal now and the next frame restores the
            // frozen pose. Hooks.onFinished then finishes the interaction exactly once.
            mTransition.cancelByOwner();
            return;
        }
        finishInteraction(session, false);
    }

    /** Called by the transition hooks (and by the refusal paths) exactly once per interaction. */
    private void finishInteraction(AuraSmallWindowGestureSession session, boolean committed) {
        finishInteraction(session, committed, true);
    }

    private void finishInteraction(AuraSmallWindowGestureSession session, boolean committed,
            boolean rebind) {
        if (mSession != session) {
            return; // A stale owner finishing after a newer interaction started.
        }
        final RunningTaskInfo info = mInfo;
        final InteractionSnapshot lease = mLease;
        mSession = null;
        mTransition = null;
        mInfo = null;
        mRegion = null;
        mActivePointerId = -1;
        mLease = null;
        if (mPreviewing) {
            mPreviewing = false;
            if (info != null && !session.region.isCorner()) {
                mPorts.onBottomPreview(info, false);
            }
        }
        boolean released = false;
        if (committed && session.region.isCorner()) {
            final Rect logical = session.committedLogicalBounds();
            if (lease != null && logical != null) {
                mIme.releaseInteraction(lease, logical, session.latest().scale);
                released = true;
            }
        }
        // Cancels and every bottom-bar ending need no geometry endpoint.
        if (!released && lease != null) mIme.releaseInteraction(lease, null, 0f);
        if (rebind) mPorts.onInteractionFinished();
    }

    /** Mini host consumes the double tap; continue from the actual miniature visible pose. */
    void fullscreenMini() {
        final RunningTaskInfo info = mPorts.taskInfo();
        if (info == null || !info.auraSmallWindowMini || mSession != null) return;
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        final Rect crop = AuraSmallWindowViewport.crop(info);
        com.android.wm.shell.freeform.AuraSmallWindowTransitionAnimation.registerGestureStart(info,
                bounds.left, bounds.top + crop.top * info.auraSmallWindowScale,
                crop.width() * info.auraSmallWindowScale,
                crop.height() * info.auraSmallWindowScale, false);
        mPorts.requestFullscreen(info);
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Entry point for a passive, non-pilfering display pointer observer (see
     * DEEPSEEK_SMALL_WINDOW_V2_INTEGRATION_RESULT.md for the required lifecycle). Any externally
     * observed pointer down cancels the interaction, including a second finger that landed on the
     * application content or outside the window.
     */
    public void onExternalPointerDown(int pointerId) {
        if (pointerId < 0) {
            return;
        }
        mExternalPointers.add(pointerId);
        if (mSession != null) {
            terminate(Termination.EXTERNAL_POINTER, 0f, 0f, mActivePointerId, 0L, 1);
        }
    }

    /** The externally observed pointer went up; the interaction may start again afterwards. */
    public void onExternalPointerUp(int pointerId) {
        mExternalPointers.remove(pointerId);
    }

    /** The external observer lost track (channel closed, display changed): clear the ledger. */
    public void onExternalPointersLost() {
        mExternalPointers.clear();
    }

    /**
     * The task's TaskInfo changed. Only hard invalidation terminates an interaction; ordinary
     * geometry drift is left to the IME lease, which the owner checks before every frame.
     */
    public void onTaskInfoChanged(RunningTaskInfo info) {
        if (mSession == null) {
            return;
        }
        if (info == null || info.token == null || !info.token.equals(mSession.token)
                || !CaptionWindowDecoration.isAuraSmallWindowTask(info) || !info.isVisible
                || !mIme.isInteractionValid(mLease)
                || !mPorts.isBaselineCurrent(mInfo, mSession)) {
            terminate(Termination.BASELINE_LOST, 0f, 0f, mActivePointerId, 0L, 1);
        }
    }

    /** The task vanished or the decoration is being released: never write a dead surface again. */
    public void onTaskVanished() {
        mLastTermination = Termination.TASK_GONE;
        final AuraSmallWindowResizeTransition transition = mTransition;
        if (transition != null) {
            transition.abandon();
        }
        final AuraSmallWindowGestureSession session = mSession;
        if (session != null) {
            finishInteraction(session, false);
        }
        mExternalPointers.clear();
    }

    public boolean isInteractionActive() {
        return mSession != null;
    }

    /** True while an owned CHANGE has not finished yet: new gestures are refused meanwhile. */
    public boolean isSettling() {
        return mTransition != null;
    }

    public Termination lastTermination() {
        return mLastTermination;
    }

    /** How many DOWNs were refused since construction; diagnostics for the release report. */
    public int refusedDownCount() {
        return mRefusedDowns;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "AuraSmallWindowInteractionController"
                + " active=" + (mSession != null)
                + " settling=" + (mTransition != null)
                + " region=" + mRegion
                + " pointerId=" + mActivePointerId
                + " leaseHeld=" + (mLease != null)
                + " externalPointers=" + mExternalPointers.size()
                + " refusedDowns=" + mRefusedDowns
                + " lastTermination=" + mLastTermination);
    }

    // ---------------------------------------------------------------- helpers

    private final class SessionHooks implements AuraSmallWindowResizeTransition.Hooks {
        private final RunningTaskInfo mHookInfo;
        private final AuraSmallWindowGestureSession mHookSession;
        private final InteractionSnapshot mHookLease;

        SessionHooks(RunningTaskInfo info, AuraSmallWindowGestureSession session,
                InteractionSnapshot lease) {
            mHookInfo = info;
            mHookSession = session;
            mHookLease = lease;
        }

        @Override
        public boolean isBaselineCurrent() {
            return mIme.isInteractionValid(mHookLease)
                    && mPorts.isBaselineCurrent(mHookInfo, mHookSession);
        }

        @Override
        public boolean onPrepareCommit(int logicalLeft, int logicalTop, int logicalWidth,
                int logicalHeight, float scale) {
            if (logicalWidth <= 0 || logicalHeight <= 0) {
                return false;
            }
            return mIme.prepareInteractionCommit(mHookLease,
                    new Rect(logicalLeft, logicalTop, logicalLeft + logicalWidth,
                            logicalTop + logicalHeight), scale);
        }

        @Override
        public void onPreview(SurfaceControl.Transaction transaction, float scale) {
            mPorts.applyChromePreview(transaction, mHookInfo, mHookSession, scale);
        }

        @Override
        public void onFinished(boolean committed) {
            final java.util.function.Consumer<Boolean> replacement = mReplacementCompletion;
            mReplacementCompletion = null;
            final Intent intent = mHookSession.latest().intent;
            final boolean bottomCommit = committed && !mHookSession.region.isCorner()
                    && (intent == Intent.COMMIT_DISMISS || intent == Intent.COMMIT_FULLSCREEN)
                    && isBaselineCurrent();
            // End the held CHANGE and release the IME lease before the mode/close transition.
            finishInteraction(mHookSession, committed && mHookSession.region.isCorner(),
                    !bottomCommit);
            if (committed && !mHookSession.region.isCorner() && !bottomCommit) {
                com.android.wm.shell.freeform.AuraSmallWindowTransitionAnimation
                        .clearGestureStart(mHookInfo.token);
            }
            if (bottomCommit) {
                mLastTermination = Termination.COMMITTED;
                if (intent == Intent.COMMIT_DISMISS) mPorts.requestDismiss(mHookInfo);
                else mPorts.requestFullscreen(mHookInfo);
            }
            if (replacement != null) replacement.accept(bottomCommit && intent == Intent.COMMIT_DISMISS);
        }
    }

    /**
     * Turns a lease into the frozen protocol snapshot. The visible rect keeps the real confirmed
     * screen position from {@code logicalBounds()} and derives its size from the fixed logical
     * canvas and the actual scale; the safe rect is the Core-provided keyboard-aware area and is
     * never replaced by a stable display guess.
     *
     * <p>The fullscreen target is set to the same safe rect. This round the bottom bar neither
     * follows the finger nor starts the mode transition itself (Codex does that), so that field is
     * carried for the frozen contract only.
     */
    private static Snapshot toProtocolSnapshot(RunningTaskInfo info, InteractionSnapshot lease) {
        if (lease.taskId != info.taskId || lease.token == null || info.token == null
                || !lease.token.equals(info.token)) return null;
        final Rect logical = lease.logicalBounds();
        final Rect safe = lease.safeBounds();
        if (logical == null || safe == null || logical.isEmpty() || safe.isEmpty()) return null;
        if (!Float.isFinite(lease.scale) || lease.scale <= 0f || lease.scale >= 1f) return null;
        if (!Float.isFinite(lease.density) || lease.density <= 0f) return null;
        final int width = Math.round(logical.width() * lease.scale);
        final int height = Math.round(logical.height() * lease.scale);
        if (width <= 0 || height <= 0) return null;
        final float minScale = AuraWindowInteraction.DEFAULT_MIN_PHYSICAL_WIDTH_DP
                * lease.density / logical.width();
        return new Snapshot(
                new AuraWindowInteraction.Rect(logical.left, logical.top, logical.left + width,
                        logical.top + height),
                logical.width(), logical.height(), lease.scale, lease.density,
                new AuraWindowInteraction.Rect(safe.left, safe.top, safe.right, safe.bottom),
                new AuraWindowInteraction.Rect(safe.left, safe.top, safe.right, safe.bottom),
                minScale, AuraWindowInteraction.DEFAULT_MAX_SCALE);
    }

    private static Region toRegion(int rawRegion) {
        switch (rawRegion) {
            case AuraSmallWindowBottomDecoration.REGION_BOTTOM:
                return Region.BOTTOM;
            case AuraSmallWindowBottomDecoration.REGION_LOWER_LEFT:
                return Region.LOWER_LEFT;
            case AuraSmallWindowBottomDecoration.REGION_LOWER_RIGHT:
                return Region.LOWER_RIGHT;
            default:
                return null;
        }
    }
}
