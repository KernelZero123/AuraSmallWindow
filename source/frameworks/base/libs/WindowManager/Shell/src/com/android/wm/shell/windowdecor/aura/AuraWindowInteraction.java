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

/**
 * Immutable interaction protocol for the Aura small window bottom bar and corner resize.
 *
 * <p>This is the contract GLM's views produce and DeepSeek's recognizer consumes. It is
 * deliberately free of {@code android.*} and {@code java.awt} dependencies so that it can be
 * compiled and exercised with a plain JDK; the Shell adapter (GLM) converts
 * {@code MotionEvent}/display data into these objects.
 *
 * <p>Rules that the whole package depends on:
 * <ul>
 *   <li>{@link Region} is decided by the decoration view's hit test. The recognizer never infers
 *       a region and never treats the whole window as the bottom bar. Corner regions can never
 *       produce a dismiss/fullscreen intent.</li>
 *   <li>{@link PointerEvent} carries raw screen pixels ({@code rawX}/{@code rawY}), milliseconds
 *       ({@code eventTimeMillis}), the active pointer id and the event's pointer count. Drag
 *       deltas are never divided by scale.</li>
 *   <li>{@link Snapshot} is the operation snapshot latched at {@code DOWN}: real screen
 *       {@code visibleRect} (left/top are never multiplied by scale), the fixed logical canvas
 *       size, the actual scale, the display density, the safe rect, the fullscreen target and
 *       the allowed scale interval.</li>
 *   <li>{@link Result} carries scale, visible rect, intent and progress. Producing it performs
 *       no animation, removes no task and changes no windowing mode; Codex owns the commit.</li>
 *   <li>dp is converted to pixels exactly once, when a threshold/config object is built
 *       ({@link #dpToPx}). Algorithms only ever see pixels.</li>
 * </ul>
 *
 * <p>Open for Codex to freeze or adjust: the extra {@code fullscreenRect} and
 * {@code pointerCount} fields (needed for the fullscreen preview target and the multi-finger
 * rule) are proposed by this task, see DEEPSEEK_SMALL_WINDOW_V2_RESULT.md.
 */
public final class AuraWindowInteraction {

    /** {@code density = densityDpi / 160}; 320dpi on the target device gives 2.0. */
    public static final float DENSITY_DPI_BASE = 160f;

    /** Initial minimum physical window width in dp (plan P2). */
    public static final float DEFAULT_MIN_PHYSICAL_WIDTH_DP = 220f;

    /** Initial upper scale bound (plan P2); the safe area may lower it further. */
    public static final float DEFAULT_MAX_SCALE = 0.95f;

    /** Initial bottom bar commit threshold in dp (plan P3). */
    public static final float DEFAULT_COMMIT_DISTANCE_DP = 48f;

    /** Initial bottom bar fling threshold in dp/s (plan P3). */
    public static final float DEFAULT_FLING_VELOCITY_DP_PER_SEC = 800f;

    /** Minimum travel for a fling gesture to be accepted, in dp (plan P3). */
    public static final float DEFAULT_MIN_FLING_DISPLACEMENT_DP = 16f;

    /** Distance that maps a bottom bar drag to {@link Result#progress} 1.0. */
    public static final float DEFAULT_PROGRESS_DISTANCE_DP = DEFAULT_COMMIT_DISTANCE_DP;

    /** Upper bound for the follow-finger translation of the dismiss preview, in dp. */
    public static final float DEFAULT_DISMISS_PREVIEW_TRAVEL_DP =
            2f * DEFAULT_COMMIT_DISTANCE_DP;

    private AuraWindowInteraction() {
    }

    /** {@code densityDpi / 160}. */
    public static float densityFromDpi(int densityDpi) {
        return densityDpi / DENSITY_DPI_BASE;
    }

    /** The single dp-to-pixel conversion point; thresholds are built with it, nothing else. */
    public static float dpToPx(float dp, float density) {
        return dp * density;
    }

    /** Fraction of the drag mapped onto the resize diagonal; used by the geometry. */
    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Hit-test region that owns a gesture; decided by the decoration view, never by us. */
    public enum Region {
        /** Bottom centre bar: swipe up dismisses, swipe down goes fullscreen. */
        BOTTOM,
        /** Bottom left corner: uniform resize keeping the top-right corner anchored. */
        LOWER_LEFT,
        /** Bottom right corner: uniform resize keeping the top-left corner anchored. */
        LOWER_RIGHT;

