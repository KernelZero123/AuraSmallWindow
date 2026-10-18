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
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Phase;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;

/** Contract checks for the frozen protocol objects GLM will feed. */
final class ProtocolTests {

    private ProtocolTests() {
    }

    static void snapshotCarriesTheDocumentedFields() {
        final Snapshot snapshot = Fixtures.device();
        equal(Fixtures.DEVICE_VISIBLE, snapshot.visibleRect, "visible rect");
        equalInt(720, snapshot.logicalWidth, "logical width");
        equalInt(1280, snapshot.logicalHeight, "logical height");
        equalFloat(0.74f, snapshot.scale, 0f, "actual scale");
        equalFloat(2.0f, snapshot.density, 0f, "display density");
        equal(Fixtures.DISPLAY, snapshot.safeRect, "safe rect");
        equal(Fixtures.DISPLAY, snapshot.fullscreenRect, "fullscreen target");
        equalFloat(440f / 720f, snapshot.minScale, 1e-6f, "allowed interval minimum");
        equalFloat(0.95f, snapshot.maxScale, 0f, "allowed interval maximum");

        // The documented width/height rule: visible size = logical size * scale, with the real
        // screen left/top kept as-is (never multiplied by the scale).
        equalInt(533, snapshot.visibleRect.width(), "533 = round(720 * 0.74)");
        equalInt(947, snapshot.visibleRect.height(), "947 = round(1280 * 0.74)");
        equalInt(93, snapshot.visibleRect.left, "left is a real screen coordinate");
        equalInt(212, snapshot.visibleRect.top, "top is a real screen coordinate");
    }

    static void snapshotFactoryUsesThePlanInterval() {
        final Snapshot of = Snapshot.of(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 2.0f,
                Fixtures.DISPLAY);
        equalFloat(440f / 720f, of.minScale, 1e-6f, "220dp at 320dpi is 440px on a 720px canvas");
        equalFloat(0.6111f, of.minScale, 1e-3f, "the plan value is about 0.61");
        equalFloat(0.95f, of.maxScale, 0f, "plan scale cap");
        equal(of.safeRect, of.fullscreenRect, "the safe rect doubles as the fullscreen target");

        final Snapshot atDensity1 = Snapshot.of(Fixtures.DEVICE_VISIBLE, 720, 1280, 0.74f, 1.0f,
                Fixtures.DISPLAY);
        equalFloat(220f / 720f, atDensity1.minScale, 1e-6f, "density 1.0 floor");

        final Snapshot degenerate = Snapshot.of(Fixtures.DEVICE_VISIBLE, 0, 1280, 0.74f, 2.0f,
                Fixtures.DISPLAY);
        equalFloat(0f, degenerate.minScale, 0f, "a zero logical width cannot divide");
    }

    static void snapshotCopiesAreIndependent() {
        final Snapshot snapshot = Fixtures.device();
        final Snapshot moved = snapshot.withVisibleRect(Rect.of(0, 0, 533, 947));
        equal(Fixtures.DEVICE_VISIBLE, snapshot.visibleRect, "the original keeps its rect");
        equal(Rect.of(0, 0, 533, 947), moved.visibleRect, "the copy carries the new rect");
        final Snapshot scaled = snapshot.withScale(0.5f);
        equalFloat(0.74f, snapshot.scale, 0f, "the original keeps its scale");
        equalFloat(0.5f, scaled.scale, 0f, "the copy carries the new scale");
    }

    static void rectArithmeticIsHandCheckable() {
        final Rect rect = Rect.of(93, 212, 626, 1159);
        equalInt(533, rect.width(), "width");
        equalInt(947, rect.height(), "height");
        equal(Rect.of(93, 152, 626, 1099), rect.offset(0, -60), "offset upwards by 60");
        equal(Rect.of(0, 0, 10, 10).lerpTo(Rect.of(10, 20, 30, 40), 0.5f), Rect.of(5, 10, 20, 25),
                "lerp to the fullscreen rect at 50%");
        equal(Rect.of(93, 212, 626, 1159), rect.lerpTo(Rect.of(500, 500, 500, 500), 0f),
                "lerp at 0 is the source rect");
        equal(Rect.of(500, 500, 500, 500), rect.lerpTo(Rect.of(500, 500, 500, 500), 1f),
                "lerp at 1 is the target rect");
        equal(rect, Rect.of(93, 212, 626, 1159), "value equality");
        check(rect.hashCode() == Rect.of(93, 212, 626, 1159).hashCode(), "hash equality");
        check(!rect.equals(rect.offset(1, 0)), "different rects are unequal");
        check(Rect.empty().width() == 0, "empty rect");
    }

