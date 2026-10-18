/* Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

/** Geometry only: changing canvas orientation must preserve its ratio and fit the visible area. */
final class AuraSmallWindowOrientationGeometry {
    static final class Pose {
        final int left, top, width, height;
        final float scale, normalScale;
        Pose(int x, int y, int w, int h, float s, float normal) {
            left = x; top = y; width = w; height = h; scale = s; normalScale = normal;
        }
    }

    static Pose rotate(int left, int top, int width, int height, float scale,
            float preferredScale, int safeLeft, int safeTop, int safeRight, int safeBottom,
            float margin, boolean mini) {
        return compute(left, top, width, height, height, width, scale, preferredScale,
                safeLeft, safeTop, safeRight, safeBottom, margin, mini);
    }

    static Pose fit(int left, int top, int width, int height, float scale,
            float preferredScale, int safeLeft, int safeTop, int safeRight, int safeBottom,
            float margin, boolean mini) {
        return compute(left, top, width, height, width, height, scale, preferredScale,
                safeLeft, safeTop, safeRight, safeBottom, margin, mini);
    }

    private static Pose compute(int left, int top, int width, int height,
            int newWidth, int newHeight, float scale, float preferredScale,
            int safeLeft, int safeTop, int safeRight, int safeBottom, float margin, boolean mini) {
        if (width <= 0 || height <= 0 || !Float.isFinite(scale) || scale <= 0f
                || !Float.isFinite(preferredScale) || preferredScale <= 0f
                || !Float.isFinite(margin) || margin < 0f) return null;
        final int inset = Math.round(margin);
        final int availableWidth = safeRight - safeLeft - 2 * inset;
        final int availableHeight = safeBottom - safeTop - 2 * inset;
        if (availableWidth <= 0 || availableHeight <= 0) return null;
        final float fit = Math.min(availableWidth / (float) newWidth,
                availableHeight / (float) newHeight);
        final float normal = Math.min(.95f, Math.min(preferredScale, fit));
        // A mini keeps its physical width, rather than suddenly growing when axes are swapped.
        final float nextScale = mini ? Math.min(width * scale / newWidth, fit) : normal;
        final int visibleWidth = Math.round(newWidth * nextScale);
        final int visibleHeight = Math.round(newHeight * nextScale);
        final int x = Math.max(safeLeft + inset, Math.min(safeRight - inset - visibleWidth,
                Math.round(left + width * scale / 2f - visibleWidth / 2f)));
        final int y = Math.max(safeTop + inset,
                Math.min(safeBottom - inset - visibleHeight, top));
        return new Pose(x, y, newWidth, newHeight, nextScale, normal);
    }
}
