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

import static com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Phase;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.check;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equal;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equalFloat;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.equalInt;
import static com.android.wm.shell.windowdecor.aura.ProbeCheck.throwsIllegalArgument;

import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.PointerEvent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;

/**
 * Bottom bar behaviour: tap, slop, direction, commit and every cancel path. Expectations are
 * hand-computed from the device fixture (visible (93,212)-(626,1159), 48dp commit = 96px,
 * 8dp slop = 16px at density 2.0) instead of being read back from the implementation.
 */
final class BottomBarGestureTests {

    private static final float DOWN_X = 360f;
    private static final float DOWN_Y = 1130f;

    private BottomBarGestureTests() {
    }

    static void tapDoesNotAct() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        final Result down = recognizer.begin(Region.BOTTOM, snapshot,
                PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        equal(Intent.NONE, down.intent, "DOWN must not act");
        check(down.active && down.restored && !down.committed,
                "DOWN state must be active/restored/uncommitted: " + down);
        check(recognizer.isTracking(), "recognizer must own the gesture after DOWN");
        equal(Region.BOTTOM, recognizer.trackingRegion(), "tracked region");
        check(recognizer.trackingSnapshot() == snapshot, "snapshot must be latched at DOWN");
        equal(null, down.resize, "bottom bar results carry no resize detail");

        final Result up = recognizer.onEvent(PointerEvent.up(1080L, 1, DOWN_X, DOWN_Y));
        equal(Intent.CANCEL, up.intent, "a tap must cancel, never dismiss");
        equal(snapshot.visibleRect, up.visibleRect, "tap restores the snapshot rect");
        equalFloat(snapshot.scale, up.scale, 0f, "tap restores the snapshot scale");
        check(up.restored && !up.committed && !up.active, "tap result flags: " + up);
        check(!recognizer.isTracking(), "recognizer must be idle after UP");
    }

