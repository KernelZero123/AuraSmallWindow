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

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;

/**
 * Uniform-scale corner resize geometry for the Aura small window.
 *
 * <p>The logical canvas ({@code logicalWidth x logicalHeight}) never changes; the resize only
 * changes the scale and therefore the visible rect. The anchored corner is exactly preserved:
 *
 * <ul>
 *   <li>{@code LOWER_RIGHT} drag keeps the top-left corner: the visible rect stays
 *       {@code (left, top, left + w*s, top + h*s)}.</li>
 *   <li>{@code LOWER_LEFT} drag keeps the top-right corner: the visible rect stays
 *       {@code (right - w*s, top, right, top + h*s)}.</li>
 * </ul>
 *
 * <p>The pointer is projected orthogonally onto the fixed aspect-ratio diagonal through the
 * anchor. With {@code d = (w, h)} and {@code p} the pointer, the projection parameter is
 * {@code t(p) = ((p - anchor) . d) / (d . d)}; a pointer exactly on the corner of scale
 * {@code s} has {@code t(p) = s}. The reported scale is the <em>delta</em> mapping
 * {@code s0 + (t(p) - t(grab))}, which equals the absolute projection whenever the gesture
 * started on the diagonal and additionally stays jump-free when the finger grabbed slightly
 * inside or outside the corner. Movement perpendicular to the diagonal never changes the
 * scale.
 *
 * <p>Every limit is applied as a single scalar clamp on the scale, and the pixel size is then
 * derived from the clamped scale. Width and height are never clipped independently, so the
 * aspect ratio of the logical canvas survives min/max/safe-area clamping.
 *
 * <p>All numbers are screen pixels. The class is pure and stateless: it never animates, never
 * touches a task and never mutates the snapshot.
 */
public final class AuraWindowResizeGeometry {

    private static final float EPSILON = 1e-6f;

    private AuraWindowResizeGeometry() {
    }

    /** Corner of the visible rect that resize must not move. */
    public enum Anchor {
        /** {@code LOWER_RIGHT} drag anchors the top-left corner. */
        TOP_LEFT,
        /** {@code LOWER_LEFT} drag anchors the top-right corner. */
        TOP_RIGHT
    }

    /** Outcome of a geometry request. */
    public enum Status {
        /** The requested scale was inside every limit. */
        OK,
        /** A limit (or an already-illegal snapshot scale) changed the requested scale. */
        CLAMPED,
        /** No legal size exists: the snapshot geometry is returned untouched. */
        UNAVAILABLE
    }

    /** Which constraint produced a bound, or why a resize is unavailable. */
    public enum LimitReason {
        /** Nothing was clamped. */
        NONE,
        /** Lower bound comes from the configured minimum physical width. */
        MIN_PHYSICAL_WIDTH,
        /** Lower bound comes from the snapshot's allowed interval. */
        PLATFORM_MIN,
        /** Upper bound comes from the snapshot's allowed interval (e.g. 0.95). */
        PLATFORM_MAX,
        /** Upper bound comes from the safe rect measured from the anchored corner. */
        SAFE_AREA,
        /** The snapshot scale was already outside the legal interval. */
        CURRENT_OUTSIDE_LIMITS,
        /** The safe area and the minimum size contradict each other. */
        MIN_ABOVE_MAX,
        /** The safe rect leaves no room at all in the growing direction. */
        EMPTY_SAFE_AREA,
        /** {@code logicalWidth}/{@code logicalHeight} are not positive. */
        DEGENERATE_LOGICAL_SIZE,
        /** The region is not a resize corner (bottom bar); resize does not apply. */
        UNSUPPORTED_REGION
    }

    /**
     * Resize limits owned by the caller (Codex), not hidden constants. dp is converted exactly
     * once, in {@link #Config(float, float)}.
     */
    public static final class Config {
        /** Minimum physical width in dp, as configured (plan initial value: 220dp). */
        public final float minPhysicalWidthDp;
        /** The same minimum converted to pixels with {@link #density}. */
        public final float minPhysicalWidthPx;
        /** Display density used for that conversion. */
        public final float density;

