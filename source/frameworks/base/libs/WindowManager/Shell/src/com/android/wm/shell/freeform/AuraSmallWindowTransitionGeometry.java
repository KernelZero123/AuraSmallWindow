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
package com.android.wm.shell.freeform;

import android.graphics.Rect;
import android.graphics.RectF;

/** Geometry shared by timed transitions and, later, gesture-owned transition frames. */
public final class AuraSmallWindowTransitionGeometry {
    private final RectF mStart;
    private final RectF mEnd;
    private final int mEndWidth;
    private final int mEndHeight;
    private final int mStartWidth;
    private final int mStartHeight;
    private final float mStartRadius;
    private final float mEndRadius;
    private final float mRootX;
    private final float mRootY;

    /** One reusable result; no Rect or matrix allocation on animation frames. */
    public static final class Frame {
        public final RectF screenBounds = new RectF();
        public float x;
        public float y;
        public float scaleX;
        public float scaleY;
        public float localCornerRadius;
        public float snapshotScaleX;
        public float snapshotScaleY;
        public float snapshotCornerRadius;
    }

    public AuraSmallWindowTransitionGeometry(Rect startLogical, float startScale,
            Rect endLogical, float endScale, float physicalCornerRadius,
            float rootX, float rootY) {
        validate(startLogical, startScale);
        validate(endLogical, endScale);
        if (!Float.isFinite(physicalCornerRadius) || physicalCornerRadius < 0f
                || !Float.isFinite(rootX) || !Float.isFinite(rootY)) {
            throw new IllegalArgumentException("Invalid transition geometry");
        }
        mStart = project(startLogical, startScale);
        mEnd = project(endLogical, endScale);
        mStartWidth = startLogical.width();
        mStartHeight = startLogical.height();
        mEndWidth = endLogical.width();
        mEndHeight = endLogical.height();
        mStartRadius = startScale < 1f ? physicalCornerRadius : 0f;
        mEndRadius = endScale < 1f ? physicalCornerRadius : 0f;
        mRootX = rootX;
        mRootY = rootY;
    }

    private static void validate(Rect bounds, float scale) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0
                || !Float.isFinite(scale) || scale <= 0f || scale > 1f
                || bounds.width() * (double) scale < 1d
                || bounds.height() * (double) scale < 1d) {
            throw new IllegalArgumentException("Invalid task-parent geometry");
        }
    }

    private static RectF project(Rect logical, float scale) {
        // SF's Task position anchors left/top. Scaling those absolute coordinates is wrong.
        return new RectF(logical.left, logical.top,
                logical.left + logical.width() * scale,
                logical.top + logical.height() * scale);
    }

    public void evaluate(float fraction, Frame out) {
        if (!Float.isFinite(fraction) || out == null) {
            throw new IllegalArgumentException("Invalid transition frame");
        }
        final float f = Math.max(0f, Math.min(1f, fraction));
        final RectF visible = out.screenBounds;
        visible.set(lerp(mStart.left, mEnd.left, f), lerp(mStart.top, mEnd.top, f),
                lerp(mStart.right, mEnd.right, f), lerp(mStart.bottom, mEnd.bottom, f));
        out.x = visible.left - mRootX;
        out.y = visible.top - mRootY;
        // Live content uses the ready end canvas; a provided snapshot uses the old canvas.
        out.scaleX = visible.width() / mEndWidth;
        out.scaleY = visible.height() / mEndHeight;
        out.snapshotScaleX = visible.width() / mStartWidth;
        out.snapshotScaleY = visible.height() / mStartHeight;
        final float physicalRadius = lerp(mStartRadius, mEndRadius, f);
        // Different canvases require nonuniform intermediate matrices. Keep both radii bounded
        // by the intended physical radius; steady-state ends are uniform and exact.
        out.localCornerRadius = physicalRadius / Math.max(out.scaleX, out.scaleY);
        out.snapshotCornerRadius = physicalRadius
                / Math.max(out.snapshotScaleX, out.snapshotScaleY);
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }
}
