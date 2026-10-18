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

package com.android.wm.shell.windowdecor.aura;

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Phase;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;

/**
 * Pure recognizer for the Aura bottom bar and the two resize corners.
 *
 * <p>Responsibilities: direction decision, thresholds, commit/cancel and the preview geometry
 * derived from them. Non-responsibilities: it never hit-tests a region (the caller passes the
 * region decided by the view), never animates, never removes a task, never changes a windowing
 * mode and never writes surface state.
 *
 * <p>State machine:
 * <ul>
 *   <li>{@link #begin}: latches the operation snapshot and the active pointer. A gesture is
 *       single-pointer only: a {@code DOWN} with {@code pointerCount > 1} never starts, and any
 *       event with {@code pointerCount > 1}, a foreign pointer id, a zero pointer count or
 *       {@link Phase#CANCEL} restores the snapshot.</li>
 *   <li>Bottom bar: a drag stays undecided below the touch slop. Once the slop is exceeded the
 *       dominant axis decides: vertical up is dismiss, vertical down is fullscreen, horizontal
 *       is rejected permanently (the gesture is cancelled and no preview is ever produced).
 *       The axis decision is sticky; releasing needs either the commit distance or a fling
 *       (velocity threshold <em>and</em> a minimum displacement) in the resolved direction.
 *       A flick that arrives as {@code DOWN}+{@code UP} with no {@code MOVE} at all still gets
 *       its axis decision from the terminal sample, so real flicks are not lost to batching.</li>
 *   <li>Corners: every sample above the slop is a resize preview delegated to
 *       {@link AuraWindowResizeGeometry}. A release below the slop is a tap and cancels.</li>
 *   <li>Only {@link Phase#UP} can commit; {@code CANCEL}, a tap, a lateral drag, multi-finger
 *       interference and pointer loss all restore the operation snapshot and leave the
 *       recognizer ready for the next {@code DOWN}.</li>
 * </ul>
 *
 * <p>All thresholds are fields of {@link Config} (single source of truth, no hidden constants)
 * and dp is converted exactly once, when the {@code Config} is built.
 */
public final class AuraWindowGestureRecognizer {

    private final Config mConfig;
    private final AuraWindowResizeGeometry.Config mGeometryConfig;

    private boolean mTracking;
    private Region mRegion;
    private Snapshot mSnapshot;
    private int mActivePointerId;
    private long mDownTimeMillis;
    private float mGrabX;
    private float mGrabY;
    private Direction mDirection;

    /** What a tracked gesture has decided so far. */
    private enum Direction {
        /** Below the slop: no axis decision yet. */
        UNDECIDED,
        /** Bottom bar, swipe up: preview/commit dismiss. */
        DISMISS,
        /** Bottom bar, swipe down: preview/commit fullscreen. */
        FULLSCREEN,
        /** Corner: uniform resize. */
        RESIZE
    }

    /** Gesture thresholds; every suggested value is a parameter here, converted once from dp. */
    public static final class Config {
        /** Display density used for the dp conversion. */
        public final float density;
        /** Touch slop in pixels, provided by the caller (ViewConfiguration). */
        public final float touchSlopPx;
        /** Commit distance in dp (initial 48dp) converted to pixels. */
        public final float commitDistancePx;
        /** Fling threshold in dp/s (initial 800dp/s) converted to pixels/s. */
        public final float flingVelocityPxPerSec;
        /** Minimum displacement for a fling, in dp (initial 16dp) converted to pixels. */
        public final float minFlingDisplacementPx;
        /** Travel that maps to progress 1.0; defaults to the commit distance. */
        public final float progressDistancePx;
        /** Cap for the follow-finger dismiss translation. */
        public final float dismissPreviewTravelPx;

        private Config(Builder builder) {
            this.density = builder.density;
            this.touchSlopPx = builder.touchSlopPx;
            this.commitDistancePx = AuraWindowInteraction.dpToPx(builder.commitDistanceDp,
                    builder.density);
            this.flingVelocityPxPerSec = AuraWindowInteraction.dpToPx(
                    builder.flingVelocityDpPerSec, builder.density);
            this.minFlingDisplacementPx = AuraWindowInteraction.dpToPx(
                    builder.minFlingDisplacementDp, builder.density);
            this.progressDistancePx = AuraWindowInteraction.dpToPx(builder.progressDistanceDp,
                    builder.density);
            this.dismissPreviewTravelPx = AuraWindowInteraction.dpToPx(
                    builder.dismissPreviewTravelDp, builder.density);
        }