        public Config(float minPhysicalWidthDp, float density) {
            if (!(density > 0f)) {
                throw new IllegalArgumentException("density must be > 0, was " + density);
            }
            if (!(minPhysicalWidthDp >= 0f)) {
                throw new IllegalArgumentException(
                        "minPhysicalWidthDp must be >= 0, was " + minPhysicalWidthDp);
            }
            this.minPhysicalWidthDp = minPhysicalWidthDp;
            this.density = density;
            this.minPhysicalWidthPx = AuraWindowInteraction.dpToPx(minPhysicalWidthDp, density);
        }

        /** Plan defaults for a display of the given density (220dp minimum width). */
        public static Config forDensity(float density) {
            return new Config(AuraWindowInteraction.DEFAULT_MIN_PHYSICAL_WIDTH_DP, density);
        }

        /** Explicit dp minimum. */
        public static Config ofDp(float minPhysicalWidthDp, float density) {
            return new Config(minPhysicalWidthDp, density);
        }

        /** Explicit pixel minimum (kept for tests and for callers that already converted). */
        public static Config ofPixels(float minPhysicalWidthPx, float density) {
            return new Config(minPhysicalWidthPx / density, density);
        }

        @Override
        public String toString() {
            return "Config(minWidthDp=" + minPhysicalWidthDp + " minWidthPx="
                    + minPhysicalWidthPx + " density=" + density + ")";
        }
    }

    /**
     * The effective scale interval for one corner of one snapshot, with the reason for each
     * bound. {@code available == false} means the constraints contradict each other and the
     * caller must not resize at all.
     */
    public static final class Limits {
        public final float minScale;
        public final float maxScale;
        public final LimitReason minReason;
        public final LimitReason maxReason;
        public final boolean available;
        /** {@link LimitReason#NONE} when available. */
        public final LimitReason unavailableReason;
        /** True when the snapshot's own scale already lies outside [minScale, maxScale]. */
        public final boolean currentScaleOutsideLimits;
        /**
         * True when the anchored corner itself is not inside the safe rect. The anchor is still
         * never translated: such a window stays partly outside instead of us faking a legal
         * placement.
         */
        public final boolean anchorOutsideSafe;

        Limits(float minScale, float maxScale, LimitReason minReason, LimitReason maxReason,
                boolean available, LimitReason unavailableReason,
                boolean currentScaleOutsideLimits, boolean anchorOutsideSafe) {
            this.minScale = minScale;
            this.maxScale = maxScale;
            this.minReason = minReason;
            this.maxReason = maxReason;
            this.available = available;
            this.unavailableReason = unavailableReason;
            this.currentScaleOutsideLimits = currentScaleOutsideLimits;
            this.anchorOutsideSafe = anchorOutsideSafe;
        }

        @Override
        public String toString() {
            return "Limits([" + minScale + "(" + minReason + ")," + maxScale + "(" + maxReason
                    + ")] available=" + available + " unavailableReason=" + unavailableReason
                    + " currentOutside=" + currentScaleOutsideLimits + " anchorOutsideSafe="
                    + anchorOutsideSafe + ")";
        }
    }

    /** Geometry answer for one resize sample or for restoring the snapshot. */
    public static final class Result {
        public final Status status;
        /** Binding constraint when {@link Status#CLAMPED}, otherwise {@code NONE}. */
        public final LimitReason limitReason;
        /** Scale to render; equals the snapshot scale when {@link Status#UNAVAILABLE}. */
        public final float scale;
        /** Visible rect to render; equals the snapshot rect when {@link Status#UNAVAILABLE}. */
        public final Rect visibleRect;
        /** Anchored corner, or null for a non-corner region. */
        public final Anchor anchor;
        /** Scale requested before clamping (diagnostics and tests). */
        public final float projectedScale;
        /** Interval-normalised position of {@link #scale} inside the effective interval. */
        public final float progress;
        public final float minScale;
        public final float maxScale;
        /** False only when no legal size exists. */
        public final boolean resizable;
        public final boolean atMin;
        public final boolean atMax;
        /** Mirrors {@link Limits#currentScaleOutsideLimits}. */
        public final boolean currentScaleOutsideLimits;
        /** Mirrors {@link Limits#anchorOutsideSafe}. */
        public final boolean anchorOutsideSafe;

