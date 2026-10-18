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

import static com.android.wm.shell.windowdecor.aura.ProbeCheck.check;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equal;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equalFloat;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equalInt;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.throwsIllegalArgument;

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Anchor;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.LimitReason;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Limits;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Status;

/**
 * Resize geometry invariants, checked against externally derived expectations: the diagonal
 * projection, the anchored corner, the aspect ratio, left/right symmetry and every clamp.
 */
final class ResizeGeometryTests {

    private ResizeGeometryTests() {
    }

    /**
     * A pointer placed exactly where the dragged corner would be for scale {@code s} must yield
     * exactly {@code s} and exactly the same rect, for every density and both corners. This is
     * the definition of the diagonal projection, checked without reusing the implementation.
     */
    static void diagonalProjectionIsExact() {
        final float[] scales = {0.61f, 0.74f, 0.95f};
        final float[] densities = {1.0f, 2.0f, 2.75f, 3.0f};
        for (float density : densities) {
            for (Region region : new Region[] {Region.LOWER_RIGHT, Region.LOWER_LEFT}) {
                for (float scale : scales) {
                    final int anchorX = region == Region.LOWER_LEFT ? 800 : 0;
                    final int anchorY = 0;
                    final Rect visible = Fixtures.visibleFor(region, anchorX, anchorY, 720, 1280,
                            scale);
                    final Snapshot snapshot = Fixtures.snapshot(visible, 720, 1280, scale, density,
                            Rect.of(0, 0, 800, 1600), 0f, 0.95f);
                    final float[] corner = Fixtures.cornerPoint(snapshot, region, scale);
                    final Result result = AuraWindowResizeGeometry.resize(snapshot, region,
                            corner[0], corner[1], corner[0], corner[1], Fixtures.NO_MIN_WIDTH);
                    equal(Status.OK, result.status, "corner-on-diagonal must not clamp: " + result);
                    equalFloat(scale, result.scale, 1e-6f,
                            "scale for a pointer on the diagonal density=" + density + " region="
                                    + region + " scale=" + scale);
                    equal(visible, result.visibleRect,
                            "rect for a pointer on the diagonal density=" + density + " region="
                                    + region + " scale=" + scale);
                    equal(region == Region.LOWER_LEFT ? Anchor.TOP_RIGHT : Anchor.TOP_LEFT,
                            result.anchor, "anchor for " + region);
                }
            }
        }
    }

