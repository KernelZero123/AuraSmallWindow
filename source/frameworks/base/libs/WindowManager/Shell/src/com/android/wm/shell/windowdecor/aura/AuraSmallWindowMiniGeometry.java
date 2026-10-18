/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.android.wm.shell.windowdecor.aura;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Intent;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Rect;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Region;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Result;
import com.android.wm.shell.windowdecor.aura.AuraWindowInteraction.Snapshot;
/** Explicit display-only geometry, separate from ordinary corner resize limits. */
public final class AuraSmallWindowMiniGeometry {
    private AuraSmallWindowMiniGeometry() {}
    public static boolean crossesThreshold(Snapshot snapshot, Region region,
            float grabX, float grabY, float x, float y) {
        if (!region.isCorner()) return false;
        final float requested = AuraWindowResizeGeometry.projectedScale(snapshot, region,
                grabX, grabY, x, y);
        // 24dp of deliberate overshoot beyond the normal 220dp floor. Reversing before UP
        // removes the request; an ordinary minimum-size resize must remain usable.
        return requested * snapshot.logicalWidth < 196f * snapshot.density;
    }
    public static Result preview(Snapshot snapshot, Result resize, float requested) {
        final float floor = 220f * snapshot.density / snapshot.logicalWidth;
        if (!resize.region.isCorner() || requested >= floor
                || resize.intent != Intent.PREVIEW_RESIZE) return resize;
        final float scale = Math.max(96f * snapshot.density / snapshot.logicalWidth,
                floor - (floor - requested) * .35f);
        return new Result(resize.intent, resize.region, scale,
                AuraWindowResizeGeometry.rectForScale(snapshot, resize.region, scale),
                resize.progress, false, false, true, false, resize.resize);
    }
    public static Result commit(Snapshot snapshot, Result resize) {
        final Rect safe = snapshot.safeRect;
        final int margin = Math.round(8f * snapshot.density);
        final float scale = Math.min(96f * snapshot.density / snapshot.logicalWidth,
                Math.min(Math.max(1, safe.width() - 2 * margin) / (float) snapshot.logicalWidth,
                        Math.max(1, safe.height() - 2 * margin) / (float) snapshot.logicalHeight));
        final int width = Math.round(snapshot.logicalWidth * scale);
        final int height = Math.round(snapshot.logicalHeight * scale);
        final boolean left = snapshot.visibleRect.left + snapshot.visibleRect.width() / 2
                < safe.left + safe.width() / 2 - snapshot.density;
        final int x = left ? safe.left + margin : safe.right - margin - width;
        final int y = safe.top + margin - Math.round(
                (float) Math.ceil(28f * snapshot.density / snapshot.scale) * scale);
        return new Result(Intent.COMMIT_RESIZE, resize.region, scale,
                new Rect(x, y, x + width, y + height), 1f, true, false, false, false,
                resize.resize);
    }
}
