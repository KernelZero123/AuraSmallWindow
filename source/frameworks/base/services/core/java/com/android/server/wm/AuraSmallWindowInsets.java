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

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.InsetsSource;
import android.view.InsetsState;

/** Projects the display IME into the logical coordinates of a uniformly scaled phone task. */
final class AuraSmallWindowInsets {
    private AuraSmallWindowInsets() {}

    static InsetsState projectIme(InsetsState state, Rect taskBounds, float scale) {
        if (!(scale > 0f && scale < 1f)) return state;
        final InsetsSource source = state.peekSource(InsetsSource.ID_IME);
        if (source == null || source.getFrame().isEmpty()) return state;

        // Insets states and sources are shared with other windows. Only replace our IME copy;
        // caption sources are already task-local, and the display's IME leash stays unscaled.
        final InsetsState result = new InsetsState(state);
        final InsetsSource projected = new InsetsSource(source);
        final Rect originalFrame = source.getFrame();
        final Rect projectedFrame = inverseFrame(originalFrame, taskBounds, scale);
        projected.setFrame(projectedFrame);
        if (source.getVisibleFrame() != null) {
            projected.setVisibleFrame(inverseFrame(source.getVisibleFrame(), taskBounds, scale));
        }
        final Rect[] boundingRects = source.getBoundingRects();
        if (boundingRects != null) {
            final Rect[] projectedRects = new Rect[boundingRects.length];
            for (int i = 0; i < boundingRects.length; i++) {
                final Rect absolute = new Rect(boundingRects[i]);
                absolute.offset(originalFrame.left, originalFrame.top);
                projectedRects[i] = inverseFrame(absolute, taskBounds, scale);
                projectedRects[i].offset(-projectedFrame.left, -projectedFrame.top);
            }
            projected.setBoundingRects(projectedRects);
        }
        final Insets attached = source.getAttachedInsets();
        if (attached != null) {
            projected.setAttachedInsets(Insets.of(
                    (int) Math.ceil(attached.left / scale),
                    (int) Math.ceil(attached.top / scale),
                    (int) Math.ceil(attached.right / scale),
                    (int) Math.ceil(attached.bottom / scale)));
        }
        result.addSource(projected);
        return result;
    }

    private static Rect inverseFrame(Rect frame, Rect taskBounds, float scale) {
        // Round outwards so a fractional boundary cannot leave content underneath the IME.
        final int left = (int) Math.floor(taskBounds.left
                + (frame.left - taskBounds.left) / scale);
        final int top = (int) Math.floor(taskBounds.top
                + (frame.top - taskBounds.top) / scale);
        return new Rect(left, top,
                frame.width() == 0 ? left : (int) Math.ceil(taskBounds.left
                        + (frame.right - taskBounds.left) / scale),
                frame.height() == 0 ? top : (int) Math.ceil(taskBounds.top
                        + (frame.bottom - taskBounds.top) / scale));
    }
}
