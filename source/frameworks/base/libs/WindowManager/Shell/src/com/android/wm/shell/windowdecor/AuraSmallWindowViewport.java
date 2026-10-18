/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.wm.shell.windowdecor;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;

/** Mini keeps the app's normal layout and clips chrome, rather than stretching its content. */
public final class AuraSmallWindowViewport {
    private AuraSmallWindowViewport() {}
    public static float decorScale(RunningTaskInfo info) {
        return info.auraSmallWindowMini && info.auraSmallWindowDecorScale > 0f
                ? info.auraSmallWindowDecorScale : info.auraSmallWindowScale;
    }
    public static Rect miniCrop(int width, int height, float density, float decorScale) {
        final int top = Math.min(height - 1, (int) Math.ceil(28f * density / decorScale));
        final int bottom = Math.max(top + 1, height - (int) Math.ceil(24f * density / decorScale));
        return new Rect(0, top, width, bottom);
    }
    public static Rect crop(RunningTaskInfo info) {
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        return info.auraSmallWindowMini ? miniCrop(bounds.width(), bounds.height(),
                info.configuration.densityDpi / 160f, decorScale(info))
                : new Rect(0, 0, bounds.width(), bounds.height());
    }
}
