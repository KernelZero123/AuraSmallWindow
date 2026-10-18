/* Copyright 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
#include "FrontEnd/RoundedTouchOcclusion.h"
#include "RoundedTouchOcclusionReference.h"
#include <binder/Parcel.h>
#include <gtest/gtest.h>
#include <gui/WindowInfo.h>
#include <ui/Transform.h>
#include <limits>
#include <chrono>

namespace android::surfaceflinger::frontend {

TEST(AuraTouchOcclusion, ScanBandUnionTiming) {
    ui::Transform transform;
    transform.set(.74f, 0.f, 0.f, .74f);
    transform.set(93.25f, 116.27f);
    const auto measure = [&](bool optimized) {
        size_t checksum = 0;
        const auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 300; ++i) {
            const gui::CornerRadii radii(48.f + (i % 90));
            const FloatRect crop(0, 0, 720, 1600);
            const Region local = optimized ? roundedTouchOcclusion(crop, radii)
                                          : reference::roundedTouchOcclusion(crop, radii);
            const Region projected = optimized ? projectTouchOcclusion(local, transform)
                                               : reference::projectTouchOcclusion(local, transform);
            size_t count;
            projected.getArray(&count);
            checksum += count;
        }
        return std::make_pair(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count(), checksum);
    };
    const auto old = measure(false);
    const auto now = measure(true);
    EXPECT_EQ(old.second, now.second);
    printf("300 rounded masks: reference=%lldus optimized=%lldus checksum=%zu\n",
           static_cast<long long>(old.first), static_cast<long long>(now.first), now.second);
}

TEST(AuraTouchOcclusion, OptimizedBandsAndProjectionPreserveOriginalCoverage) {
    for (float radius : {1.f, 24.f, 48.f, 96.f, 240.f, 511.f}) {
        for (const FloatRect crop : {FloatRect(0, 0, 720, 1280),
                                     FloatRect(3.25f, -7.5f, 723.25f, 1592.5f),
                                     FloatRect(-10.75f, 6.1f, 310.25f, 227.8f)}) {
            for (bool asymmetric : {false, true}) {
                gui::CornerRadii radii(radius);
                if (asymmetric) {
                    radii.topLeft.y *= .5f;
                    radii.bottomLeft.x = 0.f;
                    radii.bottomRight.y *= .75f;
                }
                const Region original = reference::roundedTouchOcclusion(crop, radii);
                const Region optimized = roundedTouchOcclusion(crop, radii);
                ASSERT_TRUE(original.hasSameRects(optimized));
                ASSERT_EQ(original.getBounds(), optimized.getBounds());
                for (float scale : {.266667f, .61f, .74f, .95f, 1.f, 1.3f}) {
                    for (uint32_t rotation : {uint32_t(ui::Transform::ROT_0),
                                               uint32_t(ui::Transform::ROT_90),
                                               uint32_t(ui::Transform::ROT_180),
                                               uint32_t(ui::Transform::ROT_270)}) {
                        ui::Transform transform(rotation);
                        ui::Transform resize;
                        resize.set(scale, 0.f, 0.f, scale);
                        resize.set(-93.25f, 116.27f);
                        transform = resize * transform;
                        const Region expected = reference::projectTouchOcclusion(original, transform);
                        const Region actual = projectTouchOcclusion(optimized, transform);
                        ASSERT_TRUE(expected.hasSameRects(actual));
                        ASSERT_EQ(expected.getBounds(), actual.getBounds());
                    }
                }
            }
        }
    }
}

TEST(AuraTouchOcclusion, ActualPhysicalCornersAcrossScalesAndOffsets) {
    for (float scale : {.61f, .70f, .74f, .95f}) {
        for (int x : {-100, 0, 93}) {
            const Region local = roundedTouchOcclusion(FloatRect(0, 0, 720, 1280),
                                                       gui::CornerRadii(48.f / scale));
            ui::Transform transform;
            transform.set(scale, 0.f, 0.f, scale);
            transform.set(float(x), 520.f);
            const Region visible = projectTouchOcclusion(local, transform);
            EXPECT_FALSE(visible.contains(x + 8, 528));
            EXPECT_TRUE(visible.contains(x + 24, 544));
            EXPECT_TRUE(visible.contains(x + 100, 620));
            EXPECT_FALSE(visible.contains(x - 5, 620));
            EXPECT_TRUE(visible.contains(x, 620)); // Negative origins must retain opaque edges.
        }
    }
}

TEST(AuraTouchOcclusion, EllipticalCornersAndFractionalCropRemainConservative) {
    gui::CornerRadii radii(0.f);
    radii.topLeft.x = 60.f;
    radii.topLeft.y = 40.f;
    radii.bottomRight.x = 40.f;
    radii.bottomRight.y = 60.f;
    const FloatRect crop(3.25f, 7.5f, 303.25f, 407.5f);
    const Region region = roundedTouchOcclusion(crop, radii);
    EXPECT_FALSE(region.contains(5, 9));
    EXPECT_TRUE(region.contains(302, 8)); // Square top-right corner.
    EXPECT_FALSE(region.contains(301, 405));
    EXPECT_TRUE(region.contains(4, 406)); // Square bottom-left corner.
    // Every pixel intersecting the mathematical ellipse must remain occluding.
    for (int y = 8; y < 48; ++y) {
        for (int x = 4; x < 64; ++x) {
            const float dx = (x + .5f - (crop.left + 60.f)) / 60.f;
            const float dy = (y + .5f - (crop.top + 40.f)) / 40.f;
            if (dx * dx + dy * dy <= 1.f) EXPECT_TRUE(region.contains(x, y));
        }
    }
}

TEST(AuraTouchOcclusion, InvalidAndUnenforcedClipKeepsRectangularFallback) {
    EXPECT_TRUE(roundedTouchOcclusion(FloatRect(0, 0, 720, 1280),
                                      gui::CornerRadii(0.f)).isEmpty());
    EXPECT_TRUE(roundedTouchOcclusion(FloatRect(0, 0, 720, 100000),
                                      gui::CornerRadii(48.f)).isEmpty());
    EXPECT_TRUE(roundedTouchOcclusion(FloatRect(0, 0, 8192, 8192),
                                      gui::CornerRadii(4096.f)).isEmpty());
    EXPECT_TRUE(roundedTouchOcclusion(FloatRect(0, 0, 720, 1280),
            gui::CornerRadii(std::numeric_limits<float>::quiet_NaN())).isEmpty());
}

TEST(AuraTouchOcclusion, ParcelPreservesIndependentCoverageAndFollowingWindow) {
    gui::WindowInfo first, second;
    first.name = "rounded";
    first.alpha = 1.f;
    first.frame = Rect(0, 0, 720, 1280);
    first.touchableRegion = Region(Rect(100, 100, 200, 200));
    first.touchOcclusionRegion = roundedTouchOcclusion(FloatRect(0, 0, 720, 1280),
                                                       gui::CornerRadii(48.f));
    first.replaceTouchableRegionWithCrop = true;
    first.cloneLayerStackTransform = ui::Transform();
    second.name = "plain";
    second.alpha = 1.f;
    second.frame = Rect(0, 0, 720, 1600);
    Parcel parcel;
    ASSERT_EQ(OK, first.writeToParcel(&parcel));
    ASSERT_EQ(OK, second.writeToParcel(&parcel));
    ASSERT_EQ(OK, parcel.writeInt32(0x12345678));
    parcel.setDataPosition(0);
    gui::WindowInfo actualFirst, actualSecond;
    ASSERT_EQ(OK, actualFirst.readFromParcel(&parcel));
    ASSERT_EQ(OK, actualSecond.readFromParcel(&parcel));
    EXPECT_EQ(first, actualFirst);
    EXPECT_EQ(second, actualSecond);
    EXPECT_EQ(0x12345678, parcel.readInt32());
    EXPECT_FALSE(actualFirst.touchableRegion.contains(350, 600));
    EXPECT_TRUE(actualFirst.touchOcclusionRegion.contains(350, 600));
    EXPECT_FALSE(actualFirst.touchOcclusionRegion.contains(8, 8));
    EXPECT_TRUE(actualSecond.touchOcclusionRegion.isEmpty());
}

} // namespace android::surfaceflinger::frontend