        public boolean isBottom() {
            return this == BOTTOM;
        }

        public boolean isCorner() {
            return this != BOTTOM;
        }
    }

    /** Event phase, mapped 1:1 from {@code MotionEvent} actions. */
    public enum Phase {
        /** {@code ACTION_DOWN}: starts a gesture and latches the operation snapshot. */
        DOWN,
        /** {@code ACTION_MOVE}. */
        MOVE,
        /** {@code ACTION_UP}: the only phase that can commit. */
        UP,
        /** {@code ACTION_CANCEL}, pointer loss or any event the adapter cannot map. */
        CANCEL
    }

    /**
     * What the caller should do with a {@link Result}. Preview intents are visual only; commit
     * intents are the request Codex turns into a task/transition action; CANCEL means the
     * geometry must be restored to the operation snapshot.
     */
    public enum Intent {
        /** Nothing to do yet (tracking below slop, or the gesture is idle). */
        NONE,
        /** Bottom bar is being dragged up: preview of closing the task. */
        PREVIEW_DISMISS,
        /** Bottom bar is being dragged down: preview of the fullscreen transition. */
        PREVIEW_FULLSCREEN,
        /** Corner is being dragged: preview of the new uniform size. */
        PREVIEW_RESIZE,
        /** Released past threshold going up: dismiss may be committed by Codex. */
        COMMIT_DISMISS,
        /** Released past threshold going down: fullscreen may be committed by Codex. */
        COMMIT_FULLSCREEN,
        /** Released past slop after a corner drag: new scale/bounds may be committed. */
        COMMIT_RESIZE,
        /** Tap, lateral move, cancel, pointer loss, multi-finger or no threshold: restore. */
        CANCEL;

        public boolean isPreview() {
            return this == PREVIEW_DISMISS || this == PREVIEW_FULLSCREEN
                    || this == PREVIEW_RESIZE;
        }

        public boolean isCommit() {
            return this == COMMIT_DISMISS || this == COMMIT_FULLSCREEN
                    || this == COMMIT_RESIZE;
        }

        public boolean isDismiss() {
            return this == PREVIEW_DISMISS || this == COMMIT_DISMISS;
        }

        public boolean isFullscreen() {
            return this == PREVIEW_FULLSCREEN || this == COMMIT_FULLSCREEN;
        }

        public boolean isResize() {
            return this == PREVIEW_RESIZE || this == COMMIT_RESIZE;
        }
    }

    /**
     * Immutable screen-space rectangle in pixels: real screen left/top plus a size. It is never
     * produced by multiplying all four coordinates by a scale.
     */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public static Rect of(int left, int top, int right, int bottom) {
            return new Rect(left, top, right, bottom);
        }

