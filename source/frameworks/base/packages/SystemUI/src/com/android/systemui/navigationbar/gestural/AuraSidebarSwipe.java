/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.navigationbar.gestural;

/** Per-pointer state. This only runs after the normal edge-back admission checks. */
final class AuraSidebarSwipe {
    private float startX, startY, threshold, hysteresis;
    private int pointerId;
    private boolean eligible, left, armed;
    private long downTime, candidateTime = -1;
    private float maxSpeed;
    private final long[] times = new long[32];
    private final float[] positions = new float[32];
    private int historyStart, historyCount;

    void begin(boolean allowed, boolean onLeft, int id, float x, float y,
            float density, int screenWidth, long eventTime) {
        eligible = allowed && Float.isFinite(x) && Float.isFinite(y)
                && Float.isFinite(density) && density > 0 && screenWidth > 0 && eventTime >= 0;
        left = onLeft;
        pointerId = id;
        startX = x;
        startY = y;
        threshold = Math.min(132f * density, screenWidth * .42f);
        hysteresis = 16f * density;
        armed = false;
        maxSpeed = 850f * density;
        downTime = eventTime;
        candidateTime = -1;
        historyStart = historyCount = 0;
        record(x, eventTime);
    }

    boolean update(int id, int count, float x, float y, long eventTime) {
        if (!eligible || id != pointerId || count != 1
                || !Float.isFinite(x) || !Float.isFinite(y)
                || eventTime < times[(historyStart + historyCount - 1) % times.length]) {
            reset();
            return false;
        }
        float inward = left ? x - startX : startX - x;
        float vertical = Math.abs(y - startY);
        boolean inRange = inward >= (armed ? threshold - hysteresis : threshold)
                && vertical < inward * .75f;
        record(x, eventTime);
        if (!inRange) {
            candidateTime = -1;
            armed = false;
            return false;
        }
        if (candidateTime < 0) candidateTime = eventTime;
        return evaluate(eventTime);
    }

    /** Holding a stationary finger must show the preview before UP, without fake input events. */
    boolean evaluate(long now) {
        if (!eligible || candidateTime < 0) return false;
        int newest = (historyStart + historyCount - 1) % times.length;
        int reference = historyStart;
        for (int i = 0; i < historyCount; i++) {
            int index = (historyStart + i) % times.length;
            if (times[index] <= now - 64) reference = index;
            else {
                if (index != newest) reference = index;
                break;
            }
        }
        long interval = now - times[reference];
        float speed = interval > 0
                ? Math.abs(positions[newest] - positions[reference]) * 1000f / interval
                : Float.POSITIVE_INFINITY;
        // A fast release never becomes a sidebar launch just because it crossed the distance.
        armed = now - downTime >= 240 && now - candidateTime >= 80 && speed <= maxSpeed;
        return armed;
    }

    private void record(float x, long time) {
        int index = (historyStart + historyCount) % times.length;
        if (historyCount == times.length) historyStart = (historyStart + 1) % times.length;
        else historyCount++;
        times[index] = time;
        positions[index] = x;
    }

    boolean isWaitingForPreview() { return eligible && candidateTime >= 0 && !armed; }
    boolean isEligible() { return eligible; }
    boolean isArmed() { return armed; }
    void reset() { eligible = false; armed = false; candidateTime = -1; }
}