    static void belowSlopIsIgnored() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result move = recognizer.onEvent(
                PointerEvent.move(1030L, 1, DOWN_X + 8f, DOWN_Y - 10f));
        equal(Intent.NONE, move.intent, "8px/10px is below the 16px slop");
        check(move.active && move.restored, "below-slop move must stay active and restored");
        final Result up = recognizer.onEvent(
                PointerEvent.up(1060L, 1, DOWN_X + 8f, DOWN_Y - 10f));
        equal(Intent.CANCEL, up.intent, "below-slop release must cancel");
    }

    static void swipeUpDismissesOnlyWhenTheThresholdIsReached() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));

        // 60px up: dismiss preview only, 60/96 of the progress, rect follows by raw pixels.
        final Result preview = recognizer.onEvent(
                PointerEvent.move(1100L, 1, DOWN_X, DOWN_Y - 60f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "upward drag previews dismiss");
        check(!preview.intent.isFullscreen(), "upward drag must never preview fullscreen");
        equalFloat(60f / 96f, preview.progress, 1e-5f, "dismiss progress");
        equal(Rect.of(93, 152, 626, 1099), preview.visibleRect, "dismiss preview follows raw dy");
        equalFloat(0.74f, preview.scale, 0f, "dismiss preview keeps the scale");

        // 96px up in 200ms: distance threshold reached, velocity far below the fling threshold.
        final Result commit = recognizer.onEvent(
                PointerEvent.up(1200L, 1, DOWN_X, DOWN_Y - 96f));
        equal(Intent.COMMIT_DISMISS, commit.intent, "96px up must commit the dismiss");
        check(commit.committed && !commit.active && !commit.restored, "commit flags: " + commit);
        equalFloat(1f, commit.progress, 0f, "commit progress is 1");
        equal(Rect.of(93, 116, 626, 1063), commit.visibleRect,
                "commit geometry is the follow-finger end state");
        equalFloat(0.74f, commit.scale, 0f, "dismiss never changes the scale");
    }

    static void slowSubThresholdSwipeUpDoesNotClose() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result preview = recognizer.onEvent(
                PointerEvent.move(1050L, 1, DOWN_X, DOWN_Y - 40f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "40px up previews dismiss");
        // 60px in 100ms = 600px/s < 1600px/s, and 60px < 96px: nothing may commit.
        final Result up = recognizer.onEvent(
                PointerEvent.up(1100L, 1, DOWN_X, DOWN_Y - 60f));
        equal(Intent.CANCEL, up.intent, "slow 60px swipe up must not close the window");
        equal(snapshot.visibleRect, up.visibleRect, "cancel restores the rect");
    }

    static void swipeDownGoesFullscreenOnly() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));

        // 48px down = half of the commit distance: lerp halfway to the fullscreen rect.
        final Result preview = recognizer.onEvent(
                PointerEvent.move(1100L, 1, DOWN_X, DOWN_Y + 48f));
        equal(Intent.PREVIEW_FULLSCREEN, preview.intent, "downward drag previews fullscreen");
        check(!preview.intent.isDismiss(), "downward drag must never dismiss");
        equalFloat(0.5f, preview.progress, 1e-5f, "fullscreen progress");
        equal(Rect.of(47, 106, 673, 1380), preview.visibleRect,
                "fullscreen preview interpolates toward the fullscreen rect");
        equalFloat(0.87f, preview.scale, 1e-5f, "fullscreen preview interpolates the scale");

        final Result commit = recognizer.onEvent(
                PointerEvent.up(1150L, 1, DOWN_X, DOWN_Y + 96f));
        equal(Intent.COMMIT_FULLSCREEN, commit.intent, "96px down must commit fullscreen");
        check(commit.committed, "fullscreen commit flag");
        equal(Fixtures.DISPLAY, commit.visibleRect, "fullscreen target rect");
        equalFloat(1f, commit.scale, 0f, "fullscreen commits at scale 1.0");
        equalFloat(1f, commit.progress, 0f, "fullscreen commit progress");
    }

    static void releaseGeometryIsContinuousWithThePreview() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer pre = Fixtures.recognizer();
        pre.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result lastPreview = pre.onEvent(
                PointerEvent.move(1100L, 1, DOWN_X, DOWN_Y + 96f));
        final AuraWindowGestureRecognizer commit = Fixtures.recognizer();
        commit.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result committed = commit.onEvent(
                PointerEvent.up(1100L, 1, DOWN_X, DOWN_Y + 96f));
        equal(lastPreview.visibleRect, committed.visibleRect,
                "releasing at the threshold must not jump the fullscreen geometry");
        equalFloat(lastPreview.scale, committed.scale, 0f, "release scale continuity");

        final AuraWindowGestureRecognizer preUp = Fixtures.recognizer();
        preUp.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result dismissPreview = preUp.onEvent(
                PointerEvent.move(1100L, 1, DOWN_X, DOWN_Y - 96f));
        final AuraWindowGestureRecognizer commitUp = Fixtures.recognizer();
        commitUp.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result dismissCommit = commitUp.onEvent(
                PointerEvent.up(1100L, 1, DOWN_X, DOWN_Y - 96f));
        equal(dismissPreview.visibleRect, dismissCommit.visibleRect,
                "releasing at the threshold must not jump the dismiss geometry");
    }

    static void longHorizontalDragIsRejected() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result rejected = recognizer.onEvent(
                PointerEvent.move(1050L, 1, DOWN_X + 40f, DOWN_Y + 8f));
        equal(Intent.CANCEL, rejected.intent, "40px right / 8px down is a lateral drag");
        check(!recognizer.isTracking(), "lateral drag must end the gesture");
        // The rest of the same physical gesture must not be able to start an action.
        equal(null, recognizer.onEvent(PointerEvent.move(1100L, 1, DOWN_X + 40f, DOWN_Y - 300f)),
                "after a lateral reject a vertical move is not ours");
        equal(null, recognizer.onEvent(PointerEvent.up(1150L, 1, DOWN_X + 40f, DOWN_Y - 300f)),
                "after a lateral reject the UP is not ours");

        final AuraWindowGestureRecognizer second = Fixtures.recognizer();
        second.begin(Region.BOTTOM, snapshot, PointerEvent.down(2000L, 4, DOWN_X, DOWN_Y));
        final Result diagonal = second.onEvent(
                PointerEvent.move(2050L, 4, DOWN_X + 20f, DOWN_Y + 18f));
        equal(Intent.CANCEL, diagonal.intent,
                "20px right / 18px down must reject on the dominant axis");

        final AuraWindowGestureRecognizer third = Fixtures.recognizer();
        third.begin(Region.BOTTOM, snapshot, PointerEvent.down(3000L, 5, DOWN_X, DOWN_Y));
        final Result vertical = third.onEvent(
                PointerEvent.move(3050L, 5, DOWN_X + 6f, DOWN_Y + 40f));
        equal(Intent.PREVIEW_FULLSCREEN, vertical.intent,
                "40px down / 6px right must still be a fullscreen preview");
    }

    static void multiFingerNeverActs() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        final Result down = recognizer.begin(Region.BOTTOM, snapshot,
                PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y).withPointerCount(2));
        equal(Intent.NONE, down.intent, "multi-finger DOWN must not act");
        check(!down.active && !recognizer.isTracking(), "multi-finger DOWN must not start");
        equal(null, recognizer.onEvent(PointerEvent.move(1050L, 1, DOWN_X, DOWN_Y - 200f)),
                "no gesture owns the event after a multi-finger DOWN");

        final AuraWindowGestureRecognizer interrupted = Fixtures.recognizer();
        interrupted.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result preview = interrupted.onEvent(
                PointerEvent.move(1050L, 1, DOWN_X, DOWN_Y - 60f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "single finger preview before interference");
        final Result cancelled = interrupted.onEvent(
                PointerEvent.move(1060L, 1, DOWN_X, DOWN_Y - 60f).withPointerCount(2));
        equal(Intent.CANCEL, cancelled.intent, "a second finger must cancel the gesture");
        equal(snapshot.visibleRect, cancelled.visibleRect, "interference restores the snapshot");
        check(!interrupted.isTracking(), "interference must end the gesture");
    }

    static void cancelPathsRestoreTheSnapshotAndAllowANewGesture() {
        final Snapshot snapshot = Fixtures.device();

        // 1. ACTION_CANCEL after a preview.
        final AuraWindowGestureRecognizer explicit = Fixtures.recognizer();
        explicit.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        explicit.onEvent(PointerEvent.move(1050L, 1, DOWN_X, DOWN_Y - 60f));
        final Result cancelled = explicit.onEvent(
                PointerEvent.cancel(1060L, 1, DOWN_X, DOWN_Y - 60f));
        equal(Intent.CANCEL, cancelled.intent, "CANCEL must restore");
        equal(snapshot.visibleRect, cancelled.visibleRect, "CANCEL restores the rect");
        equalFloat(snapshot.scale, cancelled.scale, 0f, "CANCEL restores the scale");
        check(cancelled.restored && !cancelled.committed, "CANCEL flags: " + cancelled);

        // 2. The active pointer disappears.
        final AuraWindowGestureRecognizer lost = Fixtures.recognizer();
        lost.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 7, DOWN_X, DOWN_Y));
        lost.onEvent(PointerEvent.move(1050L, 7, DOWN_X, DOWN_Y - 60f));
        final Result foreign = lost.onEvent(PointerEvent.move(1060L, 9, DOWN_X, DOWN_Y - 60f));
        equal(Intent.CANCEL, foreign.intent, "a foreign pointer id must cancel");
        equal(snapshot.visibleRect, foreign.visibleRect, "pointer loss restores the rect");

        final AuraWindowGestureRecognizer zeroCount = Fixtures.recognizer();
        zeroCount.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 3, DOWN_X, DOWN_Y));
        final Result empty = zeroCount.onEvent(
                PointerEvent.move(1010L, 3, DOWN_X, DOWN_Y - 30f).withPointerCount(0));
        equal(Intent.CANCEL, empty.intent, "a zero pointer count must cancel");

        // 3. After every cancel a brand new DOWN works normally.
        final Result restart = explicit.begin(Region.BOTTOM, snapshot,
                PointerEvent.down(2000L, 1, DOWN_X, DOWN_Y));
        equal(Intent.NONE, restart.intent, "a new DOWN starts fresh");
        check(restart.active && !restart.canceledPrevious, "fresh DOWN state: " + restart);
        final Result up = explicit.onEvent(PointerEvent.up(2200L, 1, DOWN_X, DOWN_Y - 96f));
        equal(Intent.COMMIT_DISMISS, up.intent, "the restarted gesture can commit normally");
    }

    static void rapidReentryDiscardsThePreviousGesture() {
        final Snapshot first = Fixtures.device();
        final Snapshot second = new Snapshot(Rect.of(10, 20, 450, 820), 720, 1280, 0.6111f, 2f,
                Fixtures.DISPLAY, Fixtures.DISPLAY, 440f / 720f, 0.95f);
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, first, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result preview = recognizer.onEvent(
                PointerEvent.move(1050L, 1, DOWN_X, DOWN_Y - 60f));
        equal(Intent.PREVIEW_DISMISS, preview.intent, "preview before the re-entrant DOWN");

        final Result reentered = recognizer.begin(Region.BOTTOM, second,
                PointerEvent.down(1060L, 2, 200f, 700f));
        check(reentered.canceledPrevious, "re-entrant DOWN must report the discarded gesture");
        equal(Intent.NONE, reentered.intent, "re-entrant DOWN does not act");
        equal(second.visibleRect, reentered.visibleRect, "re-entrant DOWN shows the new snapshot");
        equalFloat(second.scale, reentered.scale, 0f, "re-entrant DOWN uses the new scale");
        check(reentered.active, "re-entrant DOWN must own the new gesture");
        check(recognizer.trackingSnapshot() == second, "the new snapshot is latched");

        // The old finger releasing must not commit the new gesture.
        final Result stale = recognizer.onEvent(PointerEvent.up(1100L, 1, 200f, 100f));
        equal(Intent.CANCEL, stale.intent, "the old pointer must be rejected, not committed");
        check(!stale.committed, "a stale pointer must never commit");
        check(!recognizer.isTracking(), "the foreign pointer ends the new gesture");

        final Result again = recognizer.begin(Region.BOTTOM, second,
                PointerEvent.down(1200L, 2, 200f, 700f));
        check(again.active, "a fresh DOWN after the stale pointer starts normally");
        final Result committed = recognizer.onEvent(PointerEvent.up(1400L, 2, 200f, 604f));
        equal(Intent.COMMIT_DISMISS, committed.intent, "the new gesture still commits");
    }

    static void cornersNeverDismissOrGoFullscreen() {
        final Snapshot snapshot = Fixtures.device();
        for (Region region : new Region[] {Region.LOWER_LEFT, Region.LOWER_RIGHT}) {
            for (float dy : new float[] {-200f, 200f}) {
                final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
                final float[] grab = Fixtures.cornerPoint(snapshot, region, 0.74f);
                recognizer.begin(region, snapshot,
                        PointerEvent.down(1000L, 1, grab[0], grab[1]));
                final Result preview = recognizer.onEvent(
                        PointerEvent.move(1100L, 1, grab[0], grab[1] + dy));
                check(!preview.intent.isDismiss() && !preview.intent.isFullscreen(),
                        "a corner drag must never dismiss or go fullscreen: " + preview);
                final Result up = recognizer.onEvent(
                        PointerEvent.up(1200L, 1, grab[0], grab[1] + dy));
                check(!up.intent.isDismiss() && !up.intent.isFullscreen(),
                        "a corner release must never dismiss or go fullscreen: " + up);
                if (up.intent == Intent.COMMIT_RESIZE) {
                    check(up.resize != null, "commit resize must carry the resize detail");
                    if (region == Region.LOWER_RIGHT) {
                        equalInt(93, up.visibleRect.left, "LOWER_RIGHT keeps left");
                        equalInt(212, up.visibleRect.top, "LOWER_RIGHT keeps top");
                        equal(AuraWindowResizeGeometry.Anchor.TOP_LEFT, up.resize.anchor,
                                "LOWER_RIGHT anchors the top-left corner");
                    } else {
                        equalInt(626, up.visibleRect.right, "LOWER_LEFT keeps right");
                        equalInt(212, up.visibleRect.top, "LOWER_LEFT keeps top");
                        equal(AuraWindowResizeGeometry.Anchor.TOP_RIGHT, up.resize.anchor,
                                "LOWER_LEFT anchors the top-right corner");
                    }
                }
            }
        }
    }

    static void bottomRegionNeverResizes() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        recognizer.begin(Region.BOTTOM, snapshot, PointerEvent.down(1000L, 1, DOWN_X, DOWN_Y));
        final Result move = recognizer.onEvent(
                PointerEvent.move(1100L, 1, DOWN_X, DOWN_Y - 60f));
        check(!move.intent.isResize(), "the bottom region must never resize");
        equal(null, move.resize, "bottom bar results have no resize detail");
        final Result up = recognizer.onEvent(
                PointerEvent.up(1200L, 1, DOWN_X, DOWN_Y - 96f));
        check(!up.intent.isResize(), "the bottom commit is not a resize");
        equal(null, up.resize, "bottom bar commit has no resize detail");
    }

    static void cornerTapDoesNotResize() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        final float[] grab = Fixtures.cornerPoint(snapshot, Region.LOWER_RIGHT, 0.74f);
        final Result down = recognizer.begin(Region.LOWER_RIGHT, snapshot,
                PointerEvent.down(1000L, 1, grab[0], grab[1]));
        check(down.resize != null && down.resize.resizable,
                "a resizable corner reports its limits at DOWN: " + down.resize);
        final Result tiny = recognizer.onEvent(
                PointerEvent.move(1050L, 1, grab[0] + 10f, grab[1] + 10f));
        equal(Intent.NONE, tiny.intent, "14.1px is below the 16px slop");
        check(tiny.restored, "below-slop corner move must not resize");
        final Result up = recognizer.onEvent(
                PointerEvent.up(1100L, 1, grab[0] + 10f, grab[1] + 10f));
        equal(Intent.CANCEL, up.intent, "a corner tap must cancel");
        equal(snapshot.visibleRect, up.visibleRect, "corner tap keeps the rect");
    }

    static void cornerCancelRestoresAndAllowsANewResize() {
        final Snapshot snapshot = Fixtures.device();
        final AuraWindowGestureRecognizer recognizer = Fixtures.recognizer();
        final float[] grab = Fixtures.cornerPoint(snapshot, Region.LOWER_RIGHT, 0.74f);
        recognizer.begin(Region.LOWER_RIGHT, snapshot,
                PointerEvent.down(1000L, 1, grab[0], grab[1]));
        final Result preview = recognizer.onEvent(
                PointerEvent.move(1100L, 1, grab[0] + 36f, grab[1] + 64f));
        equal(Intent.PREVIEW_RESIZE, preview.intent, "corner drag previews a resize");
        check(preview.scale > 0.74f, "dragging outwards grows the window: " + preview);
        equalInt(93, preview.visibleRect.left, "preview keeps the anchored left");
        equalInt(212, preview.visibleRect.top, "preview keeps the anchored top");

        final Result cancelled = recognizer.onEvent(
                PointerEvent.cancel(1150L, 1, grab[0] + 36f, grab[1] + 64f));
        equal(Intent.CANCEL, cancelled.intent, "CANCEL must restore the resize snapshot");
        equalFloat(0.74f, cancelled.scale, 0f, "cancel restores the scale exactly");
        equal(snapshot.visibleRect, cancelled.visibleRect, "cancel restores the rect exactly");
        check(!recognizer.isTracking(), "cancel ends the resize gesture");

        // A new DOWN after the cancel resizes normally and can commit.
        final Result again = recognizer.begin(Region.LOWER_RIGHT, snapshot,
                PointerEvent.down(2000L, 2, grab[0], grab[1]));
        check(again.active && !again.canceledPrevious, "fresh corner DOWN: " + again);
        final Result committed = recognizer.onEvent(
                PointerEvent.up(2200L, 2, grab[0] + 36f, grab[1] + 64f));
        equal(Intent.COMMIT_RESIZE, committed.intent, "the restarted resize commits");
        check(committed.committed && !committed.restored, "commit flags: " + committed);
        equal(committed.resize.visibleRect, committed.visibleRect,
                "the flattened geometry mirrors the resize detail");
        equalFloat(committed.resize.scale, committed.scale, 0f,
                "the flattened scale mirrors the resize detail");
    }

    static void strayEventsAndContractViolations() {
        final AuraWindowGestureRecognizer idle = Fixtures.recognizer();
        equal(null, idle.onEvent(PointerEvent.move(1000L, 1, 10f, 10f)),
                "an idle recognizer ignores MOVE");
        equal(null, idle.onEvent(PointerEvent.up(1010L, 1, 10f, 10f)),
                "an idle recognizer ignores UP");
        equal(null, idle.cancel(), "cancel while idle reports nothing to restore");

        final Snapshot snapshot = Fixtures.device();
        throwsIllegalArgument(() -> idle.begin(Region.BOTTOM, snapshot,
                        PointerEvent.move(1000L, 1, 10f, 10f)),
                "begin requires a DOWN event");
        throwsIllegalArgument(() -> idle.begin(Region.BOTTOM, null,
                        PointerEvent.down(1000L, 1, 10f, 10f)),
                "begin requires a snapshot");
        throwsIllegalArgument(() -> idle.onEvent((PointerEvent) null),
                "onEvent rejects null");

        // An explicit cancel (ownership loss) restores and stops tracking.
        idle.begin(Region.BOTTOM, snapshot, PointerEvent.down(2000L, 1, DOWN_X, DOWN_Y));
        idle.onEvent(PointerEvent.move(2050L, 1, DOWN_X, DOWN_Y - 60f));
        final Result explicit = idle.cancel();
        equal(Intent.CANCEL, explicit.intent, "explicit cancel restores");
        equal(snapshot.visibleRect, explicit.visibleRect, "explicit cancel restores the rect");
        check(!idle.isTracking(), "explicit cancel ends tracking");

        // The dispatcher overload routes DOWN itself.
        final Result dispatched = idle.onEvent(Region.BOTTOM, snapshot,
                PointerEvent.down(3000L, 1, DOWN_X, DOWN_Y));
        check(dispatched.active, "dispatcher starts the gesture on DOWN");
        equal(Phase.DOWN, PointerEvent.down(0L, 1, 0f, 0f).phase, "down factory phase");
    }
}
