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
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.throwsIllegalArgument;

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;

/**
 * Threshold, speed and cancellation semantics. The expected numbers are the plan values converted
 * by hand for density 2.0: slop 16px, commit 48dp = 96px, fling 800dp/s = 1600px/s, minimum fling
 * displacement 16dp = 32px.
 */
final class SpeedAndSpeedThresholdTests {

    private static final float X = 360f;
    private static final float Y = 1130f;

    private SpeedAndSpeedThresholdTests() {
    }

    static void thresholdsComeFromDpExactlyOnce() {
        final AuraWindowGestureRecognizer.Config density2 =
                AuraWindowGestureRecognizer.Config.defaults(2.0f, 16f);
        equalFloat(16f, density2.touchSlopPx, 0f, "provided slop is used verbatim");
        equalFloat(96f, density2.commitDistancePx, 1e-4f, "48dp at density 2.0");
        equalFloat(1600f, density2.flingVelocityPxPerSec, 1e-3f, "800dp/s at density 2.0");
        equalFloat(32f, density2.minFlingDisplacementPx, 1e-4f, "16dp at density 2.0");
        equalFloat(96f, density2.progressDistancePx, 1e-4f, "progress distance defaults to commit");
        equalFloat(192f, density2.dismissPreviewTravelPx, 1e-4f, "dismiss travel cap at 96dp");

        final AuraWindowGestureRecognizer.Config density3 =
                AuraWindowGestureRecognizer.Config.defaults(3.0f, 24f);
        equalFloat(144f, density3.commitDistancePx, 1e-4f, "48dp at density 3.0");
        equalFloat(2400f, density3.flingVelocityPxPerSec, 1e-3f, "800dp/s at density 3.0");
        equalFloat(48f, density3.minFlingDisplacementPx, 1e-4f, "16dp at density 3.0");

        final AuraWindowGestureRecognizer.Config density275 =
                AuraWindowGestureRecognizer.Config.defaults(2.75f, 22f);
        equalFloat(132f, density275.commitDistancePx, 1e-4f, "48dp at density 2.75");
        equalFloat(2200f, density275.flingVelocityPxPerSec, 1e-3f, "800dp/s at density 2.75");
        equalFloat(44f, density275.minFlingDisplacementPx, 1e-4f, "16dp at density 2.75");

        // The handoff's worked example: 320dpi is density 2.0, so 20dp is 40px.
        equalFloat(2.0f, AuraWindowInteraction.densityFromDpi(320), 1e-6f, "320dpi density");
        equalFloat(3.0f, AuraWindowInteraction.densityFromDpi(480), 1e-6f, "480dpi density");
        equalFloat(40f, AuraWindowInteraction.dpToPx(20f, 2.0f), 1e-6f, "20dp at 320dpi is 40px");

        final AuraWindowGestureRecognizer.Config custom =
                AuraWindowGestureRecognizer.Config.builder(2.0f)
                        .touchSlopPx(16f)
                        .commitDistanceDp(100f)
                        .flingVelocityDpPerSec(50f)
                        .minFlingDisplacementDp(5f)
                        .build();
        equalFloat(200f, custom.commitDistancePx, 1e-4f, "custom commit distance in dp");
        equalFloat(100f, custom.flingVelocityPxPerSec, 1e-3f, "custom fling velocity in dp/s");
        equalFloat(10f, custom.minFlingDisplacementPx, 1e-4f, "custom fling displacement in dp");

        throwsIllegalArgument(() -> AuraWindowGestureRecognizer.Config.builder(2.0f).build(),
                "config without a touch slop must be rejected");
    }

    static void flingCommitsBelowTheDistanceThreshold() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result preview = recognizer.onEvent(PointerEvent.move(1005L, 1, X, Y - 40f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "40px up resolves the direction");
        final Result up = recognizer.onEvent(PointerEvent.up(1010L, 1, X, Y - 40f));
        // 40px in 10ms = 4000px/s >= 1600px/s and 40px >= 32px, although 40px < 96px.
        equal(Intent.COMMIT_DISMISS, up.intent, "a fast short swipe must commit");
        equal(Rect.of(93, 212 - 40, 626, 1159 - 40), up.visibleRect, "fling commit geometry");
    }

