/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.SparseArray;
import android.view.Display;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.sysui.ShellInit;

import java.io.PrintWriter;

/** Moves Aura phone windows with the existing IME animation; never changes their size or scale. */
public final class AuraSmallWindowImeController
        implements DisplayImeController.ImePositionProcessor {
    private final ShellTaskOrganizer mOrganizer;
    private final DisplayController mDisplays;
    private final SparseArray<State> mTasks = new SparseArray<>();
    private State mAnimating;
    private int mHiddenTop;
    private int mShownTop;
    private boolean mShowing;
    private boolean mSuspended;
    private boolean mRestorePending;
    private boolean mImeVisible;
    private boolean mFloatingIme;
    private int mImeTop;
    private boolean mImeControlling = true;

    public AuraSmallWindowImeController(ShellInit init, ShellTaskOrganizer organizer,
            DisplayController displays, DisplayImeController ime) {
        mOrganizer = organizer;
        mDisplays = displays;
        init.addInitCallback(() -> {
            if (SystemProperties.getBoolean("ro.aura.small_window", false)) {
                ime.addPositionProcessor(this);
            }
        }, this);
    }

    public void onTaskAppeared(RunningTaskInfo info, SurfaceControl leash) {
        if (!isAuraTask(info)) return;
        final State state = new State();
        state.info = info;
        state.leash = leash;
        state.y = info.configuration.windowConfiguration.getBounds().top;
        mTasks.put(info.taskId, state);
    }

    public void onTaskInfoChanged(RunningTaskInfo info) {
        final State state = mTasks.get(info.taskId);
        if (state == null) return;
        if (!isAuraTask(info)) {
            onTaskVanished(info.taskId);
            return;
        }
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        final Rect old = state.info.configuration.windowConfiguration.getBounds();
        final boolean geometryChanged = !bounds.equals(old)
                || Float.compare(info.auraSmallWindowScale,
                        state.info.auraSmallWindowScale) != 0;
        final boolean interactionAck = state.interactionExpected != null
                && bounds.equals(state.interactionExpected)
                && Float.compare(info.auraSmallWindowScale, state.interactionScale) == 0;
        if (state.interaction != null && (!info.isVisible
                || geometryChanged && !interactionAck)) {
            // The owner must check isInteractionValid before writing its next surface frame.
            state.interaction = null;
            state.interactionExpected = null;
        }
        if (state.interactionExpected != null
                && (interactionAck || state.interaction == null && geometryChanged)) {
            state.interactionExpected = null;
        }
        // A user's move, rotation or resize supersedes the remembered pre-IME position.
        if (!bounds.equals(old) && !bounds.equals(state.expected)) {
            state.info = info;
            stopSurfaceAnimation(state);
            state.before = null;
            state.expected = null;
        }
        if (bounds.equals(state.expected)) state.expected = null;
        state.info = info;
        if (mAnimating != state) state.y = bounds.top;
        resumeDeferredIme(state);
    }

    /**
     * Main-thread lease for one settled Aura gesture. Null means retry on the next DOWN;
     * never reset an in-flight IME surface to acquire ownership. Rects are private copies.
     */
    public InteractionSnapshot tryAcquireInteraction(RunningTaskInfo requested) {
        final State state = mTasks.get(requested.taskId);
        if (state == null || !isAuraTask(requested) || !requested.isVisible
                || !state.info.isVisible || !state.info.token.equals(requested.token)
                || !state.leash.isValid() || mSuspended || mAnimating == state
                || state.dragging || state.expected != null || state.interaction != null
                || state.interactionExpected != null) return null;
        final Rect bounds = state.info.configuration.windowConfiguration.getBounds();
        if (!bounds.equals(requested.configuration.windowConfiguration.getBounds())
                || Float.compare(state.info.auraSmallWindowScale,
                        requested.auraSmallWindowScale) != 0
                || Math.abs(state.y - bounds.top) > 0.5f) return null;
        final DisplayLayout layout = mDisplays.getDisplayLayout(state.info.displayId);
        if (layout == null) return null;
        final Rect safe = new Rect();
        layout.getStableBounds(safe);
        if (mImeVisible && !mFloatingIme) {
            // Conservatively reserve the final keyboard area even while another target animates.
            safe.bottom = Math.min(safe.bottom, Math.min(mImeTop, mShownTop));
        }
        if (safe.isEmpty()) return null;
        state.interaction = new InteractionSnapshot(requested.taskId, requested.token,
                bounds, safe, requested.auraSmallWindowScale, layout.density());
        return state.interaction;
    }

    public boolean isInteractionValid(InteractionSnapshot snapshot) {
        if (snapshot == null || mSuspended) return false;
        final State state = mTasks.get(snapshot.taskId);
        return state != null && state.interaction == snapshot && state.info.isVisible
                && state.leash.isValid() && state.info.token.equals(snapshot.token);
    }

    /** Register the endpoint BEFORE submitting the final WCT; TaskInfo may arrive synchronously. */
    public boolean prepareInteractionCommit(InteractionSnapshot snapshot, Rect bounds, float scale) {
        if (!isInteractionValid(snapshot) || bounds == null || bounds.isEmpty()
                || bounds.width() != snapshot.mLogicalBounds.width()
                || bounds.height() != snapshot.mLogicalBounds.height()
                || !Float.isFinite(scale) || scale <= 0f || scale >= 1f) return false;
        final State state = mTasks.get(snapshot.taskId);
        state.interactionExpected = new Rect(bounds);
        state.interactionScale = scale;
        return true;
    }

    /**
     * Call after the owner's final surface transaction and Core WCT have been submitted.
     * Pass null bounds for cancellation/no geometry commit. The caller restores its snapshot
     * before cancellation; this method does not write the owner's leash. A geometry commit
     * remains fenced until TaskInfo confirms it (or a newer external geometry supersedes it).
     */
    public void releaseInteraction(InteractionSnapshot snapshot, Rect committedBounds,
            float committedScale) {
        if (snapshot == null) return;
        final State state = mTasks.get(snapshot.taskId);
        if (state == null || state.interaction != snapshot) return;
        state.interaction = null;
        if (committedBounds != null) {
            state.before = null; // A successful user resize supersedes pre-IME position memory.
            final Rect current = state.info.configuration.windowConfiguration.getBounds();
            if (!current.equals(committedBounds)
                    || Float.compare(state.info.auraSmallWindowScale, committedScale) != 0) {
                state.interactionExpected = new Rect(committedBounds);
                state.interactionScale = committedScale;
            } else {
                // A no-op endpoint may not produce any TaskInfo callback.
                state.interactionExpected = null;
            }
        } else {
            state.interactionExpected = null;
        }
        resumeDeferredIme(state);
    }

    public static final class InteractionSnapshot {
        public final int taskId;
        public final WindowContainerToken token;
        public final float scale;
        public final float density;
        private final Rect mLogicalBounds;
        private final Rect mSafeBounds;

        private InteractionSnapshot(int id, WindowContainerToken taskToken, Rect bounds,
                Rect safe, float actualScale, float displayDensity) {
            taskId = id;
            token = taskToken;
            scale = actualScale;
            density = displayDensity;
            mLogicalBounds = new Rect(bounds);
            mSafeBounds = new Rect(safe);
        }

        public Rect logicalBounds() { return new Rect(mLogicalBounds); }
        public Rect safeBounds() { return new Rect(mSafeBounds); }
    }

    public void onTaskVanished(int taskId) {
        final State state = mTasks.get(taskId);
        if (state == null) return;
        // Removal/fullscreen transitions own the leash now. Do not overwrite their transform.
        if (mAnimating == state) mAnimating = null;
        mTasks.remove(taskId);
    }

    public void onDragStateChanged(int taskId, boolean dragging) {
        final State state = mTasks.get(taskId);
        if (state == null) return;
        state.dragging = dragging;
        if (dragging) {
            // The drag positioner reads logical Task bounds. Abort a transient surface offset
            // before it takes that baseline, and do not later undo the user's chosen position.
            stopSurfaceAnimation(state);
            state.before = null;
        }
    }

    /** Legacy caption drag must not reset an in-flight IME offset before reading its baseline. */
    public boolean canBeginCaptionDrag(int taskId) {
        final State state = mTasks.get(taskId);
        return state != null && !mSuspended && mAnimating != state && !state.dragging
                && state.expected == null && state.interactionExpected == null
                && state.interaction == null && state.info.isVisible && state.leash.isValid()
                && Math.abs(state.y
                        - state.info.configuration.windowConfiguration.getBounds().top) <= .5f;
    }

    public void setSuspended(boolean suspended) {
        mSuspended = suspended;
        if (suspended) {
            for (int i = 0; i < mTasks.size(); i++) {
                final State state = mTasks.valueAt(i);
                if (state.interaction != null) {
                    state.interaction = null;
                    state.interactionExpected = null;
                    state.imeDeferred = true;
                }
            }
        }
        if (suspended && mAnimating != null) stopSurfaceAnimation(mAnimating);
        if (!suspended && mRestorePending) {
            mRestorePending = false;
            onImeControlTargetChanged(Display.DEFAULT_DISPLAY, false);
        }
        if (!suspended) {
            for (int i = 0; i < mTasks.size(); i++) resumeDeferredIme(mTasks.valueAt(i));
        }
    }

    @Override
    public int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
            boolean showing, boolean floating, SurfaceControl.Transaction t) {
        if (displayId != Display.DEFAULT_DISPLAY) return 0;
        final DisplayLayout layout = mDisplays.getDisplayLayout(displayId);
        if (layout == null) return 0;
        final InsetsState insets = mDisplays.getInsetsState(displayId);
        final InsetsSource source = insets == null ? null : insets.peekSource(InsetsSource.ID_IME);
        final Rect frame = source == null ? null
                : source.getVisibleFrame() != null ? source.getVisibleFrame() : source.getFrame();
        final boolean isFloatingIme = floating || (frame != null && frame.height() > 0
                && frame.height() < 58 * layout.density());
        mHiddenTop = hiddenTop;
        mShownTop = shownTop;
        mShowing = showing;
        mImeVisible = showing;
        mFloatingIme = isFloatingIme;
        mImeTop = showing ? hiddenTop : shownTop;
        if (mSuspended) return 0;
        final WindowContainerToken target = mOrganizer.getImeLayeringTarget(displayId);
        State selected = null;
        for (int i = 0; i < mTasks.size(); i++) {
            final State state = mTasks.valueAt(i);
            if (state.interaction != null || state.interactionExpected != null) {
                state.imeDeferred = true;
                continue;
            }
            if (showing && state.info.token.equals(target)
                    && state.info.isVisible && !state.dragging && !isFloatingIme) {
                selected = state;
                break;
            }
            if (!showing && state.before != null && !state.dragging) selected = state;
        }
        // If focus changes to another app's keyboard, restore the old small window instead of
        // lifting it for that app. This also handles switching to a floating keyboard.
        if (selected == null && showing) {
            for (int i = 0; i < mTasks.size(); i++) {
                final State state = mTasks.valueAt(i);
                if (state.before != null && !state.dragging && state.interaction == null
                        && state.interactionExpected == null) {
                    selected = state;
                    break;
                }
            }
        }
        if (selected == null || !selected.leash.isValid()) {
            if (mAnimating != null) stopSurfaceAnimation(mAnimating);
            return 0;
        }
        final State state = selected;
        final Rect bounds = state.info.configuration.windowConfiguration.getBounds();
        final boolean lifting = showing && mImeControlling && !isFloatingIme
                && state.info.token.equals(target);
        if (lifting && state.before == null) state.before = new Rect(bounds);
        final Rect origin = state.before != null ? state.before : bounds;
        final int targetY = lifting
                ? calculateTargetTop(origin, state.info.auraSmallWindowScale, shownTop,
                        layout.stableInsets().top, layout.density(), layout.isLandscape())
                : origin.top;
        if (mAnimating != null && mAnimating != state) stopSurfaceAnimation(mAnimating);
        state.startY = state.y;
        state.endY = targetY;
        state.restoring = !lifting;
        state.firstProgress = Float.NaN;
        mAnimating = state;
        t.setPosition(state.leash, bounds.left, state.y);
        return 0;
    }

    /** Screen-pixel geometry. density is densityDpi / 160, never densityDpi / 100. */
    public static int calculateTargetTop(Rect logicalBounds, float scale, int imeTop,
            int safeTop, float density, boolean landscape) {
        return AuraSmallWindowImeGeometry.calculateTargetTop(logicalBounds, scale,
                imeTop, safeTop, density, landscape);
    }

    @Override
    public void onImePositionChanged(int displayId, int imeTop, SurfaceControl.Transaction t) {
        if (displayId == Display.DEFAULT_DISPLAY) mImeTop = imeTop;
        final State state = mAnimating;
        if (displayId != Display.DEFAULT_DISPLAY || state == null
                || !state.leash.isValid()) return;
        final int start = mShowing ? mHiddenTop : mShownTop;
        final int end = mShowing ? mShownTop : mHiddenTop;
        final float rawProgress = end == start ? 1f
                : Math.max(0f, Math.min(1f, (float) (imeTop - start) / (end - start)));
        // A reversed/restarted IME animation can start at a nonzero fraction. Continue from
        // the current surface position rather than interpolating it a second time and jumping.
        if (Float.isNaN(state.firstProgress)) state.firstProgress = rawProgress;
        final float progress = state.firstProgress >= 1f ? 1f
                : Math.max(0f, (rawProgress - state.firstProgress) / (1f - state.firstProgress));
        state.y = state.startY + (state.endY - state.startY) * progress;
        t.setPosition(state.leash,
                state.info.configuration.windowConfiguration.getBounds().left, state.y);
    }

    @Override
    public void onImeEndPositioning(int displayId, boolean cancel, SurfaceControl.Transaction t) {
        if (displayId != Display.DEFAULT_DISPLAY || mAnimating == null) return;
        final State state = mAnimating;
        mAnimating = null;
        if (!state.leash.isValid() || state.dragging || mSuspended) return;
        final Rect bounds = new Rect(state.info.configuration.windowConfiguration.getBounds());
        // Canceled/reversed IME animations retain the actual last rendered position. Commit it
        // instead of leaving mAnimating permanently busy or snapping back to old Task bounds.
        bounds.offsetTo(bounds.left, cancel ? Math.round(state.y) : state.endY);
        state.y = bounds.top;
        t.setPosition(state.leash, bounds.left, bounds.top);
        if (!bounds.equals(state.info.configuration.windowConfiguration.getBounds())) {
            state.expected = new Rect(bounds);
            mOrganizer.applyTransaction(new WindowContainerTransaction()
                    .setBounds(state.info.token, bounds));
        }
        if (state.restoring && !cancel) state.before = null;
    }

    @Override
    public void onImeControlTargetChanged(int displayId, boolean controlling) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        mImeControlling = controlling;
        if (controlling) return;
        if (mSuspended) {
            mRestorePending = true;
            return;
        }
        if (mAnimating != null) stopSurfaceAnimation(mAnimating);
        // The fullscreen app now owns the animation, so there may be no Shell hide callback.
        for (int i = 0; i < mTasks.size(); i++) {
            final State state = mTasks.valueAt(i);
            if (state.interaction != null || state.interactionExpected != null) {
                state.imeDeferred = true;
                continue;
            }
            if (state.before == null || state.dragging) continue;
            final Rect restored = new Rect(state.info.configuration.windowConfiguration.getBounds());
            restored.offsetTo(restored.left, state.before.top);
            state.before = null;
            state.expected = new Rect(restored);
            mOrganizer.applyTransaction(new WindowContainerTransaction()
                    .setBounds(state.info.token, restored));
        }
    }

    @Override
    public void onImeVisibilityChanged(int displayId, boolean showing) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            mImeVisible = showing;
            for (int i = 0; i < mTasks.size(); i++) {
                final State state = mTasks.valueAt(i);
                if (state.interaction != null || state.interactionExpected != null) {
                    state.imeDeferred = true;
                }
            }
        }
        if (displayId == Display.DEFAULT_DISPLAY && !showing && mSuspended) {
            mRestorePending = true;
        }
    }

    /** Apply a keyboard change missed while a gesture owned the surface, after Core ack. */
    private void resumeDeferredIme(State state) {
        if (!state.imeDeferred || mSuspended || mAnimating != null || state.dragging
                || state.expected != null || state.interaction != null
                || state.interactionExpected != null || !state.info.isVisible
                || !state.leash.isValid()) return;
        state.imeDeferred = false;
        final Rect bounds = state.info.configuration.windowConfiguration.getBounds();
        final DisplayLayout layout = mDisplays.getDisplayLayout(state.info.displayId);
        if (layout == null) return;
        final boolean lifting = mImeVisible && mImeControlling && !mFloatingIme
                && state.info.token.equals(
                mOrganizer.getImeLayeringTarget(state.info.displayId));
        if (!lifting && state.before == null) return;
        if (lifting && state.before == null) state.before = new Rect(bounds);
        final Rect origin = state.before != null ? state.before : bounds;
        final Rect target = new Rect(bounds);
        target.offsetTo(bounds.left, lifting ? calculateTargetTop(origin,
                state.info.auraSmallWindowScale, mShownTop, layout.stableInsets().top,
                layout.density(), layout.isLandscape()) : origin.top);
        if (!lifting) state.before = null;
        if (target.equals(bounds)) return;
        state.y = target.top;
        state.expected = new Rect(target);
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setPosition(state.leash, target.left, target.top).apply();
        }
        mOrganizer.applyTransaction(new WindowContainerTransaction()
                .setBounds(state.info.token, target));
    }

    private void stopSurfaceAnimation(State state) {
        if (mAnimating != state) return;
        mAnimating = null;
        final Rect bounds = state.info.configuration.windowConfiguration.getBounds();
        state.y = bounds.top;
        if (state.leash.isValid()) {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                t.setPosition(state.leash, bounds.left, bounds.top).apply();
            }
        }
    }

    private static boolean isAuraTask(RunningTaskInfo info) {
        return SystemProperties.getBoolean("ro.aura.small_window", false)
                && info.displayId == Display.DEFAULT_DISPLAY
                && info.getActivityType() == ACTIVITY_TYPE_STANDARD
                && info.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && info.auraSmallWindowScale > 0f && info.auraSmallWindowScale < 1f;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Aura IME lift suspended=" + mSuspended);
        for (int i = 0; i < mTasks.size(); i++) {
            final State state = mTasks.valueAt(i);
            pw.println(prefix + "  task=" + state.info.taskId + " before=" + state.before
                    + " y=" + state.y + " dragging=" + state.dragging
                    + " animating=" + (state == mAnimating)
                    + " interaction=" + (state.interaction != null)
                    + " interactionExpected=" + state.interactionExpected
                    + " imeDeferred=" + state.imeDeferred);
        }
    }

    private static final class State {
        RunningTaskInfo info;
        SurfaceControl leash;
        Rect before;
        Rect expected;
        InteractionSnapshot interaction;
        Rect interactionExpected;
        float interactionScale;
        boolean imeDeferred;
        float y;
        float startY;
        int endY;
        boolean restoring;
        boolean dragging;
        float firstProgress;
    }
}