        /** Empty rect at the origin, used as a "no target" placeholder. */
        public static Rect empty() {
            return new Rect(0, 0, 0, 0);
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public Rect offset(int dx, int dy) {
            return new Rect(left + dx, top + dy, right + dx, bottom + dy);
        }

        /** Linear interpolation of all four edges; only used for the fullscreen preview. */
        public Rect lerpTo(Rect target, float fraction) {
            final float f = clamp(fraction, 0f, 1f);
            return new Rect(
                    Math.round(left + (target.left - left) * f),
                    Math.round(top + (target.top - top) * f),
                    Math.round(right + (target.right - right) * f),
                    Math.round(bottom + (target.bottom - bottom) * f));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Rect)) return false;
            final Rect other = (Rect) o;
            return left == other.left && top == other.top && right == other.right
                    && bottom == other.bottom;
        }

        @Override
        public int hashCode() {
            int result = left;
            result = 31 * result + top;
            result = 31 * result + right;
            result = 31 * result + bottom;
            return result;
        }

        @Override
        public String toString() {
            return "Rect(" + left + "," + top + "-" + right + "," + bottom + ")";
        }
    }

    /**
     * One pointer sample in screen pixels.
     *
     * <p>{@code pointerCount} mirrors {@code MotionEvent.getPointerCount()}: how many pointers
     * the event itself reports. Any value greater than one while a gesture is active means
     * multi-finger interference and cancels the gesture.
     */
    public static final class PointerEvent {
        public final Phase phase;
        public final long eventTimeMillis;
        public final int pointerId;
        public final int pointerCount;
        public final float rawX;
        public final float rawY;

        public PointerEvent(Phase phase, long eventTimeMillis, int pointerId, int pointerCount,
                float rawX, float rawY) {
            this.phase = phase;
            this.eventTimeMillis = eventTimeMillis;
            this.pointerId = pointerId;
            this.pointerCount = pointerCount;
            this.rawX = rawX;
            this.rawY = rawY;
        }

        public static PointerEvent down(long timeMillis, int pointerId, float rawX, float rawY) {
            return new PointerEvent(Phase.DOWN, timeMillis, pointerId, 1, rawX, rawY);
        }

        public static PointerEvent move(long timeMillis, int pointerId, float rawX, float rawY) {
            return new PointerEvent(Phase.MOVE, timeMillis, pointerId, 1, rawX, rawY);
        }

        public static PointerEvent up(long timeMillis, int pointerId, float rawX, float rawY) {
            return new PointerEvent(Phase.UP, timeMillis, pointerId, 1, rawX, rawY);
        }

        public static PointerEvent cancel(long timeMillis, int pointerId, float rawX, float rawY) {
            return new PointerEvent(Phase.CANCEL, timeMillis, pointerId, 1, rawX, rawY);
        }

        /** Copy of this event carrying an explicit pointer count (multi-finger tests/adapters). */
        public PointerEvent withPointerCount(int count) {
            return new PointerEvent(phase, eventTimeMillis, pointerId, count, rawX, rawY);
        }

        /** Copy of this event with another phase, keeping time, pointer and position. */
        public PointerEvent withPhase(Phase newPhase) {
            return new PointerEvent(newPhase, eventTimeMillis, pointerId, pointerCount, rawX,
                    rawY);
        }

        @Override
        public String toString() {
            return "PointerEvent(" + phase + " t=" + eventTimeMillis + " id=" + pointerId
                    + " n=" + pointerCount + " raw=" + rawX + "," + rawY + ")";
        }
    }

    /**
     * Operation snapshot latched at {@code DOWN}. Everything the algorithm needs is here; the
     * geometry never re-reads the display or the task while a gesture is in flight.
     *
     * <p>{@code visibleRect} keeps the real screen left/top and its size is
     * {@code logical size * scale}. The constructor intentionally does not validate
     * contradictions (a rect partly outside the safe rect, a scale outside the allowed interval,
     * a non-positive logical size): those are legal inputs that must degrade explicitly, not
     * throw, so the recognizer can always answer with a legal rect.
     */
    public static final class Snapshot {
        /** Visible window rect in screen pixels. */
        public final Rect visibleRect;
        /** Fixed logical canvas width in pixels (never changed per frame by resizing). */
        public final int logicalWidth;
        /** Fixed logical canvas height in pixels. */
        public final int logicalHeight;
        /** Actual scale currently applied to the task. */
        public final float scale;
        /** Display density, {@code densityDpi / 160}. */
        public final float density;
        /** Area the scaled window should stay inside, in screen pixels. */
        public final Rect safeRect;
        /** Target rect of the fullscreen transition preview, in screen pixels. */
        public final Rect fullscreenRect;
        /** Lower bound of the allowed scale interval, inclusive. */
        public final float minScale;
        /** Upper bound of the allowed scale interval, inclusive. */
        public final float maxScale;

        public Snapshot(Rect visibleRect, int logicalWidth, int logicalHeight, float scale,
                float density, Rect safeRect, Rect fullscreenRect, float minScale,
                float maxScale) {
            if (visibleRect == null || safeRect == null || fullscreenRect == null) {
                throw new IllegalArgumentException("rects must not be null");
            }
            if (!(density > 0f)) {
                throw new IllegalArgumentException("density must be > 0, was " + density);
            }
            this.visibleRect = visibleRect;
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
            this.scale = scale;
            this.density = density;
            this.safeRect = safeRect;
            this.fullscreenRect = fullscreenRect;
            this.minScale = minScale;
            this.maxScale = maxScale;
        }

        /**
         * Typical snapshot: the safe rect is also the fullscreen target, the scale interval is
         * the initial plan interval (min physical width 220dp, max scale 0.95).
         */
        public static Snapshot of(Rect visibleRect, int logicalWidth, int logicalHeight,
                float scale, float density, Rect safeRect) {
            final float minWidthScale = logicalWidth > 0
                    ? dpToPx(DEFAULT_MIN_PHYSICAL_WIDTH_DP, density) / logicalWidth
                    : 0f;
            return new Snapshot(visibleRect, logicalWidth, logicalHeight, scale, density,
                    safeRect, safeRect, minWidthScale, DEFAULT_MAX_SCALE);
        }

        /** Same snapshot with another visible rect (used to model a moved window). */
        public Snapshot withVisibleRect(Rect rect) {
            return new Snapshot(rect, logicalWidth, logicalHeight, scale, density, safeRect,
                    fullscreenRect, minScale, maxScale);
        }

        /** Same snapshot with another actual scale. */
        public Snapshot withScale(float newScale) {
            return new Snapshot(visibleRect, logicalWidth, logicalHeight, newScale, density,
                    safeRect, fullscreenRect, minScale, maxScale);
        }

        @Override
        public String toString() {
            return "Snapshot(visible=" + visibleRect + " logical=" + logicalWidth + "x"
                    + logicalHeight + " scale=" + scale + " density=" + density + " safe="
                    + safeRect + " fullscreen=" + fullscreenRect + " interval=[" + minScale + ","
                    + maxScale + "])";
        }
    }

    /**
     * Outcome of a single event. Immutable, so it is safe to keep as the "restore" reference.
     */
    public static final class Result {
        public final Intent intent;
        /** Region that owns the gesture; the caller's hit test result, echoed back. */
        public final Region region;
        /** Scale the decoration should render right now. */
        public final float scale;
        /** Visible rect the decoration should render right now. */
        public final Rect visibleRect;
        /** Normalised 0..1 progress of the current preview. */
        public final float progress;
        /** True only for {@code COMMIT_*} intents. */
        public final boolean committed;
        /** True when the geometry equals the operation snapshot (tap/lateral/cancel/no-op). */
        public final boolean restored;
        /** True while the recognizer still owns the gesture and expects more events. */
        public final boolean active;
        /** True when this event discarded a still-active previous gesture (rapid re-entry). */
        public final boolean canceledPrevious;
        /** Resize detail for corner regions; null for bottom bar and idle results. */
        public final AuraWindowResizeGeometry.Result resize;

        Result(Intent intent, Region region, float scale, Rect visibleRect, float progress,
                boolean committed, boolean restored, boolean active, boolean canceledPrevious,
                AuraWindowResizeGeometry.Result resize) {
            this.intent = intent;
            this.region = region;
            this.scale = scale;
            this.visibleRect = visibleRect;
            this.progress = progress;
            this.committed = committed;
            this.restored = restored;
            this.active = active;
            this.canceledPrevious = canceledPrevious;
            this.resize = resize;
        }

        /** A trusted replacement command uses the exit animation without simulated input. */
        public static Result dismissForReplacement(Snapshot snapshot) {
            return new Result(Intent.COMMIT_DISMISS, Region.BOTTOM, snapshot.scale,
                    snapshot.visibleRect, 1f, true, false, false, false, null);
        }

        /** Idle/no-op result describing the untouched snapshot. */
        static Result idle(Snapshot snapshot, Region region, boolean canceledPrevious,
                AuraWindowResizeGeometry.Result resize) {
            return new Result(Intent.NONE, region, snapshot.scale, snapshot.visibleRect, 0f, false,
                    true, false, canceledPrevious, resize);
        }

        /** Restore result: the caller must put back the operation snapshot geometry. */
        static Result restore(Snapshot snapshot, Region region, boolean canceledPrevious,
                AuraWindowResizeGeometry.Result resize) {
            return new Result(Intent.CANCEL, region, snapshot.scale, snapshot.visibleRect, 0f,
                    false, true, false, canceledPrevious, resize);
        }

        @Override
        public String toString() {
            return "Result(" + intent + " region=" + region + " scale=" + scale + " rect="
                    + visibleRect + " progress=" + progress + " committed=" + committed
                    + " restored=" + restored + " active=" + active + " canceledPrevious="
                    + canceledPrevious + (resize == null ? "" : " resize=" + resize) + ")";
        }
    }
}