    static void highSpeedLowDisplacementNeverCommits() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        recognizer.onEvent(PointerEvent.move(1001L, 1, X, Y - 24f));
        // 24px in 1ms = 24000px/s but only 24px of travel: below the 32px minimum.
        final Result up = recognizer.onEvent(PointerEvent.up(1001L, 1, X, Y - 24f));
        equal(Intent.CANCEL, up.intent, "high-speed noise with tiny travel must not act");
        equal(snapshot.visibleRect, up.visibleRect, "noise restores the snapshot");
    }

    static void degenerateTimeDeltasNeverThrow() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer zero = Fixtures.recognizer();
        zero.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        zero.onEvent(PointerEvent.move(1000L, 1, X, Y - 60f));
        final Result zeroDelta = zero.onEvent(PointerEvent.up(1000L, 1, X, Y - 60f));
        equal(Intent.CANCEL, zeroDelta.intent, "a zero time delta cannot fling");

        final AuraWindowGestureRecognizer negative = Fixtures.recognizer();
        negative.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        negative.onEvent(PointerEvent.move(995L, 1, X, Y - 60f));
        final Result negativeDelta = negative.onEvent(PointerEvent.up(990L, 1, X, Y - 60f));
        equal(Intent.CANCEL, negativeDelta.intent, "a negative time delta cannot fling");

        // Distance stays independent of time: a zero delta still commits when far enough.
        final AuraWindowGestureRecognizer far = Fixtures.recognizer();
        far.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result farUp = far.onEvent(PointerEvent.up(1000L, 1, X, Y - 200f));
        equal(Intent.COMMIT_DISMISS, farUp.intent, "distance alone must still commit");
    }

    static void velocityIsMeasuredInPixelsPerSecond() {
        final Snapshot snapshot = Fixtures.device();
        // 32px in 20ms = exactly 1600px/s: at the threshold, so it commits.
        final AuraWindowGestureRecognizer atThreshold = Fixtures.recognizer();
        atThreshold.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        atThreshold.onEvent(PointerEvent.move(1010L, 1, X, Y - 32f));
        final Result hit = atThreshold.onEvent(PointerEvent.up(1020L, 1, X, Y - 32f));
        equal(Intent.COMMIT_DISMISS, hit.intent, "1600px/s is exactly the fling threshold");

        // 32px in 21ms = 1523.8px/s: below the threshold and below the distance.
        final AuraWindowGestureRecognizer belowThreshold = Fixtures.recognizer();
        belowThreshold.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        belowThreshold.onEvent(PointerEvent.move(1015L, 1, X, Y - 32f));
        final Result miss = belowThreshold.onEvent(PointerEvent.up(1021L, 1, X, Y - 32f));
        equal(Intent.CANCEL, miss.intent, "1523px/s is below the fling threshold");
    }

    static void terminalPointerSampleGovernsTheVelocity() {
        final Snapshot snapshot = Fixtures.device();
        // Same positions, different release times: the UP time must be the end of the window.
        final AuraWindowGestureRecognizer slow = Fixtures.recognizer();
        slow.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        slow.onEvent(PointerEvent.move(1010L, 1, X, Y - 60f));
        equal(Intent.CANCEL, slow.onEvent(PointerEvent.up(1100L, 1, X, Y - 60f)).intent,
                "60px in 100ms is 600px/s");

        final AuraWindowGestureRecognizer fast = Fixtures.recognizer();
        fast.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        fast.onEvent(PointerEvent.move(1010L, 1, X, Y - 60f));
        equal(Intent.COMMIT_DISMISS, fast.onEvent(PointerEvent.up(1015L, 1, X, Y - 60f)).intent,
                "60px in 15ms is 4000px/s");

        // The UP position also governs: a stale release point must not reuse the MOVE travel.
        final AuraWindowGestureRecognizer stale = Fixtures.recognizer();
        stale.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        stale.onEvent(PointerEvent.move(1010L, 1, X, Y - 80f));
        final Result up = stale.onEvent(PointerEvent.up(1012L, 1, X, Y - 20f));
        equal(Intent.CANCEL, up.intent,
                "the UP sample (20px) governs, not the earlier MOVE (80px)");
    }

    static void reversalAfterResolutionPreventsCommit() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result preview = recognizer.onEvent(PointerEvent.move(1050L, 1, X, Y - 70f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "70px up resolves dismiss");
        final Result reversed = recognizer.onEvent(PointerEvent.up(1120L, 1, X, Y + 10f));
        equal(Intent.CANCEL, reversed.intent,
                "a finger that ends on the wrong side must not close the window");
        equal(snapshot.visibleRect, reversed.visibleRect, "reversal restores the snapshot");

        final AuraWindowGestureRecognizer halfBack = Fixtures.recognizer();
        halfBack.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        halfBack.onEvent(PointerEvent.move(1050L, 1, X, Y - 70f));
        final Result half = halfBack.onEvent(PointerEvent.up(1060L, 1, X, Y - 20f));
        equal(Intent.CANCEL, half.intent, "20px of remaining travel is below every threshold");
    }

    static void singleUpFlickCommitsWithoutAnyMove() {
        final Snapshot snapshot = Fixtures.device();
        // Input batching can deliver a fast flick as DOWN + UP only: the direction must be
        // resolved from the terminal sample, otherwise a real flick would never work.
        final AuraWindowGestureRecognizer dismiss = Fixtures.recognizer();
        dismiss.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result up = dismiss.onEvent(PointerEvent.up(1010L, 1, X, Y - 40f));
        equal(Intent.COMMIT_DISMISS, up.intent, "DOWN+UP flick must commit on speed");

        final AuraWindowGestureRecognizer fullscreen = Fixtures.recognizer();
        fullscreen.begin(Region.BOTTOM, snapshot, PointerEvent.down(2000L, 2, X, Y));
        final Result down = fullscreen.onEvent(PointerEvent.up(2200L, 2, X, Y + 96f));
        equal(Intent.COMMIT_FULLSCREEN, down.intent, "DOWN+UP drag must commit on distance");

        // A DOWN+UP that never exceeded the slop is still a tap.
        final AuraWindowGestureRecognizer tap = Fixtures.recognizer();
        tap.begin(Region.BOTTOM, snapshot, PointerEvent.down(3000L, 3, X, Y));
        equal(Intent.CANCEL, tap.onEvent(PointerEvent.up(3080L, 3, X, Y + 6f)).intent,
                "a 6px DOWN+UP is still a tap");

        // A lateral DOWN+UP is still rejected.
        final AuraWindowGestureRecognizer lateral = Fixtures.recognizer();
        lateral.begin(Region.BOTTOM, snapshot, PointerEvent.down(4000L, 4, X, Y));
        equal(Intent.CANCEL, lateral.onEvent(PointerEvent.up(4080L, 4, X + 60f, Y + 4f)).intent,
                "a lateral DOWN+UP must not act");
    }

    static void thresholdsAreParametersNotHiddenConstants() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer.Config config = AuraWindowGestureRecognizer.Config
                .builder(2.0f)
                .touchSlopPx(16f)
                .commitDistanceDp(100f)
                .build();
        final AuraWindowGestureRecognizer recognizer = new AuraWindowGestureRecognizer(config,
                AuraWindowResizeGeometry.Config.forDensity(2.0f));

        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result defaultSized = recognizer.onEvent(
                PointerEvent.up(1400L, 1, X, Y - 96f));
        equal(Intent.CANCEL, defaultSized.intent,
                "with a 200px commit distance the default 96px swipe must not commit");

        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(2000L, 1, X, Y));
        final Result doubled = recognizer.onEvent(PointerEvent.up(2400L, 1, X, Y - 200f));
        equal(Intent.COMMIT_DISMISS, doubled.intent,
                "with a 200px commit distance a 200px swipe must commit");

        final AuraWindowGestureRecognizer.Config noMinFling = AuraWindowGestureRecognizer.Config
                .builder(2.0f)
                .touchSlopPx(16f)
                .minFlingDisplacementDp(0f)
                .build();
        final AuraWindowGestureRecognizer fling = new AuraWindowGestureRecognizer(noMinFling,
                AuraWindowResizeGeometry.Config.forDensity(2.0f));
        fling.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        fling.onEvent(PointerEvent.move(1000L, 1, X, Y - 16f));
        final Result tiny = fling.onEvent(PointerEvent.up(1001L, 1, X, Y - 16f));
        equal(Intent.COMMIT_DISMISS, tiny.intent,
                "a zero minimum fling displacement must honour the parameter");
    }

    static void dismissPreviewTravelIsCapped() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer.Config config = AuraWindowGestureRecognizer.Config
                .builder(2.0f)
                .touchSlopPx(16f)
                .commitDistanceDp(400f)
                .progressDistanceDp(48f)
                .dismissPreviewTravelDp(96f)
                .build();
        final AuraWindowGestureRecognizer recognizer = new AuraWindowGestureRecognizer(config,
                AuraWindowResizeGeometry.Config.forDensity(2.0f));
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, X, Y));
        final Result preview = recognizer.onEvent(PointerEvent.move(3000L, 1, X, Y - 400f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "long slow drag still previews dismiss");
        equal(Rect.of(93, 212 - 192, 626, 1159 - 192), preview.visibleRect,
                "follow-finger translation is capped at 96dp");
        equalFloat(1f, preview.progress, 1e-5f, "progress saturates at its own distance");
        final Result up = recognizer.onEvent(PointerEvent.up(4000L, 1, X, Y - 400f));
        equal(Intent.CANCEL, up.intent, "400px is below the configured 800px commit distance");
    }
}
