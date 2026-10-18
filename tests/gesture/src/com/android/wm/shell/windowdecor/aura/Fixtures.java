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
 * Device-shaped fixtures for the probe suites.
 *
 * <p>The measured state of the target device is 720x1280 logical pixels at 320dpi
 * (density 2.0) with scale 0.74 and a visible rect of (93,212)-(626,1159), i.e. 533x947 screen
 * pixels. Those numbers are used verbatim wherever a test needs the real configuration.
 */
final class Fixtures {

    private Fixtures() {
    }

    static final float DENSITY = 2.0f;
    static final int DENSITY_DPI = 320;
    static final int DISPLAY_W = 720;
    static final int DISPLAY_H = 1600;
    static final Rect DISPLAY = Rect.of(0, 0, DISPLAY_W, DISPLAY_H);
    static final int LOGICAL_W = 720;
    static final int LOGICAL_H = 1280;
    static final float DEFAULT_SCALE = 0.74f;
    static final Rect DEVICE_VISIBLE = Rect.of(93, 212, 626, 1159);
    /** 8dp at density 2.0, the usual ViewConfiguration scaled touch slop. */
    static final float SLOP_PX = 16f;
    /** sqrt(720^2 + 1280^2): the logical diagonal, the scale/pixel conversion factor. */
    static final double LOGICAL_DIAGONAL = Math.sqrt(
            (double) LOGICAL_W * LOGICAL_W + (double) LOGICAL_H * LOGICAL_H);

    static final AuraWindowResizeGeometry.Config DEVICE_GEOMETRY =
            AuraWindowResizeGeometry.Config.forDensity(DENSITY);
    /** 220dp floor disabled: used by pure projection/ratio invariants. */
    static final AuraWindowResizeGeometry.Config NO_MIN_WIDTH =
            AuraWindowResizeGeometry.Config.ofDp(0f, DENSITY);

    /** Real measured snapshot: interval = [220dp/720, 0.95] = [0.6111, 0.95]. */
    static Snapshot device() {
        return new Snapshot(DEVICE_VISIBLE, LOGICAL_W, LOGICAL_H, DEFAULT_SCALE, DENSITY, DISPLAY,
                DISPLAY, 440f / LOGICAL_W, AuraWindowInteraction.DEFAULT_MAX_SCALE);
    }

    /** Real measured snapshot with an explicit safe/fullscreen area. */
    static Snapshot device(Rect safeRect) {
        return new Snapshot(DEVICE_VISIBLE, LOGICAL_W, LOGICAL_H, DEFAULT_SCALE, DENSITY, safeRect,
                safeRect, 440f / LOGICAL_W, AuraWindowInteraction.DEFAULT_MAX_SCALE);
    }

    static Snapshot snapshot(Rect visibleRect, int logicalWidth, int logicalHeight, float scale,
            float density, Rect safeRect, float minScale, float maxScale) {
        return new Snapshot(visibleRect, logicalWidth, logicalHeight, scale, density, safeRect,
                safeRect, minScale, maxScale);
    }

    /** Visible rect of a uniform scale with the given anchored corner. */
    static Rect visibleFor(Region region, int anchorX, int anchorY, int logicalWidth,
            int logicalHeight, float scale) {
        final int width = Math.round(logicalWidth * scale);
        final int height = Math.round(logicalHeight * scale);
        if (region == Region.LOWER_LEFT) {
            return Rect.of(anchorX - width, anchorY, anchorX, anchorY + height);
        }
        return Rect.of(anchorX, anchorY, anchorX + width, anchorY + height);
    }

    /**
     * The raw pointer position of the dragged corner for a scale, i.e. what the finger would sit
     * on if the visible rect matched that scale exactly.
     */
    static float[] cornerPoint(Snapshot snapshot, Region region, float scale) {
        final float x = region == Region.LOWER_LEFT
                ? snapshot.visibleRect.right - snapshot.logicalWidth * scale
                : snapshot.visibleRect.left + snapshot.logicalWidth * scale;
        final float y = snapshot.visibleRect.top + snapshot.logicalHeight * scale;
        return new float[] {x, y};
    }

    static AuraWindowGestureRecognizer recognizer() {
        return new AuraWindowGestureRecognizer(DENSITY, SLOP_PX);
    }

    static AuraWindowGestureRecognizer recognizer(
            AuraWindowResizeGeometry.Config geometryConfig) {
        return new AuraWindowGestureRecognizer(
                AuraWindowGestureRecognizer.Config.defaults(DENSITY, SLOP_PX), geometryConfig);
    }

    static boolean sameRect(Rect expected, Rect actual) {
        return expected != null && expected.equals(actual);
    }
}
