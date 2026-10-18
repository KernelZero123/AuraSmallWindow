/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wm.shell.windowdecor;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.windowdecor.aura.AuraWindowGestureRecognizer;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Phase;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry;

/**
 * Main-thread adapter between physical controls and the pure gesture protocol. This class writes
 * no surfaces and starts no transitions. The owner retains it while CHANGE is becoming ready,
 * including when UP/CANCEL arrives first. Only the owner's applied frame advances rendered state.
 *
 * <p>The operation snapshot is built by the coordinator from the Codex-owned IME interaction lease
 * (see {@code AuraSmallWindowImeController.tryAcquireInteraction}): the confirmed logical bounds
 * and the keyboard-aware safe bounds. This class never re-derives the position from a stable
 * display layout: doing so would restore the pre-lift logical position and make the window jump at
 * the start of a gesture. It only cross-checks the frozen values against the committed task state
 * and refuses a snapshot that cannot describe this task (that refusal is a no-op for the caller,
 * which releases the lease it just took).
 */
final class AuraSmallWindowGestureSession {
    final WindowContainerToken token;
    final int taskId;
    final Snapshot snapshot;
    final Region region;
    private final AuraWindowGestureRecognizer mRecognizer;
    private Result mLatest;
    private boolean mTerminal;
    private AuraWindowInteraction.Rect mRenderedRect;
    private float mRenderedScale;
    private int mActivePointerId;
    private float mLastX;
    private float mLastY;
    private final float mGrabY;
    private final float mGrabX;
    private boolean mMiniCommit;
    private long mLastEventTimeMillis;

    private AuraSmallWindowGestureSession(RunningTaskInfo info, Snapshot value, Region area,
            AuraWindowGestureRecognizer.Config recognizerConfig,
            AuraWindowResizeGeometry.Config geometryConfig, PointerEvent down) {
        token = info.token;
        taskId = info.taskId;
        snapshot = value;
        region = area;
        mRecognizer = new AuraWindowGestureRecognizer(recognizerConfig, geometryConfig);
        mLatest = mRecognizer.begin(area, value, down);
        mRenderedRect = value.visibleRect;
        mRenderedScale = value.scale;
        mActivePointerId = down.pointerId;
        mLastX = down.rawX;
        mLastY = down.rawY;
        mGrabY = down.rawY;
        mGrabX = down.rawX;
        mLastEventTimeMillis = down.eventTimeMillis;
    }

    /**
     * Validates a frozen snapshot and opens the only session for it. Returns null (no side effect)
     * when this task cannot accept the interaction: not a standard Aura small window, not visible,
     * a malformed snapshot, a snapshot that does not describe this task's canvas/scale, a
     * multi-pointer DOWN, or a resize interval that is absent or already outside the current
     * scale (a first preview would snap, so the corner gesture is refused instead).
     *
     * <p>A contradictory resize interval ({@code minScale > maxScale}, e.g. a 220dp floor above
     * the 0.95 cap on a very dense display) only disables the corners: the bottom bar keeps
     * working, because it depends on thresholds rather than on the scale interval.
     */
    static AuraSmallWindowGestureSession begin(RunningTaskInfo info, Region region,
            Snapshot snapshot, AuraWindowGestureRecognizer.Config recognizerConfig,
            AuraWindowResizeGeometry.Config geometryConfig, PointerEvent down) {
        if (info == null || region == null || snapshot == null || down == null
                || recognizerConfig == null || geometryConfig == null) return null;
        if (!CaptionWindowDecoration.isAuraSmallWindowTask(info) || !info.isVisible) return null;
        if (down.phase != Phase.DOWN || down.pointerCount != 1 || down.pointerId < 0
                || !Float.isFinite(down.rawX) || !Float.isFinite(down.rawY)) return null;
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        if (bounds == null || bounds.isEmpty()
                || snapshot.logicalWidth != bounds.width()
                || snapshot.logicalHeight != bounds.height()) return null;
        if (!Float.isFinite(snapshot.density) || snapshot.density <= 0f) return null;
        final float scale = info.auraSmallWindowScale;
        if (!Float.isFinite(scale) || scale <= 0f || scale > 1f
                || Float.compare(snapshot.scale, scale) != 0) return null;
        if (!Float.isFinite(snapshot.minScale) || !Float.isFinite(snapshot.maxScale)) return null;
        if (snapshot.visibleRect.width() != Math.round(bounds.width() * scale)
                || snapshot.visibleRect.height() != Math.round(bounds.height() * scale)
                || snapshot.visibleRect.width() <= 0 || snapshot.visibleRect.height() <= 0) {
            return null;
        }
        if (region.isCorner()) {
            final AuraWindowResizeGeometry.Limits limits = AuraWindowResizeGeometry.limits(snapshot,
                    region, geometryConfig);
            // Disabling an illegal starting interval is preferable to snapping on first MOVE.
            if (!limits.available || limits.currentScaleOutsideLimits) return null;
        }
        return new AuraSmallWindowGestureSession(info, snapshot, region, recognizerConfig,
                geometryConfig, down);
    }