        public static Builder builder(float density) {
            return new Builder(density);
        }

        /** Plan defaults with a caller-provided touch slop in pixels. */
        public static Config defaults(float density, float touchSlopPx) {
            return builder(density).touchSlopPx(touchSlopPx).build();
        }

        @Override
        public String toString() {
            return "Config(density=" + density + " slopPx=" + touchSlopPx + " commitPx="
                    + commitDistancePx + " flingPxPerSec=" + flingVelocityPxPerSec
                    + " minFlingPx=" + minFlingDisplacementPx + " progressPx="
                    + progressDistancePx + " dismissTravelPx=" + dismissPreviewTravelPx + ")";
        }

        /** Builder that keeps the dp values visible and converts them exactly once. */
        public static final class Builder {
            private final float density;
            private float touchSlopPx = -1f;
            private float commitDistanceDp = AuraWindowInteraction.DEFAULT_COMMIT_DISTANCE_DP;
            private float flingVelocityDpPerSec =
                    AuraWindowInteraction.DEFAULT_FLING_VELOCITY_DP_PER_SEC;
            private float minFlingDisplacementDp =
                    AuraWindowInteraction.DEFAULT_MIN_FLING_DISPLACEMENT_DP;
            private float progressDistanceDp = AuraWindowInteraction.DEFAULT_PROGRESS_DISTANCE_DP;
            private float dismissPreviewTravelDp =
                    AuraWindowInteraction.DEFAULT_DISMISS_PREVIEW_TRAVEL_DP;

            private Builder(float density) {
                if (!(density > 0f)) {
                    throw new IllegalArgumentException("density must be > 0, was " + density);
                }
                this.density = density;
            }

            /** Touch slop already in pixels (ViewConfiguration.getScaledTouchSlop()). */
            public Builder touchSlopPx(float px) {
                if (!(px >= 0f)) {
                    throw new IllegalArgumentException("touchSlopPx must be >= 0, was " + px);
                }
                this.touchSlopPx = px;
                return this;
            }

            /** Touch slop in dp, converted with this builder's density. */
            public Builder touchSlopDp(float dp) {
                return touchSlopPx(AuraWindowInteraction.dpToPx(dp, density));
            }

            public Builder commitDistanceDp(float dp) {
                this.commitDistanceDp = dp;
                return this;
            }

            public Builder flingVelocityDpPerSec(float dpPerSec) {
                this.flingVelocityDpPerSec = dpPerSec;
                return this;
            }

            public Builder minFlingDisplacementDp(float dp) {
                this.minFlingDisplacementDp = dp;
                return this;
            }

            public Builder progressDistanceDp(float dp) {
                this.progressDistanceDp = dp;
                return this;
            }

            public Builder dismissPreviewTravelDp(float dp) {
                this.dismissPreviewTravelDp = dp;
                return this;
            }

            public Config build() {
                if (touchSlopPx < 0f) {
                    throw new IllegalStateException(
                            "touchSlopPx is required; provide the ViewConfiguration value");
                }
                if (!(commitDistanceDp > 0f)) {
                    throw new IllegalStateException(
                            "commitDistanceDp must be > 0, was " + commitDistanceDp);
                }
                if (!(progressDistanceDp > 0f)) {
                    throw new IllegalStateException(
                            "progressDistanceDp must be > 0, was " + progressDistanceDp);
                }
                if (!(dismissPreviewTravelDp >= 0f)) {
                    throw new IllegalStateException(
                            "dismissPreviewTravelDp must be >= 0, was " + dismissPreviewTravelDp);
                }
                return new Config(this);
            }
        }
    }

    /** Plan-threshold recognizer with a caller-provided touch slop. */
    public AuraWindowGestureRecognizer(float density, float touchSlopPx) {
        this(Config.defaults(density, touchSlopPx),
                AuraWindowResizeGeometry.Config.forDensity(density));
    }

