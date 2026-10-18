/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.wm.shell.windowdecor;

/** Physical outer arc of the lower task corners; never covers a pixel inside the app shape. */
final class AuraSmallWindowCornerHitTest {
    private AuraSmallWindowCornerHitTest() {}

    static int findRegion(float left, float top, float width, float height, float density,
            float x, float y) {
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(width)
                || !Float.isFinite(height) || !Float.isFinite(density) || !Float.isFinite(x)
                || !Float.isFinite(y) || width <= 0f || height <= 0f || density <= 0f) return -1;
        final float radius = Math.min(24f * density, Math.min(width, height) / 2f);
        final float outer = radius + 12f * density;
        final float cy = top + height - radius;
        if (y < cy) return -1;
        final float leftCx = left + radius;
        final float rightCx = left + width - radius;
        if (x <= leftCx && inArc(x - leftCx, y - cy, radius, outer)) return 1;
        if (x >= rightCx && inArc(x - rightCx, y - cy, radius, outer)) return 2;
        return -1;
    }

    private static boolean inArc(float dx, float dy, float radius, float outer) {
        final float distanceSquared = dx * dx + dy * dy;
        // A pixel of clearance avoids grabbing the antialiased app boundary.
        return distanceSquared >= (radius + 1f) * (radius + 1f)
                && distanceSquared <= outer * outer;
    }
}
