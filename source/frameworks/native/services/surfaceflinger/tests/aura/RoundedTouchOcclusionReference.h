/*
 * Copyright 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <algorithm>
#include <cmath>
#include <gui/CornerRadii.h>
#include <ui/FloatRect.h>
#include <ui/Region.h>
#include <ui/Transform.h>

namespace android::surfaceflinger::frontend::reference {

// Conservative pixel coverage of an SF-enforced clip, independent of client input regions.
// Empty means no usable clip; the caller retains the ordinary rectangular occlusion frame.
inline Region roundedTouchOcclusion(const FloatRect& crop, const gui::CornerRadii& radii) {
    constexpr float kCoordinateLimit = 1000000.f;
    if (radii.isEmpty() || !std::isfinite(crop.left) || !std::isfinite(crop.top) ||
        !std::isfinite(crop.right) || !std::isfinite(crop.bottom) ||
        std::abs(crop.left) > kCoordinateLimit || std::abs(crop.right) > kCoordinateLimit ||
        std::abs(crop.top) > kCoordinateLimit || std::abs(crop.bottom) > kCoordinateLimit ||
        crop.getWidth() <= 0.f || crop.getHeight() <= 0.f || crop.getHeight() > 8192.f) {
        return {};
    }
    struct Corner { float x; float y; };
    const auto corner = [&](float x, float y) {
        return Corner{std::clamp(x, 0.f, crop.getWidth() / 2.f),
                      std::clamp(y, 0.f, crop.getHeight() / 2.f)};
    };
    const float values[] = {radii.topLeft.x, radii.topLeft.y, radii.topRight.x,
                           radii.topRight.y, radii.bottomLeft.x, radii.bottomLeft.y,
                           radii.bottomRight.x, radii.bottomRight.y};
    for (float value : values) {
        if (!std::isfinite(value)) return {};
    }
    const auto tl = corner(radii.topLeft.x, radii.topLeft.y);
    const auto tr = corner(radii.topRight.x, radii.topRight.y);
    const auto bl = corner(radii.bottomLeft.x, radii.bottomLeft.y);
    const auto br = corner(radii.bottomRight.x, radii.bottomRight.y);
    // Region unions have a cost per corner row. Bound work for pathological client surfaces;
    // the conservative rectangular fallback keeps the existing touch protection in that case.
    if (std::max(tl.y, tr.y) + std::max(bl.y, br.y) > 1024.f) return {};
    const int top = static_cast<int>(std::floor(crop.top));
    const int bottom = static_cast<int>(std::ceil(crop.bottom));
    const int middleTop = static_cast<int>(std::ceil(crop.top + std::max(tl.y, tr.y)));
    const int middleBottom = static_cast<int>(std::floor(crop.bottom - std::max(bl.y, br.y)));
    const auto inset = [](Corner c, float center, int y) {
        if (c.x <= 0.f || c.y <= 0.f) return 0.f;
        // Use the closest point in this pixel row, then round coverage outwards.
        const float distance = std::abs(center - std::clamp(center, float(y), float(y + 1)));
        const float ratio = std::clamp(distance / c.y, 0.f, 1.f);
        return c.x * (1.f - std::sqrt(std::max(0.f, 1.f - ratio * ratio)));
    };
    Region result;
    for (int y = top; y < bottom;) {
        if (y >= middleTop && y < middleBottom) {
            result.orSelf(Rect(static_cast<int>(std::floor(crop.left)), y,
                               static_cast<int>(std::ceil(crop.right)), middleBottom));
            y = middleBottom;
            continue;
        }
        const float leftInset = y + 1 <= crop.top + tl.y
                ? inset(tl, crop.top + tl.y, y)
                : y >= crop.bottom - bl.y ? inset(bl, crop.bottom - bl.y, y) : 0.f;
        const float rightInset = y + 1 <= crop.top + tr.y
                ? inset(tr, crop.top + tr.y, y)
                : y >= crop.bottom - br.y ? inset(br, crop.bottom - br.y, y) : 0.f;
        const int left = static_cast<int>(std::floor(crop.left + leftInset));
        const int right = static_cast<int>(std::ceil(crop.right - rightInset));
        if (left < right) result.orSelf(Rect(left, y, right, y + 1));
        ++y;
    }
    return result;
}

// Round each transformed coverage rectangle outwards, including negative translations.
// Nearest-pixel rounding of an input origin can omit a real opaque edge, which is unsuitable
// for the independent visual-security mask. Invalid projection keeps rectangular protection.
inline Region projectTouchOcclusion(const Region& local, const ui::Transform& transform) {
    Region result;
    for (const Rect& rect : local) {
        const FloatRect projected = transform.transform(
                FloatRect(rect.left, rect.top, rect.right, rect.bottom));
        const float values[] = {projected.left, projected.top, projected.right, projected.bottom};
        for (float value : values) {
            if (!std::isfinite(value) || std::abs(value) > 1000000000.f) return {};
        }
        const Rect coverage(static_cast<int>(std::floor(projected.left)),
                            static_cast<int>(std::floor(projected.top)),
                            static_cast<int>(std::ceil(projected.right)),
                            static_cast<int>(std::ceil(projected.bottom)));
        if (!coverage.isEmpty()) result.orSelf(coverage);
    }
    return result;
}

} // namespace android::surfaceflinger::frontend