    public AuraWindowGestureRecognizer(Config config,
            AuraWindowResizeGeometry.Config geometryConfig) {
        if (config == null || geometryConfig == null) {
            throw new IllegalArgumentException("config and geometryConfig must not be null");
        }
        mConfig = config;
        mGeometryConfig = geometryConfig;
    }

    public Config config() {
        return mConfig;
    }

    public boolean isTracking() {
        return mTracking;
    }

    /** Region of the tracked gesture, or null when idle. */
    public Region trackingRegion() {
        return mTracking ? mRegion : null;
    }

    /** Operation snapshot of the tracked gesture, or null when idle. */
    public Snapshot trackingSnapshot() {
        return mTracking ? mSnapshot : null;
    }

    /**
     * Starts a gesture from a {@code DOWN} event. The snapshot is latched here and is the only
     * state every later result is derived from. A {@code DOWN} that arrives while another
     * gesture is still active discards the old gesture (rapid re-entry) and reports
     * {@link Result#canceledPrevious}.
     *
     * <p>A {@code DOWN} with more than one pointer never starts a gesture.
     */
    public Result begin(Region region, Snapshot snapshot, PointerEvent down) {
        if (region == null || snapshot == null || down == null) {
            throw new IllegalArgumentException("region, snapshot and down must not be null");
        }
        if (down.phase != Phase.DOWN) {
            throw new IllegalArgumentException("begin requires Phase.DOWN, was " + down.phase);
        }
        final boolean canceledPrevious = mTracking;
        resetState();
        final AuraWindowResizeGeometry.Result restore =
                region.isCorner() ? AuraWindowResizeGeometry.restore(snapshot, region,
                        mGeometryConfig) : null;
        if (down.pointerCount > 1) {
            return Result.idle(snapshot, region, canceledPrevious, restore);
        }
        mTracking = true;
        mRegion = region;
        mSnapshot = snapshot;
        mActivePointerId = down.pointerId;
        mDownTimeMillis = down.eventTimeMillis;
        mGrabX = down.rawX;
        mGrabY = down.rawY;
        mDirection = region.isCorner() ? Direction.RESIZE : Direction.UNDECIDED;
        return new Result(Intent.NONE, region, snapshot.scale, snapshot.visibleRect, 0f, false,
                true, true, canceledPrevious, restore);
    }

    /**
     * Feeds a {@code MOVE}, {@code UP} or {@code CANCEL} event to the active gesture.
     *
     * <p>Returns null when no gesture is active: a stray event is not ours and the caller must
     * ignore it. {@code DOWN} must be routed to {@link #begin}.
     */
    public Result onEvent(PointerEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (!mTracking) {
            return null;
        }
        if (event.pointerCount > 1) {
            // Multi-finger interference: restore and stop.
            return cancelInternal(false);
        }
        if (event.pointerCount < 1) {
            // Active pointer reported as gone without an UP for it.
            return cancelInternal(false);
        }
        if (event.phase == Phase.CANCEL) {
            return cancelInternal(false);
        }
        if (event.pointerId != mActivePointerId) {
            // The active finger was replaced or lost.
            return cancelInternal(false);
        }
        if (event.phase == Phase.MOVE) {
            return onMove(event);
        }
        if (event.phase == Phase.UP) {
            return onUp(event);
        }
        return null;
    }

    /**
     * Convenience dispatcher: {@code DOWN} starts a gesture, anything else is fed to the active
     * one. Returns null when there is nothing to do (stray event while idle).
     */
    public Result onEvent(Region region, Snapshot snapshot, PointerEvent event) {
        if (event != null && event.phase == Phase.DOWN) {
            return begin(region, snapshot, event);
        }
        return onEvent(event);
    }

    /**
     * Explicit cancel, for the caller losing gesture ownership (view detach, task change,
     * another transform owner taking over). Returns the restore result, or null when idle.
     */
    public Result cancel() {
        return mTracking ? cancelInternal(false) : null;
    }