        Result(Status status, LimitReason limitReason, float scale, Rect visibleRect, Anchor anchor,
                float projectedScale, float progress, float minScale, float maxScale,
                boolean resizable, boolean atMin, boolean atMax, boolean currentScaleOutsideLimits,
                boolean anchorOutsideSafe) {
            this.status = status;
            this.limitReason = limitReason;
            this.scale = scale;
            this.visibleRect = visibleRect;
            this.anchor = anchor;
            this.projectedScale = projectedScale;
            this.progress = progress;
            this.minScale = minScale;
            this.maxScale = maxScale;
            this.resizable = resizable;
            this.atMin = atMin;
            this.atMax = atMax;
            this.currentScaleOutsideLimits = currentScaleOutsideLimits;
            this.anchorOutsideSafe = anchorOutsideSafe;
        }

        static Result unavailable(Snapshot snapshot, Region region, Limits limits) {
            return new Result(Status.UNAVAILABLE, limits.unavailableReason, snapshot.scale,
                    snapshot.visibleRect, region.isCorner() ? anchorFor(region) : null,
                    snapshot.scale, 0f, limits.minScale, limits.maxScale, false, false, false,
                    limits.currentScaleOutsideLimits, limits.anchorOutsideSafe);
        }

        @Override
        public String toString() {
            return "ResizeResult(" + status + " reason=" + limitReason + " scale=" + scale
                    + " rect=" + visibleRect + " anchor=" + anchor + " projected="
                    + projectedScale + " progress=" + progress + " interval=[" + minScale + ","
                    + maxScale + "] resizable=" + resizable + " atMin=" + atMin + " atMax="
                    + atMax + " currentOutside=" + currentScaleOutsideLimits + " anchorOutside="
                    + anchorOutsideSafe + ")";
        }
    }

    /** Anchor corner for a resize region; throws for the bottom bar. */
    public static Anchor anchorFor(Region region) {
        if (region == Region.LOWER_RIGHT) {
            return Anchor.TOP_LEFT;
        }
        if (region == Region.LOWER_LEFT) {
            return Anchor.TOP_RIGHT;
        }
        throw new IllegalArgumentException("region is not a resize corner: " + region);
    }

    /**
     * Projection parameter of a raw screen point onto the aspect-ratio diagonal through the
     * anchor. A pointer exactly on the corner of scale {@code s} yields {@code s}; perpendicular
     * offsets do not change the value.
     */
    public static float projectionParameter(Snapshot snapshot, Region region, float rawX,
            float rawY) {
        final double w = snapshot.logicalWidth;
        final double h = snapshot.logicalHeight;
        if (!(w > 0d) || !(h > 0d)) {
            // No meaningful diagonal: degrade to 0 instead of dividing by zero or reporting a
            // meaningless value. resize() rejects such snapshots before it gets here.
            return 0f;
        }
        final double denominator = w * w + h * h;
        final double dx;
        final double dy;
        if (region == Region.LOWER_RIGHT) {
            dx = rawX - snapshot.visibleRect.left;
            dy = rawY - snapshot.visibleRect.top;
        } else if (region == Region.LOWER_LEFT) {
            dx = snapshot.visibleRect.right - rawX;
            dy = rawY - snapshot.visibleRect.top;
        } else {
            throw new IllegalArgumentException("region is not a resize corner: " + region);
        }
        return (float) ((dx * w + dy * h) / denominator);
    }

