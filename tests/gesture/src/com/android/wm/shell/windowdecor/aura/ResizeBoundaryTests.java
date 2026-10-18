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

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.LimitReason;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Limits;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowResizeGeometry.Status;

/**
 * Safe-area edges, degraded intervals, partly off-screen start rects, landscape/small screens and
 * an exhaustive grid that must never emit an illegal size.
 */
final class ResizeBoundaryTests {

    private ResizeBoundaryTests() {
    }

    /** Growth must stop exactly on the safe boundary, never past it. */
    static void safeAreaEdgesStopExactlyOnTheBoundary() {
        // Right edge: the rect already touches x=720, so the limit keeps right == 720.
        final Snapshot right = new Snapshot(Rect.of(187, 212, 720, 1159), 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final Result rightResult = AuraWindowResizeGeometry.resize(right, Region.LOWER_RIGHT,
                720f, 1159f, 4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        equal(LimitReason.SAFE_AREA, rightResult.limitReason, "right edge reason");
        equalInt(720, rightResult.visibleRect.right, "right edge is exactly the safe edge");
        equalInt(187, rightResult.visibleRect.left, "right-edge drag keeps the anchor");
        check(rightResult.visibleRect.bottom <= 1600, "bottom stays inside");

        // Bottom edge: the rect already touches y=1600.
        final Snapshot bottom = new Snapshot(Rect.of(93, 653, 626, 1600), 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final Result bottomResult = AuraWindowResizeGeometry.resize(bottom, Region.LOWER_RIGHT,
                626f, 1600f, 4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        equalInt(1600, bottomResult.visibleRect.bottom, "bottom edge is exactly the safe edge");
        equalInt(93, bottomResult.visibleRect.left, "bottom-edge drag keeps the anchor");

        // Right and bottom at once: both land exactly on their boundary.
        final Snapshot corner = new Snapshot(Rect.of(187, 653, 720, 1600), 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final Result cornerResult = AuraWindowResizeGeometry.resize(corner, Region.LOWER_RIGHT,
                720f, 1600f, 4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        equalInt(720, cornerResult.visibleRect.right, "corner case right");
        equalInt(1600, cornerResult.visibleRect.bottom, "corner case bottom");

        // Left edge with the mirrored corner region.
        final Snapshot left = new Snapshot(Rect.of(0, 653, 533, 1600), 720, 1280, 0.74f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final Result leftResult = AuraWindowResizeGeometry.resize(left, Region.LOWER_LEFT,
                0f, 1600f, -4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        equalInt(0, leftResult.visibleRect.left, "left edge is exactly the safe edge");
        equalInt(1600, leftResult.visibleRect.bottom, "left-edge bottom is exactly the boundary");
        equalInt(533, leftResult.visibleRect.right, "left-edge drag keeps the anchor");
    }

    /** Contradictory constraints must degrade, never invent a size. */
    static void minAboveMaxAndEmptySafeAreaDegrade() {
        final Rect[] impossibleSafes = {
                Rect.of(0, 0, 720, 600), // safe height cannot host the 220dp floor
                Rect.of(0, 0, 720, 400),
        };
        for (Rect safe : impossibleSafes) {
            final Snapshot snapshot = Fixtures.device(safe);
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    626f, 1159f, 900f, 1500f, Fixtures.DEVICE_GEOMETRY);
            equal(Status.UNAVAILABLE, result.status, "impossible safe rect must degrade: " + safe);
            equal(LimitReason.MIN_ABOVE_MAX, result.limitReason, "min > max reason: " + safe);
            check(!result.resizable, "min > max must not be resizable: " + safe);
            equal(snapshot.visibleRect, result.visibleRect, "degrade keeps the rect: " + safe);
            equalFloat(snapshot.scale, result.scale, 0f, "degrade keeps the scale: " + safe);
            equalFloat(0f, result.progress, 0f, "degrade has no progress: " + safe);
            final Limits limits = AuraWindowResizeGeometry.limits(snapshot, Region.LOWER_RIGHT,
                    Fixtures.DEVICE_GEOMETRY);
            check(!limits.available, "limits must report unavailable: " + safe);
        }

        final Rect[] emptySafes = {
                Rect.of(0, 0, 50, 1600), // nothing to the right of the anchor
                Rect.of(0, 0, 720, 200), // nothing below the anchor
                Rect.of(0, 0, 0, 0),
        };
        for (Rect safe : emptySafes) {
            final Snapshot snapshot = Fixtures.device(safe);
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    626f, 1159f, 900f, 1500f, Fixtures.DEVICE_GEOMETRY);
            equal(Status.UNAVAILABLE, result.status, "empty safe area must degrade: " + safe);
            equal(LimitReason.EMPTY_SAFE_AREA, result.limitReason,
                    "empty safe area reason: " + safe);
            equal(snapshot.visibleRect, result.visibleRect, "degrade keeps the rect: " + safe);
            check(result.visibleRect.width() > 0 && result.visibleRect.height() > 0,
                    "no degenerate rect: " + safe);
        }

        // The recognizer must surface the degrade and refuse to commit anything.
        final Snapshot snapshot = Fixtures.device(Rect.of(0, 0, 720, 600));
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        final AuraWindowInteraction.Result down = recognizer.begin(Region.LOWER_RIGHT, snapshot,
                PointerEvent.down(1000L, 1, 626f, 1159f));
        check(down.resize != null && !down.resize.resizable,
                "DOWN must report the degraded interval: " + down.resize);
        final AuraWindowInteraction.Result move = recognizer.onEvent(
                PointerEvent.move(1100L, 1, 700f, 1300f));
        equal(Intent.PREVIEW_RESIZE, move.intent, "a degraded corner still previews resize");
        check(move.resize != null && !move.resize.resizable, "preview reports the degrade");
        check(move.restored, "a degraded preview must keep the snapshot geometry");
        equal(snapshot.visibleRect, move.visibleRect, "no illegal size is emitted");
        final AuraWindowInteraction.Result up = recognizer.onEvent(
                PointerEvent.up(1200L, 1, 700f, 1300f));
        equal(Intent.CANCEL, up.intent, "a degraded resize must never commit");
        check(!up.committed, "degraded resize must not be committed");
        check(up.resize != null && !up.resize.resizable, "cancel keeps the degrade detail");
        equal(snapshot.visibleRect, up.visibleRect, "cancel restores the snapshot exactly");
    }

    static void degenerateLogicalSizesDegrade() {
        final int[][] logicals = {{0, 1280}, {720, 0}, {-720, 1280}, {720, -1280}, {0, 0}};
        for (int[] logical : logicals) {
            final Snapshot snapshot = new Snapshot(Fixtures.DEVICE_VISIBLE, logical[0], logical[1],
                    0.74f, 2f, Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
            final Result result = AuraWindowResizeGeometry.resize(snapshot, Region.LOWER_RIGHT,
                    626f, 1159f, 700f, 1300f, Fixtures.DEVICE_GEOMETRY);
            equal(Status.UNAVAILABLE, result.status,
                    "degenerate logical size must degrade: " + logical[0] + "x" + logical[1]);
            equal(LimitReason.DEGENERATE_LOGICAL_SIZE, result.limitReason,
                    "degenerate reason: " + logical[0] + "x" + logical[1]);
            equal(snapshot.visibleRect, result.visibleRect, "degrade keeps the rect");
            check(Float.isFinite(result.scale), "scale must stay finite");
            equalFloat(0f, AuraWindowResizeGeometry.projectionParameter(snapshot,
                            Region.LOWER_RIGHT, 100f, 100f), 0f,
                    "projectionParameter degrades to 0 instead of dividing by zero");
        }
    }

    /** A start rect that is already partly outside must keep its anchor and stay partly outside. */
    static void startRectPartlyOutsideKeepsTheAnchor() {
        final Rect safe = Rect.of(100, 300, 700, 1500);

        final Snapshot right = Fixtures.device(safe);
        final Result rightResult = AuraWindowResizeGeometry.resize(right, Region.LOWER_RIGHT,
                626f, 1159f, 4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        check(rightResult.anchorOutsideSafe, "the top-left anchor is outside the safe rect");
        equal(LimitReason.SAFE_AREA, rightResult.limitReason, "outside-anchor reason");
        equalInt(93, rightResult.visibleRect.left, "the anchor is never translated");
        equalInt(212, rightResult.visibleRect.top, "the anchor is never translated vertically");
        equalInt(700, rightResult.visibleRect.right, "growth stops exactly on safe.right");
        check(rightResult.visibleRect.left < safe.left,
                "the window legitimately stays partly outside; we do not fake a legal placement");
        check(rightResult.visibleRect.width() > 0 && rightResult.visibleRect.height() > 0,
                "an outside anchor still yields a positive size");

        final Snapshot left = new Snapshot(Rect.of(94, 212, 627, 1159), 720, 1280, 0.74f, 2f,
                safe, safe, 440f / 720f, 0.95f);
        final Result leftResult = AuraWindowResizeGeometry.resize(left, Region.LOWER_LEFT,
                94f, 1159f, -4000f, 4000f, Fixtures.DEVICE_GEOMETRY);
        check(leftResult.anchorOutsideSafe, "the top-right anchor is outside the safe rect");
        equalInt(627, leftResult.visibleRect.right, "the right anchor is never translated");
        equalInt(100, leftResult.visibleRect.left, "growth stops exactly on safe.left");
    }

    /** Landscape, narrow landscape safe areas and small screens. */
    static void landscapeAndSmallScreens() {
        // Landscape 1600x720 logical, 320dpi.
        final Snapshot landscape = new Snapshot(Rect.of(100, 50, 1284, 583), 1600, 720, 0.74f, 2f,
                Rect.of(0, 0, 1600, 720), Rect.of(0, 0, 1600, 720), 440f / 1600f, 0.95f);
        final Result wide = AuraWindowResizeGeometry.resize(landscape, Region.LOWER_RIGHT,
                1284f, 583f, 5000f, 5000f, Fixtures.DEVICE_GEOMETRY);
        equal(LimitReason.SAFE_AREA, wide.limitReason, "landscape is limited by the safe area");
        equalInt(720, wide.visibleRect.bottom, "landscape bottom lands exactly on the boundary");
        equalInt(100, wide.visibleRect.left, "landscape keeps the anchor");
        check(wide.visibleRect.right <= 1600, "landscape stays inside the width");

        // Narrow landscape safe area: still workable, bottom lands exactly on 500.
        final Snapshot narrow = new Snapshot(Rect.of(100, 50, 1284, 583), 1600, 720, 0.74f, 2f,
                Rect.of(0, 0, 1600, 500), Rect.of(0, 0, 1600, 500), 440f / 1600f, 0.95f);
        final Result narrowResult = AuraWindowResizeGeometry.resize(narrow, Region.LOWER_RIGHT,
                1284f, 583f, 5000f, 5000f, Fixtures.DEVICE_GEOMETRY);
        check(narrowResult.resizable, "a 500px-tall landscape safe area is still usable");
        equalInt(500, narrowResult.visibleRect.bottom, "narrow landscape stops at the boundary");
        equalFloat(0.625f, narrowResult.scale, 1e-6f, "narrow landscape limit is 450/720");

        // Too short for the 220dp floor: degrade instead of producing a tiny window.
        final Snapshot tooNarrow = new Snapshot(Rect.of(100, 50, 1284, 583), 1600, 720, 0.74f, 2f,
                Rect.of(0, 0, 1600, 100), Rect.of(0, 0, 1600, 100), 440f / 1600f, 0.95f);
        final Result degraded = AuraWindowResizeGeometry.resize(tooNarrow, Region.LOWER_RIGHT,
                1284f, 583f, 5000f, 5000f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.UNAVAILABLE, degraded.status, "a 100px landscape safe area must degrade");
        equal(LimitReason.MIN_ABOVE_MAX, degraded.limitReason, "short landscape reason");
        equal(tooNarrow.visibleRect, degraded.visibleRect, "short landscape keeps the rect");

        // Small screen, 480x800 logical at 320dpi: the 220dp floor now dominates.
        final Snapshot small = new Snapshot(Rect.of(0, 0, 456, 760), 480, 800, 0.95f, 2f,
                Rect.of(0, 0, 480, 800), Rect.of(0, 0, 480, 800), 440f / 480f, 0.95f);
        final Result fits = AuraWindowResizeGeometry.resize(small, Region.LOWER_RIGHT,
                456f, 760f, 456f, 760f, Fixtures.DEVICE_GEOMETRY);
        equal(Status.OK, fits.status, "0.95 is legal on a 480px canvas");
        equalFloat(0.95f, fits.scale, 1e-6f, "small screen keeps 0.95");

        final Result floored = AuraWindowResizeGeometry.resize(small, Region.LOWER_RIGHT,
                456f, 760f, 200f, 300f, Fixtures.DEVICE_GEOMETRY);
        equal(LimitReason.MIN_PHYSICAL_WIDTH, floored.limitReason,
                "the 220dp floor is the binding limit on a 480px canvas");
        equalFloat(440f / 480f, floored.scale, 1e-6f, "small screen floor is 440/480");
        equalInt(440, floored.visibleRect.width(), "small screen floor width is 220dp");
        check(floored.visibleRect.height() > 0, "no degenerate height");
    }

    /**
     * Exhaustive grid over regions, densities, safe rects, start scales and drag offsets. Any
     * available result must keep the anchor, stay inside the safe rect, stay inside the scale
     * interval, keep the aspect ratio and stay positive; any unavailable result must return the
     * snapshot untouched.
     */
    static void gridNeverEmitsIllegalGeometry() {
        final Region[] regions = {Region.LOWER_RIGHT, Region.LOWER_LEFT};
        final float[] densities = {1.0f, 2.0f, 2.75f, 3.0f};
        final Rect[] safes = {Fixtures.DISPLAY, Rect.of(100, 300, 700, 1500), Rect.of(0, 0, 720, 600),
                Rect.of(0, 0, 400, 400), Rect.of(50, 50, 700, 1550)};
        final float[] startScales = {0.3f, 0.61f, 0.74f, 0.9f};
        final float[][] offsets = {{-4000f, -4000f}, {-300f, -500f}, {0f, 0f}, {300f, 500f},
                {4000f, 4000f}};
        int available = 0;
        int degraded = 0;
        for (Region region : regions) {
            for (float density : densities) {
                final AuraWindowResizeGeometry.Config config =
                        AuraWindowResizeGeometry.Config.forDensity(density);
                for (Rect safe : safes) {
                    for (float startScale : startScales) {
                        final int anchorX = region == Region.LOWER_LEFT ? 700 : 20;
                        final int anchorY = 20;
                        final Rect visible = Fixtures.visibleFor(region, anchorX, anchorY, 720, 1280,
                                startScale);
                        final Snapshot snapshot = new Snapshot(visible, 720, 1280, startScale,
                                density, safe, safe, 440f / 720f, 0.95f);
                        final float grabX = region == Region.LOWER_LEFT ? visible.left
                                : visible.right;
                        final float grabY = visible.bottom;
                        for (float[] offset : offsets) {
                            final Result result = AuraWindowResizeGeometry.resize(snapshot, region,
                                    grabX, grabY, grabX + offset[0], grabY + offset[1], config);
                            final String context = region + " density=" + density + " safe=" + safe
                                    + " start=" + startScale + " offset=" + offset[0] + ","
                                    + offset[1] + " -> " + result;
                            if (!result.resizable) {
                                degraded++;
                                equal(visible, result.visibleRect,
                                        "unavailable keeps the rect | " + context);
                                equalFloat(startScale, result.scale, 0f,
                                        "unavailable keeps the scale | " + context);
                                continue;
                            }
                            available++;
                            final Rect rect = result.visibleRect;
                            check(rect.width() > 0 && rect.height() > 0,
                                    "positive size | " + context);
                            check(Float.isFinite(result.scale) && Float.isFinite(result.progress),
                                    "finite numbers | " + context);
                            check(result.scale >= result.minScale - 1e-6f
                                            && result.scale <= result.maxScale + 1e-6f,
                                    "scale inside the interval | " + context);
                            check(result.progress >= 0f && result.progress <= 1f,
                                    "progress in range | " + context);
                            if (region == Region.LOWER_RIGHT) {
                                equalInt(anchorX, rect.left, "anchor left | " + context);
                                equalInt(anchorY, rect.top, "anchor top | " + context);
                                check(rect.right <= safe.right,
                                        "right inside the safe rect | " + context);
                            } else {
                                equalInt(anchorX, rect.right, "anchor right | " + context);
                                equalInt(anchorY, rect.top, "anchor top | " + context);
                                check(rect.left >= safe.left,
                                        "left inside the safe rect | " + context);
                            }
                            check(rect.bottom <= safe.bottom,
                                    "bottom inside the safe rect | " + context);
                            equalFloat(720f * result.scale, rect.width(), 0.51f,
                                    "width from the scale | " + context);
                            equalFloat(1280f * result.scale, rect.height(), 0.51f,
                                    "height from the scale | " + context);
                            final double ratio = (double) rect.width() / rect.height();
                            check(Math.abs(ratio - 720d / 1280d) < 0.002d,
                                    "aspect ratio | " + context);
                        }
                    }
                }
            }
        }
        check(available > 50, "grid must exercise many available samples, got " + available);
        check(degraded > 5, "grid must exercise degraded samples, got " + degraded);
        System.out.println("     grid samples: available=" + available + " degraded=" + degraded);
    }
}