    private Result onMove(PointerEvent event) {
        if (mDirection == Direction.RESIZE) {
            return resizeSample(event);
        }
        final float dx = event.rawX - mGrabX;
        final float dy = event.rawY - mGrabY;
        if (mDirection == Direction.UNDECIDED) {
            final Direction resolved = verticalDirectionOrNull(dx, dy);
            if (resolved != null) {
                mDirection = resolved;
            } else if (isLateral(dx, dy)) {
                // Long horizontal drag: never a bottom bar action. Rejected permanently, so a
                // later vertical move in the same gesture cannot turn it into a dismiss.
                return cancelInternal(false);
            } else {
                // Below the slop (or a perfect diagonal tie): keep owning the gesture but change
                // nothing.
                return new Result(Intent.NONE, mRegion, mSnapshot.scale, mSnapshot.visibleRect, 0f,
                        false, true, true, false, null);
            }
        }
        final boolean dismiss = mDirection == Direction.DISMISS;
        final float travel = dismiss ? -dy : dy;
        if (travel <= 0f) {
            // Reversed past the grab point: direction stays sticky, but there is nothing to
            // preview and nothing that could commit.
            return new Result(dismiss ? Intent.PREVIEW_DISMISS : Intent.PREVIEW_FULLSCREEN,
                    mRegion, mSnapshot.scale, mSnapshot.visibleRect, 0f, false, true, true, false,
                    null);
        }
        final float progress = AuraWindowInteraction.clamp(travel / mConfig.progressDistancePx,
                0f, 1f);
        if (dismiss) {
            final float offset = Math.min(travel, mConfig.dismissPreviewTravelPx);
            final Rect rect = mSnapshot.visibleRect.offset(0, -Math.round(offset));
            return new Result(Intent.PREVIEW_DISMISS, mRegion, mSnapshot.scale, rect, progress,
                    false, rect.equals(mSnapshot.visibleRect), true, false, null);
        }
        final float fraction = AuraWindowInteraction.clamp(travel / mConfig.commitDistancePx, 0f,
                1f);
        final Rect rect = mSnapshot.visibleRect.lerpTo(mSnapshot.fullscreenRect, fraction);
        final float scale = mSnapshot.scale + (1f - mSnapshot.scale) * fraction;
        return new Result(Intent.PREVIEW_FULLSCREEN, mRegion, scale, rect, progress, false,
                rect.equals(mSnapshot.visibleRect), true, false, null);
    }

    private Result onUp(PointerEvent event) {
        if (mDirection == Direction.RESIZE) {
            return resizeRelease(event);
        }
        final float dx = event.rawX - mGrabX;
        final float dy = event.rawY - mGrabY;
        if (mDirection == Direction.UNDECIDED) {
            // A flick can reach us with no MOVE at all (input batching, very fast flick), and the
            // axis decision belongs to the final displacement anyway: resolve it here, but only
            // for a vertical-dominant release beyond the slop. A tap, a below-slop release and a
            // lateral release all cancel.
            final Direction resolved = verticalDirectionOrNull(dx, dy);
            if (resolved == null) {
                return cancelInternal(false);
            }
            mDirection = resolved;
        }
        final boolean dismiss = mDirection == Direction.DISMISS;
        final float travel = dismiss ? -dy : dy;
        final boolean distanceReached = travel >= mConfig.commitDistancePx;
        final boolean flung = isFling(travel, event.eventTimeMillis);
        if (!distanceReached && !flung) {
            return cancelInternal(false);
        }
        final Rect rect;
        final float scale;
        if (dismiss) {
            final float offset = Math.min(travel, mConfig.dismissPreviewTravelPx);
            rect = mSnapshot.visibleRect.offset(0, -Math.round(offset));
            scale = mSnapshot.scale;
        } else {
            rect = mSnapshot.fullscreenRect;
            scale = 1f;
        }
        final Intent intent = dismiss ? Intent.COMMIT_DISMISS : Intent.COMMIT_FULLSCREEN;
        final Result result = new Result(intent, mRegion, scale, rect, 1f, true, false, false,
                false, null);
        resetState();
        return result;
    }

    /**
     * Axis decision for the bottom bar: the vertical axis wins once the slop is exceeded and it
     * dominates the horizontal axis. Returns null while the drag is undecided (below the slop, or
     * a perfect diagonal tie).
     */
    private Direction verticalDirectionOrNull(float dx, float dy) {
        final float absDx = Math.abs(dx);
        final float absDy = Math.abs(dy);
        if (absDy >= mConfig.touchSlopPx && absDy > absDx) {
            return dy < 0f ? Direction.DISMISS : Direction.FULLSCREEN;
        }
        return null;
    }

