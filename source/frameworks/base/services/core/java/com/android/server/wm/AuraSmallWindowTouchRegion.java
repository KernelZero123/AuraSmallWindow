/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wm;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;

/** A task-local rounded input mask; the parent SurfaceControl transform projects it to screen. */
final class AuraSmallWindowTouchRegion {
    private AuraSmallWindowTouchRegion() {}

    static Region create(Rect logicalBounds, float localCornerRadius) {
        if (!Float.isFinite(localCornerRadius) || localCornerRadius < 0f) {
            throw new IllegalArgumentException("Invalid local corner radius");
        }
        if (logicalBounds.width() <= 0 || logicalBounds.height() <= 0) return new Region();
        if (localCornerRadius == 0f) return new Region(logicalBounds);
        // Rasterize at the local origin first, then integer-translate. This keeps the mask
        // invariant when a task moves and avoids large absolute coordinates losing precision.
        final Path path = new Path();
        path.addRoundRect(new RectF(0, 0, logicalBounds.width(), logicalBounds.height()),
                localCornerRadius, localCornerRadius, Path.Direction.CW);
        final Region result = new Region();
        result.setPath(path, new Region(0, 0, logicalBounds.width(), logicalBounds.height()));
        result.translate(logicalBounds.left, logicalBounds.top);
        return result;
    }
}