    /** Movement perpendicular to the diagonal must not change the scale at all. */
    static void perpendicularDragDoesNotResize() {
        final Snapshot snapshot = Fixtures.device();
        final float[] grab = {626f, 1159f};
        final double length = Fixtures.LOGICAL_DIAGONAL;
        final float unitX = (float) (1280d / length);
        final float unitY = (float) (-720d / length);
        for (float distance : new float[] {-200f, -50f, 50f, 200f}) {
            final float x = grab[0] + unitX * distance;
            final float y = grab[1] + unitY * distance;
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    grab[0], grab[1], x, y, Fixtures.DEVICE_GEOMETRY);
            equalFloat(0.74f, result.scale, 1e-4f,
                    "perpendicular movement " + distance + "px changed the scale: " + result);
            equal(snapshot.visibleRect, result.visibleRect,
                    "perpendicular movement must not change the rect");
            equalFloat(0.74f, result.projectedScale, 1e-4f, "projected scale stays at 0.74");
        }
    }

    /**
     * Dragging along the diagonal by (dx,dy) changes the scale by
     * {@code (dx*720 + dy*1280) / (720^2 + 1280^2)}; for (72,128) that is exactly +0.1, and the
     * dragged corner follows the finger pixel for pixel.
     */
    static void diagonalDeltaMapsLinearly() {
        final Snapshot snapshot = Fixtures.device();
        final Result up = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                626f, 1159f, 626f + 72f, 1159f + 128f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.OK, up.status, "0.84 is inside the device interval: " + up);
        equalFloat(0.84f, up.scale, 1e-4f, "(72,128) along the diagonal is +0.1 scale");
        equal(Rect.of(93, 212, 698, 1287), up.visibleRect, "corner follows the finger");
        equalInt(698, (int) (626f + 72f), "drag target x");
        equalInt(1287, (int) (1159f + 128f), "drag target y");
        equalInt(698, up.visibleRect.right, "dragged corner sits on the finger (x)");
        equalInt(1287, up.visibleRect.bottom, "dragged corner sits on the finger (y)");

        final Result down = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                626f, 1159f, 626f - 72f, 1159f - 128f, Fixtures.DEVICE_GEOMETRY);
        equalFloat(0.64f, down.scale, 1e-4f, "(-72,-128) along the diagonal is -0.1 scale");
        equal(Rect.of(93, 212, 554, 1031), down.visibleRect, "shrinking follows the finger too");
    }

    /** The two corners must be mirror images for mirror-image input. */
    static void leftRightSymmetry() {
        final Rect rightVisible = Fixtures.DEVICE_VISIBLE;
        final Rect leftVisible = Rect.of(Fixtures.DISPLAY_W - rightVisible.right, rightVisible.top,
                Fixtures.DISPLAY_W - rightVisible.left, rightVisible.bottom);
        final Snapshot rightSnapshot = new Snapshot(rightVisible, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final Snapshot leftSnapshot = new Snapshot(leftVisible, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        equal(Rect.of(94, 212, 627, 1159), leftVisible, "mirrored visible rect");

        for (float[] offset : new float[][] {{72f, 128f}, {-72f, -128f}, {400f, 700f},
                {-500f, -900f}}) {
            final Result right = AuraWindowResizeGeometry.resize(rightSnapshot, Region.LOWER_RIGHT,
                    626f, 1159f, 626f + offset[0], 1159f + offset[1], Fixtures.DEVICE_GEOMETRY);
            final Result left = AuraWindowResizeGeometry.resize(leftSnapshot, Region.LOWER_LEFT,
                    94f, 1159f, 94f - offset[0], 1159f + offset[1], Fixtures.DEVICE_GEOMETRY);
            equalFloat(right.scale, left.scale, 1e-5f,
                    "mirrored input must produce the same scale offset=" + offset[0] + ","
                            + offset[1] + " right=" + right + " left=" + left);
            final Rect mirrored = Rect.of(Fixtures.DISPLAY_W - right.visibleRect.right,
                    right.visibleRect.top, Fixtures.DISPLAY_W - right.visibleRect.left,
                    right.visibleRect.bottom);
            equal(mirrored, left.visibleRect,
                    "mirrored rect offset=" + offset[0] + "," + offset[1]);
        }
    }

    /**
     * The 220dp minimum width is a dp rule: at every density the committed width must be 220dp
     * of physical pixels, and requesting less must clamp to that floor.
     */
    static void minPhysicalWidthFloorIsDensityCorrect() {
        final float[] densities = {1.0f, 2.0f, 2.75f, 3.0f};
        for (float density : densities) {
            final Snapshot snapshot = Fixtures.snapshot(Rect.of(0, 0, 648, 1152), 720, 1280, 0.9f,
                    density, Rect.of(0, 0, 720, 1600), 0f, 0.95f);
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    648f, 1152f, 216f, 384f, AuraWindowResizeGeometry.Config.forDensity(density));
            check(result.resizable, "the 220dp floor must be reachable density=" + density);
            equal(Status.CLAMPED, result.status, "the floor clamps density=" + density);
            equal(LimitReason.MIN_PHYSICAL_WIDTH, result.limitReason,
                    "floor reason density=" + density);
            check(result.atMin, "must sit at the lower bound density=" + density);
            equalFloat(220f, result.visibleRect.width() / density, 0.01f,
                    "minimum physical width is 220dp density=" + density);
            equalFloat(220f * density, result.visibleRect.width(), 0f,
                    "minimum width in physical pixels density=" + density);
        }

        // At the target density a request for scale 0.61 lands just below the 220dp floor
        // (440/720 = 0.6111), so it must clamp up to exactly 440px = 220dp.
        final Snapshot device = Fixtures.snapshot(Rect.of(0, 0, 648, 1152), 720, 1280, 0.9f, 2f,
                Rect.of(0, 0, 720, 1600), 0f, 0.95f);
        final Result boundary = AuraWindowResizeGeometry.resize(device, Region.LOWER_RIGHT,
                648f, 1152f, 439.2f, 780.8f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.CLAMPED, boundary.status, "scale 0.61 is below the 220dp floor");
        equal(LimitReason.MIN_PHYSICAL_WIDTH, boundary.limitReason, "0.61 floor reason");
        equalFloat(440f / 720f, boundary.scale, 1e-6f, "0.61 clamps to 440/720");
        equalInt(440, boundary.visibleRect.width(), "clamped width is 440px");
        equalInt(782, boundary.visibleRect.height(), "clamped height follows the same 440/720");

        // At density 1.0 the same request is legal, which proves the floor tracks density.
        final Snapshot lowDensity = Fixtures.snapshot(Rect.of(0, 0, 648, 1152), 720, 1280, 0.9f,
                1f, Rect.of(0, 0, 720, 1600), 0f, 0.95f);
        final Result legal = AuraWindowResizeGeometry.resize(lowDensity, Region.LOWER_RIGHT,
                648f, 1152f, 439.2f, 780.8f, AuraWindowResizeGeometry.Config.forDensity(1f));
        equal(Status.OK, legal.status, "at density 1.0 scale 0.61 is above the 220dp floor");
        equalFloat(0.61f, legal.scale, 1e-6f, "density 1.0 keeps 0.61");
        equalInt(439, legal.visibleRect.width(), "density 1.0 width for 0.61");
    }

    static void everyLimitIsAppliedAsAScalarClamp() {
        // Platform maximum.
        final Snapshot maxSnapshot = new Snapshot(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.80f);
        final Result maxResult = AuraWindowResizeGeometry.resize(maxSnapshot, Region.LOWER_RIGHT,
                626f, 1159f, 777f, 1428f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.CLAMPED, maxResult.status, "0.95 request against a 0.80 cap");
        equal(LimitReason.PLATFORM_MAX, maxResult.limitReason, "platform max reason");
        equalFloat(0.80f, maxResult.scale, 1e-6f, "clamped to the platform maximum");
        equal(Rect.of(93, 212, 669, 1236), maxResult.visibleRect, "rect at scale 0.80");
        check(maxResult.atMax, "must report atMax");

        // Safe area: the moving edge must land exactly on the safe boundary.
        final Snapshot safeSnapshot = Fixtures.device();
        final Result safeResult = AuraWindowResizeGeometry.resize(safeSnapshot, Region.LOWER_RIGHT,
                626f, 1159f, 2000f, 3000f, Fixtures.DEVICE_GEOMETRY);
        equal(LimitReason.SAFE_AREA, safeResult.limitReason, "safe area reason");
        equalFloat(627f / 720f, safeResult.scale, 1e-6f, "safe-area maximum is 627/720");
        equalInt(720, safeResult.visibleRect.right, "right edge lands exactly on the safe edge");
        equalInt(1327, safeResult.visibleRect.bottom, "bottom stays inside the safe rect");
        check(safeResult.atMax, "must report atMax for the safe area");

        // Platform minimum.
        final Snapshot minSnapshot = new Snapshot(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 0.70f, 0.95f);
        final Result minResult = AuraWindowResizeGeometry.resize(minSnapshot, Region.LOWER_RIGHT,
                626f, 1159f, 0f, 0f, Fixtures.NO_MIN_WIDTH);
        equal(LimitReason.PLATFORM_MIN, minResult.limitReason, "platform min reason");
        equalFloat(0.70f, minResult.scale, 1e-6f, "clamped to the platform minimum");
        equalInt(504, minResult.visibleRect.width(), "width at 0.70");
        check(minResult.atMin, "must report atMin");

        // Contradictory interval.
        final Snapshot brokenSnapshot = new Snapshot(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 0.90f, 0.80f);
        final Result broken = AuraWindowResizeGeometry.resize(brokenSnapshot, Region.LOWER_RIGHT,
                626f, 1159f, 900f, 1500f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.UNAVAILABLE, broken.status, "min > max must be unavailable");
        equal(LimitReason.MIN_ABOVE_MAX, broken.limitReason, "min > max reason");
        check(!broken.resizable, "min > max must not be resizable");
        equal(Fixtures.DEVICE_VISIBLE, broken.visibleRect, "unavailable keeps the snapshot rect");
        equalFloat(0.74f, broken.scale, 0f, "unavailable keeps the snapshot scale");
    }

    /** Anchors, the aspect ratio and the safe area must survive every clamp. */
    static void anchorAndAspectRatioSurviveClamping() {
        final float[] scales = {0.62f, 0.74f, 0.90f};
        final float[][] offsets = {{-500f, -900f}, {-72f, -128f}, {0f, 0f}, {72f, 128f},
                {500f, 900f}, {2000f, 3500f}};
        int checked = 0;
        for (Region region : new Region[] {Region.LOWER_RIGHT, Region.LOWER_LEFT}) {
            for (float scale : scales) {
                final int anchorX = region == Region.LOWER_LEFT ? 670 : 50;
                final int anchorY = 100;
                final Rect visible = Fixtures.visibleFor(region, anchorX, anchorY, 720, 1280,
                        scale);
                final Snapshot snapshot = new Snapshot(visible, 720, 1280, scale, 2f,
                        Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
                final float grabX = region == Region.LOWER_LEFT ? visible.left : visible.right;
                final float grabY = visible.bottom;
                for (float[] offset : offsets) {
                    final Result result = AuraWindowResizeGeometry.resize(snapshot, region, grabX,
                            grabY, grabX + offset[0], grabY + offset[1],
                            Fixtures.DEVICE_GEOMETRY);
                    final Rect rect = result.visibleRect;
                    final String context = region + " scale=" + scale + " offset=" + offset[0] + ","
                            + offset[1] + " -> " + result;
                    if (!result.resizable) {
                        equal(visible, rect, "unavailable keeps the rect | " + context);
                        equalFloat(scale, result.scale, 0f,
                                "unavailable keeps the scale | " + context);
                        continue;
                    }
                    if (region == Region.LOWER_RIGHT) {
                        equalInt(anchorX, rect.left, "LOWER_RIGHT keeps left | " + context);
                        equalInt(anchorY, rect.top, "LOWER_RIGHT keeps top | " + context);
                        check(rect.right <= Fixtures.DISPLAY_W,
                                "LOWER_RIGHT stays inside the safe width | " + context);
                    } else {
                        equalInt(anchorX, rect.right, "LOWER_LEFT keeps right | " + context);
                        equalInt(anchorY, rect.top, "LOWER_LEFT keeps top | " + context);
                        check(rect.left >= 0, "LOWER_LEFT stays inside the safe width | " + context);
                    }
                    check(rect.bottom <= Fixtures.DISPLAY_H,
                            "bottom stays inside the safe rect | " + context);
                    check(rect.width() > 0 && rect.height() > 0,
                            "no degenerate output | " + context);
                    check(result.scale >= result.minScale - 1e-6f
                                    && result.scale <= result.maxScale + 1e-6f,
                            "scale inside the effective interval | " + context);
                    // Sizes must be derived from the one scalar scale (no per-axis clipping).
                    equalFloat(720f * result.scale, rect.width(), 0.51f,
                            "width comes from the scale, not from clipping | " + context);
                    equalFloat(1280f * result.scale, rect.height(), 0.51f,
                            "height comes from the scale, not from clipping | " + context);
                    final double ratio = (double) rect.width() / rect.height();
                    check(Math.abs(ratio - 720d / 1280d) < 0.002d,
                            "logical aspect ratio preserved | " + context);
                    checked++;
                }
            }
        }
        check(checked >= 24, "expected many resizable samples, got " + checked);
    }

    static void restoreReportsTheUntouchedSnapshot() {
        final Snapshot snapshot = Fixtures.device();
        final Result restore = AuraWindowResizeGeometry.restore(snapshot, Region.LOWER_RIGHT,
                Fixtures.DEVICE_GEOMETRY);
        equal(snapshot.visibleRect, restore.visibleRect, "restore returns the snapshot rect");
        equalFloat(snapshot.scale, restore.scale, 0f, "restore returns the snapshot scale");
        check(restore.resizable, "the device snapshot is resizable");
        equal(Status.OK, restore.status, "0.74 is inside the device interval");
        equalFloat(0.6111f, restore.minScale, 1e-3f, "device minimum scale");
        equalFloat(627f / 720f, restore.maxScale, 1e-6f, "device maximum scale");

        // Available interval that excludes the current scale: legal to resize, but a snap.
        final Snapshot outside = new Snapshot(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 0.80f, 0.95f);
        final Result clampedRestore = AuraWindowResizeGeometry.restore(outside, Region.LOWER_RIGHT,
                Fixtures.DEVICE_GEOMETRY);
        equal(Status.CLAMPED, clampedRestore.status, "an illegal snapshot scale must be flagged");
        equal(LimitReason.CURRENT_OUTSIDE_LIMITS, clampedRestore.limitReason,
                "current-outside reason");
        check(clampedRestore.currentScaleOutsideLimits, "currentOutside flag");
        equal(Fixtures.DEVICE_VISIBLE, clampedRestore.visibleRect,
                "restore still reports the snapshot geometry");
        check(!clampedRestore.anchorOutsideSafe, "this anchor is inside the display");

        // The anchor itself outside the safe rect: resizing stays available and never translates.
        final Snapshot shifted = Fixtures.device(Rect.of(100, 300, 700, 1500));
        final Result shiftedRestore = AuraWindowResizeGeometry.restore(shifted, Region.LOWER_RIGHT,
                Fixtures.DEVICE_GEOMETRY);
        check(shiftedRestore.anchorOutsideSafe, "an anchor outside the safe rect is reported");
        equal(Status.OK, shiftedRestore.status,
                "an outside anchor alone must not disable resizing");
        equal(Fixtures.DEVICE_VISIBLE, shiftedRestore.visibleRect,
                "the anchor is never translated to 'fix' the placement");
    }

    static void uncommittedResizeNeverPollutesTheSnapshot() {
        final Snapshot snapshot = Fixtures.device();
        final Rect before = snapshot.visibleRect;
        final float scaleBefore = snapshot.scale;
        final Result first = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                626f, 1159f, 698f, 1287f, Fixtures.DEVICE_GEOMETRY);
        // A different drag in between must not leave any state behind.
        AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT, 626f, 1159f, 100f, 100f,
                Fixtures.DEVICE_GEOMETRY);
        final Result again = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                626f, 1159f, 698f, 1287f, Fixtures.DEVICE_GEOMETRY);
        equal(first.visibleRect, again.visibleRect, "the geometry is stateless");
        equalFloat(first.scale, again.scale, 0f, "the geometry returns the same scale");
        equal(before, snapshot.visibleRect, "the snapshot rect is untouched");
        equalFloat(scaleBefore, snapshot.scale, 0f, "the snapshot scale is untouched");
        check(first.visibleRect != snapshot.visibleRect,
                "results must be new objects, never the snapshot rect itself");
        equal(Fixtures.DEVICE_VISIBLE, Fixtures.device().visibleRect, "fixtures stay immutable");

        // Cancel/restore must reproduce the snapshot exactly, not the last preview.
        AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT, 626f, 1159f, 900f, 1500f,
                Fixtures.DEVICE_GEOMETRY);
        final Result restore = AuraWindowResizeGeometry.restore(snapshot, Region.LOWER_RIGHT,
                Fixtures.DEVICE_GEOMETRY);
        equal(Fixtures.DEVICE_VISIBLE, restore.visibleRect, "restore is exact after a preview");
        equalFloat(0.74f, restore.scale, 0f, "restore scale is exact after a preview");
    }

    static void progressIsMonotonicInScale() {
        final Snapshot snapshot = Fixtures.device();
        final Limits limits = AuraWindowResizeGeometry.limits(snapshot, Region.LOWER_RIGHT,
                Fixtures.DEVICE_GEOMETRY);
        check(limits.available, "device limits are available");
        float previousScale = -1f;
        float previousProgress = -1f;
        for (float factor = 0f; factor <= 1.0001f; factor += 0.05f) {
            final float target = limits.minScale
                    + (limits.maxScale - limits.minScale) * Math.min(factor, 1f);
            final float[] corner = {(float) (720d * target) + snapshot.visibleRect.left,
                    (float) (1280d * target) + snapshot.visibleRect.top};
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    626f, 1159f, corner[0], corner[1], Fixtures.DEVICE_GEOMETRY);
            check(result.scale >= previousScale - 1e-5f,
                    "scale must grow monotonically with the drag: " + result);
            check(result.progress >= previousProgress - 1e-5f,
                    "progress must grow monotonically with the drag: " + result);
            previousScale = result.scale;
            previousProgress = result.progress;
        }
        equalFloat(1f, previousProgress, 1e-3f, "at the maximum the progress is 1");
    }

    static void unsupportedRegionAndBadArguments() {
        final Snapshot snapshot = Fixtures.device();
        final Result bottom = AuraWindowResizeGeometry.resize(snapshot, Region.BOTTOM, 360f, 1130f,
                360f, 1000f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.UNAVAILABLE, bottom.status, "the bottom bar is not a resize region");
        equal(LimitReason.UNSUPPORTED_REGION, bottom.limitReason, "unsupported region reason");
        equal(null, bottom.anchor, "no anchor for the bottom bar");
        equal(Fixtures.DEVICE_VISIBLE, bottom.visibleRect, "degrade keeps the snapshot rect");
        throwsIllegalArgument(() -> AuraWindowResizeGeometry.anchorFor(Region.BOTTOM),
                "anchorFor rejects the bottom bar");
        throwsIllegalArgument(() -> AuraWindowResizeGeometry.projectionParameter(snapshot,
                        Region.BOTTOM, 1f, 1f),
                "projectionParameter rejects the bottom bar");
        throwsIllegalArgument(() -> AuraWindowResizeGeometry.rectForScale(snapshot,
                        Region.BOTTOM, 0.5f),
                "rectForScale rejects the bottom bar");
        throwsIllegalArgument(() -> new Snapshot(null, 720, 1280, 0.74f, 2f, Fixtures.DISPLAY,
                        Fixtures.DISPLAY, 0.6f, 0.95f),
                "snapshot rejects a null rect");
        throwsIllegalArgument(() -> new Snapshot(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 0f,
                        Fixtures.DISPLAY, Fixtures.DISPLAY, 0.6f, 0.95f),
                "snapshot rejects a non-positive density");
    }
}
