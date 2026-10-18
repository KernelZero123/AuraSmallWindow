/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.android.wm.shell.freeform;

import android.graphics.Rect;

/** Screen-pixel IME lift geometry, independent of task/surface lifecycle. */
public final class AuraSmallWindowImeGeometry {
    private AuraSmallWindowImeGeometry() {}

    public static int calculateTargetTop(Rect logicalBounds, float scale, int imeTop,
            int safeTop, float density, boolean landscape) {
        final int gap = Math.round((landscape ? 10 : 20) * density);
        final int visibleHeight = (int) Math.ceil(logicalBounds.height() * scale);
        // Keep the window's entire logical size. When it cannot fit between the status bar
        // and keyboard, cap movement at the safe top and leave app Insets to handle the rest.
        return Math.min(logicalBounds.top,
                Math.max(safeTop, imeTop - gap - visibleHeight));
    }
}