    /** True when the horizontal axis dominates beyond the slop: never a bottom bar action. */
    private boolean isLateral(float dx, float dy) {
        final float absDx = Math.abs(dx);
        final float absDy = Math.abs(dy);
        return absDx >= mConfig.touchSlopPx && absDx > absDy;
    }

    /**
     * Fling test: the velocity measured from the {@code DOWN} pointer sample to the terminal
     * pointer sample must reach the threshold <em>and</em> the travel must reach the minimum
     * displacement. A zero or negative time delta yields no fling (never a division by zero),
     * and low-displacement noise can therefore never commit on speed alone.
     */
    private boolean isFling(float travel, long terminalTimeMillis) {
        final long deltaMillis = terminalTimeMillis - mDownTimeMillis;
        if (deltaMillis <= 0L || travel <= 0f) {
            return false;
        }
        final float velocityPxPerSec = travel / deltaMillis * 1000f;
        return velocityPxPerSec >= mConfig.flingVelocityPxPerSec
                && travel >= mConfig.minFlingDisplacementPx;
    }

    private Result resizeSample(PointerEvent event) {
        final float dx = event.rawX - mGrabX;
        final float dy = event.rawY - mGrabY;
        if (dx * dx + dy * dy < mConfig.touchSlopPx * mConfig.touchSlopPx) {
            return new Result(Intent.NONE, mRegion, mSnapshot.scale, mSnapshot.visibleRect, 0f,
                    false, true, true, false, geometryResult(event));
        }
        final AuraWindowResizeGeometry.Result resize = geometryResult(event);
        if (!resize.resizable) {
            // Degraded: report the degrade explicitly and keep the snapshot geometry.
            return new Result(Intent.PREVIEW_RESIZE, mRegion, mSnapshot.scale,
                    mSnapshot.visibleRect, 0f, false, true, true, false, resize);
        }
        return new Result(Intent.PREVIEW_RESIZE, mRegion, resize.scale, resize.visibleRect,
                resize.progress, false, false, true, false, resize);
    }

    private Result resizeRelease(PointerEvent event) {
        final float dx = event.rawX - mGrabX;
        final float dy = event.rawY - mGrabY;
        if (dx * dx + dy * dy < mConfig.touchSlopPx * mConfig.touchSlopPx) {
            // Tap on the corner: no resize, restore.
            return cancelInternal(false);
        }
        final AuraWindowResizeGeometry.Result resize = geometryResult(event);
        if (!resize.resizable) {
            // Never commit an illegal size; report the degrade inside the restore result.
            return cancelWithResize(resize);
        }
        final Result result = new Result(Intent.COMMIT_RESIZE, mRegion, resize.scale,
                resize.visibleRect, 1f, true, false, false, false, resize);
        resetState();
        return result;
    }

    private AuraWindowResizeGeometry.Result geometryResult(PointerEvent event) {
        return AuraWindowResizeGeometry.resize(mSnapshot, mRegion, mGrabX, mGrabY, event.rawX,
                event.rawY, mGeometryConfig);
    }

    private Result cancelWithResize(AuraWindowResizeGeometry.Result resize) {
        final Snapshot snapshot = mSnapshot;
        final Region region = mRegion;
        resetState();
        return Result.restore(snapshot, region, false, resize);
    }

    private Result cancelInternal(boolean canceledPrevious) {
        final Snapshot snapshot = mSnapshot;
        final Region region = mRegion;
        final AuraWindowResizeGeometry.Result resize = region != null && region.isCorner()
                ? AuraWindowResizeGeometry.restore(snapshot, region, mGeometryConfig) : null;
        resetState();
        return Result.restore(snapshot, region, canceledPrevious, resize);
    }

    private void resetState() {
        mTracking = false;
        mRegion = null;
        mSnapshot = null;
        mActivePointerId = 0;
        mDownTimeMillis = 0L;
        mGrabX = 0f;
        mGrabY = 0f;
        mDirection = null;
    }
}