    Result accept(int rawPhase, float rawX, float rawY, int pointerId, long timeMillis,
            int pointerCount) {
        if (mTerminal) return mLatest;
        final Phase phase;
        switch (rawPhase) {
            case 1: phase = Phase.MOVE; break;
            case 2: phase = Phase.UP; break;
            case 3: phase = Phase.CANCEL; break;
            // A second DOWN belongs to a new owner, never restart this snapshot in place.
            default: phase = Phase.CANCEL; break;
        }
        return feed(new PointerEvent(phase, timeMillis, pointerId, pointerCount, rawX, rawY));
    }

    /**
     * Owner-side cancellation (cross-host interference, lifecycle or task invalidation) with no
     * new pointer sample: the frozen interaction is restored and the recognizer becomes terminal.
     */
    Result cancelByOwner() {
        if (mTerminal) return mLatest;
        return feed(new PointerEvent(Phase.CANCEL, mLastEventTimeMillis, mActivePointerId, 1,
                mLastX, mLastY));
    }

    private Result feed(PointerEvent event) {
        Result result = mRecognizer.onEvent(event);
        if (result != null && result.intent == Intent.PREVIEW_RESIZE
                && region.isCorner()) {
            final float requested = AuraWindowResizeGeometry.projectedScale(snapshot, region,
                    mGrabX, mGrabY, event.rawX, event.rawY);
            result = com.android.wm.shell.windowdecor.aura.AuraSmallWindowMiniGeometry
                    .preview(snapshot, result, requested);
        }
        if (result != null && result.intent == Intent.COMMIT_RESIZE
                && com.android.wm.shell.windowdecor.aura.AuraSmallWindowMiniGeometry
                    .crossesThreshold(snapshot, region, mGrabX, mGrabY, event.rawX, event.rawY)) {
            mMiniCommit = true;
            result = com.android.wm.shell.windowdecor.aura.AuraSmallWindowMiniGeometry
                    .commit(snapshot, result);
        }
        if (result != null) {
            mLatest = result;
            mTerminal = !result.active;
        }
        mActivePointerId = event.pointerId;
        mLastX = event.rawX;
        mLastY = event.rawY;
        mLastEventTimeMillis = event.eventTimeMillis;
        return mLatest;
    }

    boolean isMiniCommit() { return mMiniCommit; }

    /** A launch replacement requests an exit directly, without injecting a touch gesture. */
    void dismissForReplacement() {
        if (region.isCorner() || mTerminal) throw new IllegalStateException("Not an idle bottom session");
        mLatest = Result.dismissForReplacement(snapshot);
        mTerminal = true;
    }

    float dragDeltaY() { return mLastY - mGrabY; }
    Result latest() { return mLatest; }
    boolean isTerminal() { return mTerminal; }
    AuraWindowInteraction.Rect renderedRect() { return mRenderedRect; }
    float renderedScale() { return mRenderedScale; }

    /** Call only after a preview transaction has actually been applied by the surface owner. */
    void onPreviewApplied(Result result) {
        if (result.intent != Intent.PREVIEW_RESIZE && result.intent != Intent.PREVIEW_FULLSCREEN
                && result.intent != Intent.PREVIEW_DISMISS) {
            throw new IllegalArgumentException("Only rendered previews advance visual state");
        }
        mRenderedRect = result.visibleRect;
        mRenderedScale = result.scale;
    }

    /** Fullscreen endpoints are deliberately excluded; they require a new ready mode transition. */
    WindowContainerTransaction resizeCommit() {
        final Rect logical = committedLogicalBounds();
        if (logical == null) {
            throw new IllegalStateException("No resize commit");
        }
        return new WindowContainerTransaction()
                .setAuraSmallWindowGeometry(token, logical, mLatest.scale, mMiniCommit);
    }

    /**
     * Logical bounds of the newest commit result (frozen screen position plus the unchanged
     * logical canvas), or null when the newest intent is not a resize commit. Used both for the
     * final geometry WCT and for the IME lease endpoint, so both describe the same rectangle.
     */
    Rect committedLogicalBounds() {
        if (mLatest.intent != Intent.COMMIT_RESIZE) return null;
        final int left = mLatest.visibleRect.left;
        final int top = mLatest.visibleRect.top;
        return new Rect(left, top, left + snapshot.logicalWidth, top + snapshot.logicalHeight);
    }
}
