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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowInsets;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression tests for physical IME overlap at different phone-task positions and scales. */
@SmallTest
@RunWith(WindowTestRunner.class)
public class AuraSmallWindowInsetsTests extends WindowTestsBase {
    private static final Rect TASK = new Rect(94, 212, 814, 1492);

    private static InsetsState stateWithIme(Rect frame) {
        final InsetsState state = new InsetsState();
        state.addSource(new InsetsSource(InsetsSource.ID_IME, WindowInsets.Type.ime())
                .setFrame(frame).setVisible(true));
        return state;
    }

    @Test
    public void physicalOverlapMatchesProjectedInset() {
        for (int percent = 50; percent <= 90; percent += 5) {
            final float scale = percent / 100f;
            for (int top = 80; top <= 400; top += 80) {
                final Rect bounds = new Rect(TASK);
                bounds.offsetTo(TASK.left, top);
                for (int imeTop = 500; imeTop <= 1500; imeTop += 100) {
                    final InsetsState result = AuraSmallWindowInsets.projectIme(
                            stateWithIme(new Rect(0, imeTop, 720, 1600)), bounds, scale);
                    final int logicalInset = result.peekSource(InsetsSource.ID_IME)
                            .calculateInsets(bounds, bounds, false).bottom;
                    final float expectedOverlap = Math.max(0f,
                            Math.min(bounds.height() * scale,
                                    bounds.top + bounds.height() * scale - imeTop));
                    // Outward rounding permits less than one physical pixel of extra avoidance.
                    assertTrue("No visible overlap may be missed", logicalInset * scale
                            >= expectedOverlap - 0.01f);
                    assertTrue("Do not resize using unscaled display coordinates",
                            logicalInset * scale <= expectedOverlap + 1f);
                }
            }
        }
    }

    @Test
    public void imeBelowVisibleWindowDoesNotInsetLogicalWindow() {
        final InsetsState result = AuraSmallWindowInsets.projectIme(
                stateWithIme(new Rect(0, 1200, 720, 1600)), TASK, .74f);
        assertEquals(Insets.NONE, result.peekSource(InsetsSource.ID_IME)
                .calculateInsets(TASK, TASK, false));
    }

    @Test
    public void doesNotMutateDisplayStateOrCaption() {
        final InsetsState state = stateWithIme(new Rect(0, 1000, 720, 1600));
        final InsetsSource originalIme = state.peekSource(InsetsSource.ID_IME);
        final int captionId = InsetsSource.createId(null, 0, WindowInsets.Type.captionBar());
        final InsetsSource caption = new InsetsSource(captionId, WindowInsets.Type.captionBar())
                .setFrame(94, 212, 814, 331).setVisible(true);
        state.addSource(caption);
        final InsetsState result = AuraSmallWindowInsets.projectIme(state, TASK, .74f);
        assertNotSame(state, result);
        assertNotSame(originalIme, result.peekSource(InsetsSource.ID_IME));
        assertEquals(new Rect(0, 1000, 720, 1600), originalIme.getFrame());
        assertSame(caption, result.peekSource(captionId));
    }

    @Test
    public void preservesHiddenAndFloatingImeSemantics() {
        final InsetsState hidden = stateWithIme(new Rect(0, 1000, 720, 1600));
        hidden.peekSource(InsetsSource.ID_IME).setVisible(false);
        final InsetsSource result = AuraSmallWindowInsets.projectIme(hidden, TASK, .74f)
                .peekSource(InsetsSource.ID_IME);
        assertFalse(result.isVisible());
        assertEquals(Insets.NONE, result.calculateInsets(TASK, TASK, false));
        final InsetsState floating = stateWithIme(new Rect());
        assertSame(floating, AuraSmallWindowInsets.projectIme(floating, TASK, .74f));
    }

    @Test
    public void transformsVisibleFrameAndRelativeBoundingRectsWithoutSharing() {
        final Rect task = new Rect(100, 200, 820, 1480);
        final InsetsState state = stateWithIme(new Rect(0, 1000, 720, 1600));
        final InsetsSource source = state.peekSource(InsetsSource.ID_IME);
        source.setVisibleFrame(new Rect(10, 1020, 710, 1580));
        source.setBoundingRects(new Rect[] { new Rect(20, 30, 700, 590) });
        final InsetsSource result = AuraSmallWindowInsets.projectIme(state, task, .5f)
                .peekSource(InsetsSource.ID_IME);
        assertEquals(new Rect(-100, 1800, 1340, 3000), result.getFrame());
        assertEquals(new Rect(-80, 1840, 1320, 2960), result.getVisibleFrame());
        assertEquals(new Rect(40, 60, 1400, 1180), result.getBoundingRects()[0]);
        result.getBoundingRects()[0].offset(500, 500);
        assertEquals(new Rect(20, 30, 700, 590), source.getBoundingRects()[0]);
        assertEquals(new Rect(10, 1020, 710, 1580), source.getVisibleFrame());
    }

    @Test
    public void unchangedForUnscaledTasksAndMissingIme() {
        final InsetsState state = stateWithIme(new Rect(0, 1000, 720, 1600));
        assertSame(state, AuraSmallWindowInsets.projectIme(state, TASK, 1f));
        final InsetsState empty = new InsetsState();
        assertSame(empty, AuraSmallWindowInsets.projectIme(empty, TASK, .74f));
    }

    @Test
    public void freezingKeepsDisplaySpaceAndProjectsOnlyOnce() {
        final WindowState window = newWindowBuilder("scaled-ime",
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION).build();
        final Task task = window.getTask();
        doReturn(.74f).when(task).getAuraSmallWindowScale();
        doReturn(new Rect(TASK)).when(task).getBounds();
        final Rect displayIme = new Rect(0, 1000, 720, 1600);
        window.mAboveInsetsState.addSource(new InsetsSource(
                InsetsSource.ID_IME, WindowInsets.Type.ime())
                .setFrame(displayIme).setVisible(true));
        final Rect before = new Rect(window.getInsetsState()
                .peekSource(InsetsSource.ID_IME).getFrame());
        window.freezeInsetsState();
        assertEquals(displayIme, window.getFrozenInsetsState()
                .peekSource(InsetsSource.ID_IME).getFrame());
        assertEquals(before, window.getInsetsState().peekSource(InsetsSource.ID_IME).getFrame());
        assertEquals(before, window.getCompatInsetsState()
                .peekSource(InsetsSource.ID_IME).getFrame());
        doReturn(1f).when(task).getAuraSmallWindowScale();
        assertEquals(displayIme, window.getInsetsState()
                .peekSource(InsetsSource.ID_IME).getFrame());
        window.clearFrozenInsetsState();
        assertEquals(displayIme, window.getInsetsState()
                .peekSource(InsetsSource.ID_IME).getFrame());
    }
}