    /**
     * Scale requested by the drag: the snapshot scale plus the change of the diagonal
     * projection between the grab point and the current point. Unclamped, may be any value.
     */
    public static float projectedScale(Snapshot snapshot, Region region, float grabX, float grabY,
            float rawX, float rawY) {
        final float from = projectionParameter(snapshot, region, grabX, grabY);
        final float to = projectionParameter(snapshot, region, rawX, rawY);
        return snapshot.scale + (to - from);
    }

    /** Visible rect for a scale, anchored exactly, with both sides derived from that scale. */
    public static Rect rectForScale(Snapshot snapshot, Region region, float scale) {
        final int width = Math.round(snapshot.logicalWidth * scale);
        final int height = Math.round(snapshot.logicalHeight * scale);
        if (region == Region.LOWER_RIGHT) {
            return new Rect(snapshot.visibleRect.left, snapshot.visibleRect.top,
                    snapshot.visibleRect.left + width, snapshot.visibleRect.top + height);
        }
        if (region == Region.LOWER_LEFT) {
            return new Rect(snapshot.visibleRect.right - width, snapshot.visibleRect.top,
                    snapshot.visibleRect.right, snapshot.visibleRect.top + height);
        }
        throw new IllegalArgumentException("region is not a resize corner: " + region);
    }

    /**
     * Effective interval for this corner.
     *
     * <p>Lower bound: {@code max(snapshot.minScale, minPhysicalWidthPx / logicalWidth)}.
     * Upper bound: {@code min(snapshot.maxScale, safeLimit)} where {@code safeLimit} is how far
     * the window may grow from the anchored corner before its moving edges leave the safe rect.
     * Both are scalars, so clamping can never change the aspect ratio.
     */
    public static Limits limits(Snapshot snapshot, Region region, Config config) {
        if (!region.isCorner()) {
            return new Limits(snapshot.minScale, snapshot.maxScale, LimitReason.PLATFORM_MIN,
                    LimitReason.PLATFORM_MAX, false, LimitReason.UNSUPPORTED_REGION, false, false);
        }
        final int logicalWidth = snapshot.logicalWidth;
        final int logicalHeight = snapshot.logicalHeight;
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            return new Limits(snapshot.minScale, snapshot.maxScale, LimitReason.PLATFORM_MIN,
                    LimitReason.PLATFORM_MAX, false, LimitReason.DEGENERATE_LOGICAL_SIZE, false,
                    false);
        }
        final float minWidthScale = config.minPhysicalWidthPx / logicalWidth;
        final float minScale;
        final LimitReason minReason;
        if (minWidthScale >= snapshot.minScale) {
            minScale = minWidthScale;
            minReason = LimitReason.MIN_PHYSICAL_WIDTH;
        } else {
            minScale = snapshot.minScale;
            minReason = LimitReason.PLATFORM_MIN;
        }