    static void pointerEventFactoriesCarryEveryField() {
        final PointerEvent down = PointerEvent.down(1234L, 7, 10.5f, 20.25f);
        equal(Phase.DOWN, down.phase, "down phase");
        equalInt(1234, (int) down.eventTimeMillis, "event time");
        equalInt(7, down.pointerId, "pointer id");
        equalInt(1, down.pointerCount, "single pointer");
        equalFloat(10.5f, down.rawX, 0f, "raw x");
        equalFloat(20.25f, down.rawY, 0f, "raw y");
        equalInt(2, down.withPointerCount(2).pointerCount, "pointer count copy");
        equalInt(1, down.pointerCount, "the original is unchanged");
        equal(Phase.CANCEL, down.withPhase(Phase.CANCEL).phase, "phase copy");
        equalInt(7, down.withPhase(Phase.CANCEL).pointerId, "phase copy keeps the pointer");
        equal(Phase.MOVE, PointerEvent.move(0L, 0, 0f, 0f).phase, "move factory");
        equal(Phase.UP, PointerEvent.up(0L, 0, 0f, 0f).phase, "up factory");
        equal(Phase.CANCEL, PointerEvent.cancel(0L, 0, 0f, 0f).phase, "cancel factory");
    }

    static void phaseOrdinalsMatchTheAdapterConstants() {
        // AuraSmallWindowBottomBarView (GLM) publishes PHASE_DOWN=0, PHASE_MOVE=1, PHASE_UP=2,
        // PHASE_CANCEL=3 and says the values line up with this protocol. That mapping must not
        // drift silently, so it is pinned here.
        equalInt(0, Phase.DOWN.ordinal(), "PHASE_DOWN adapter constant");
        equalInt(1, Phase.MOVE.ordinal(), "PHASE_MOVE adapter constant");
        equalInt(2, Phase.UP.ordinal(), "PHASE_UP adapter constant");
        equalInt(3, Phase.CANCEL.ordinal(), "PHASE_CANCEL adapter constant");
        equalInt(4, Phase.values().length, "no extra phases");
        equal(Phase.DOWN, Phase.values()[0], "values()[0] is DOWN");
        equal(Phase.CANCEL, Phase.values()[3], "values()[3] is CANCEL");
    }

    static void intentAndRegionHelpers() {
        check(Intent.NONE.isPreview() == false && Intent.NONE.isCommit() == false,
                "NONE is neither preview nor commit");
        check(Intent.PREVIEW_DISMISS.isPreview() && Intent.PREVIEW_DISMISS.isDismiss(),
                "preview dismiss helpers");
        check(Intent.COMMIT_FULLSCREEN.isCommit() && Intent.COMMIT_FULLSCREEN.isFullscreen(),
                "commit fullscreen helpers");
        check(Intent.COMMIT_RESIZE.isResize() && !Intent.COMMIT_RESIZE.isDismiss(),
                "resize helpers");
        check(!Intent.CANCEL.isCommit() && !Intent.CANCEL.isPreview(), "cancel is terminal");
        check(Region.BOTTOM.isBottom() && !Region.BOTTOM.isCorner(), "bottom region helpers");
        check(Region.LOWER_LEFT.isCorner() && !Region.LOWER_LEFT.isBottom(),
                "corner region helpers");
        check(Region.LOWER_RIGHT.isCorner(), "lower right is a corner");
        equalFloat(160f, AuraWindowInteraction.DENSITY_DPI_BASE, 0f, "dpi base");
        equalFloat(220f, AuraWindowInteraction.DEFAULT_MIN_PHYSICAL_WIDTH_DP, 0f, "220dp plan");
        equalFloat(48f, AuraWindowInteraction.DEFAULT_COMMIT_DISTANCE_DP, 0f, "48dp plan");
        equalFloat(800f, AuraWindowInteraction.DEFAULT_FLING_VELOCITY_DP_PER_SEC, 0f, "800dp/s");
        equalFloat(16f, AuraWindowInteraction.DEFAULT_MIN_FLING_DISPLACEMENT_DP, 0f, "16dp");
        equalFloat(0.95f, AuraWindowInteraction.DEFAULT_MAX_SCALE, 0f, "0.95 cap");
    }
}
