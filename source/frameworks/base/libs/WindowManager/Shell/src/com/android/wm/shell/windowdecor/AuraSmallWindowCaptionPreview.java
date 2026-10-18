/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wm.shell.windowdecor;

import android.view.SurfaceControl;
import android.view.View;
import android.widget.TextView;

import com.android.wm.shell.R;

/**
 * P2 caption compensation for an already laid-out, fixed-canvas Aura header. No ViewHost relayout
 * is performed on gesture frames. This draft is unused until the owner/lifecycle hooks are bound.
 * A new committed scale must rebind the normal caption and reset its native matrix in that draw;
 * do not reset this object between UP and Core's corresponding TaskInfo/layout confirmation.
 */
final class AuraSmallWindowCaptionPreview {
    private final View mCaption;
    private final SurfaceControl mSurface;
    private final float mBaselineScale;
    private final float mDensity;
    private final int mLogicalWidth;
    private final View mIcon;
    private final TextView mTitle;
    private final View mMore;
    private final int mInitialTitleMax;

    AuraSmallWindowCaptionPreview(View caption, SurfaceControl surface, float scale,
            float displayDensity, int logicalWidth) {
        if (caption == null || surface == null || !surface.isValid()
                || !Float.isFinite(scale) || scale <= 0f || scale >= 1f
                || !Float.isFinite(displayDensity) || displayDensity <= 0f || logicalWidth <= 0) {
            throw new IllegalArgumentException("Invalid laid-out caption snapshot");
        }
        mCaption = caption;
        mSurface = surface;
        mBaselineScale = scale;
        mDensity = displayDensity;
        mLogicalWidth = logicalWidth;
        mIcon = caption.findViewById(R.id.aura_app_icon);
        mTitle = caption.findViewById(R.id.aura_app_title);
        mMore = caption.findViewById(R.id.aura_more_button);
        mInitialTitleMax = mTitle == null ? 0 : mTitle.getMaxWidth();
    }

    void apply(SurfaceControl.Transaction transaction, float scale) {
        if (!mSurface.isValid() || !Float.isFinite(scale) || scale <= 0f || scale > .95f) {
            throw new IllegalArgumentException("Invalid caption preview scale");
        }
        final float factor = mBaselineScale / scale;
        // Task scale changes both axes. Keep the native header height physical while its width
        // follows the task; compensate only individual controls horizontally.
        transaction.setMatrix(mSurface, 1f, 0f, 0f, factor);
        compensate(mIcon, factor, false);
        compensate(mTitle, factor, false);
        compensate(mMore, factor, true);
        if (mTitle != null) {
            final int maximum = Float.compare(scale, mBaselineScale) == 0 ? mInitialTitleMax
                    : Math.round(Math.min(120f * mDensity,
                            Math.max(0f, mLogicalWidth * scale - 120f * mDensity))
                            / mBaselineScale);
            if (mTitle.getMaxWidth() != maximum) mTitle.setMaxWidth(maximum);
        }
    }

    private void compensate(View view, float factor, boolean end) {
        if (view == null) return;
        final boolean anchoredLeft = end == (mCaption.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        view.setPivotX(anchoredLeft ? 0f : view.getWidth());
        view.setScaleX(factor);
        view.setTranslationX(anchoredLeft ? view.getLeft() * (factor - 1f)
                : (mLogicalWidth - view.getRight()) * (1f - factor));
    }
}