        final Rect safe = snapshot.safeRect;
        final Rect visible = snapshot.visibleRect;
        final float safeScaleWidth;
        final float safeScaleHeight;
        final boolean anchorOutsideSafe;
        if (region == Region.LOWER_RIGHT) {
            safeScaleWidth = (safe.right - visible.left) / (float) logicalWidth;
            safeScaleHeight = (safe.bottom - visible.top) / (float) logicalHeight;
            anchorOutsideSafe = !contains(safe, visible.left, visible.top);
        } else {
            safeScaleWidth = (visible.right - safe.left) / (float) logicalWidth;
            safeScaleHeight = (safe.bottom - visible.top) / (float) logicalHeight;
            anchorOutsideSafe = !contains(safe, visible.right, visible.top);
        }
        final float safeMax = Math.min(safeScaleWidth, safeScaleHeight);
        if (!(safeMax > 0f)) {
            return new Limits(minScale, safeMax, minReason, LimitReason.SAFE_AREA, false,
                    LimitReason.EMPTY_SAFE_AREA, isOutside(snapshot.scale, minScale, safeMax),
                    anchorOutsideSafe);
        }
        final float maxScale;
        final LimitReason maxReason;
        if (safeMax <= snapshot.maxScale) {
            maxScale = safeMax;
            maxReason = LimitReason.SAFE_AREA;
        } else {
            maxScale = snapshot.maxScale;
            maxReason = LimitReason.PLATFORM_MAX;
        }
        if (maxScale < minScale) {
            return new Limits(minScale, maxScale, minReason, maxReason, false,
                    LimitReason.MIN_ABOVE_MAX, isOutside(snapshot.scale, minScale, maxScale),
                    anchorOutsideSafe);
        }
        return new Limits(minScale, maxScale, minReason, maxReason, true, LimitReason.NONE,
                isOutside(snapshot.scale, minScale, maxScale), anchorOutsideSafe);
    }

    /**
     * Preview/commit geometry for one resize sample. {@code grabX}/{@code grabY} are the raw
     * {@code DOWN} coordinates and stay the reference for the whole gesture, so an uncommitted
     * drag can never pollute the snapshot.
     */
    public static Result resize(Snapshot snapshot, Region region, float grabX, float grabY,
            float rawX, float rawY, Config config) {
        final Limits limits = limits(snapshot, region, config);
        if (!limits.available) {
            return Result.unavailable(snapshot, region, limits);
        }
        final float projected = projectedScale(snapshot, region, grabX, grabY, rawX, rawY);
        final float scale = AuraWindowInteraction.clamp(projected, limits.minScale,
                limits.maxScale);
        return build(snapshot, region, limits, scale, projected);
    }

    /**
     * Result describing the untouched snapshot, used for tap/cancel so the caller can restore
     * exactly. Nothing is clamped here: the status only reports whether the snapshot's own scale
     * already lies outside the legal interval.
     */
    public static Result restore(Snapshot snapshot, Region region, Config config) {
        final Limits limits = limits(snapshot, region, config);
        if (!limits.available) {
            return Result.unavailable(snapshot, region, limits);
        }
        final boolean outside = limits.currentScaleOutsideLimits;
        return create(snapshot, region, limits, snapshot.scale, snapshot.scale,
                outside ? Status.CLAMPED : Status.OK,
                outside ? LimitReason.CURRENT_OUTSIDE_LIMITS : LimitReason.NONE);
    }

    private static Result build(Snapshot snapshot, Region region, Limits limits, float scale,
            float projected) {
        final Status status;
        final LimitReason reason;
        // The binding constraint is decided by which bound the *requested* scale crossed, not by
        // the direction the clamped value moved: clamping up means the lower bound applied.
        if (projected < limits.minScale - EPSILON) {
            status = Status.CLAMPED;
            reason = limits.minReason;
        } else if (projected > limits.maxScale + EPSILON) {
            status = Status.CLAMPED;
            reason = limits.maxReason;
        } else if (limits.currentScaleOutsideLimits) {
            status = Status.CLAMPED;
            reason = LimitReason.CURRENT_OUTSIDE_LIMITS;
        } else {
            status = Status.OK;
            reason = LimitReason.NONE;
        }
        return create(snapshot, region, limits, scale, projected, status, reason);
    }

    private static Result create(Snapshot snapshot, Region region, Limits limits, float scale,
            float projected, Status status, LimitReason reason) {
        final float span = limits.maxScale - limits.minScale;
        final float progress = span > EPSILON
                ? AuraWindowInteraction.clamp((scale - limits.minScale) / span, 0f, 1f)
                : 0f;
        return new Result(status, reason, scale, rectForScale(snapshot, region, scale),
                anchorFor(region), projected, progress, limits.minScale, limits.maxScale, true,
                scale <= limits.minScale + EPSILON, scale >= limits.maxScale - EPSILON,
                limits.currentScaleOutsideLimits, limits.anchorOutsideSafe);
    }

    private static boolean contains(Rect rect, int x, int y) {
        return x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    private static boolean isOutside(float value, float min, float max) {
        return value < min - EPSILON || value > max + EPSILON;
    }
}
