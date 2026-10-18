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

package com.android.wm.shell.windowdecor.aura;

/**
 * Minimal standalone entry point for the Aura small window gesture/resize algorithm.
 *
 * <p>Run with a plain JDK against the {@code patches/AuraSmallWindow} sources plus this probe
 * directory. No Soong, no APK, no device, no {@code android.*} runtime. Prints
 * {@code ALL PASS assertions=N} and exits 0 on success, or prints the failure and exits 1.
 */
public final class GestureProbeMain {

    private GestureProbeMain() {
    }

    public static void main(String[] args) {
        System.out.println("Aura small window gesture/resize probe (pure JDK)");
        try {
            ProbeCheck.section("protocol objects");
            ProtocolTests.snapshotCarriesTheDocumentedFields();
            ProtocolTests.snapshotFactoryUsesThePlanInterval();
            ProtocolTests.snapshotCopiesAreIndependent();
            ProtocolTests.rectArithmeticIsHandCheckable();
            ProtocolTests.pointerEventFactoriesCarryEveryField();
            ProtocolTests.phaseOrdinalsMatchTheAdapterConstants();
            ProtocolTests.intentAndRegionHelpers();
            ProbeCheck.pass("ProtocolTests");

            ProbeCheck.section("bottom bar gestures");
            BottomBarGestureTests.tapDoesNotAct();
            BottomBarGestureTests.belowSlopIsIgnored();
            BottomBarGestureTests.swipeUpDismissesOnlyWhenTheThresholdIsReached();
            BottomBarGestureTests.slowSubThresholdSwipeUpDoesNotClose();
            BottomBarGestureTests.swipeDownGoesFullscreenOnly();
            BottomBarGestureTests.releaseGeometryIsContinuousWithThePreview();
            BottomBarGestureTests.longHorizontalDragIsRejected();
            BottomBarGestureTests.multiFingerNeverActs();
            BottomBarGestureTests.cancelPathsRestoreTheSnapshotAndAllowANewGesture();
            BottomBarGestureTests.rapidReentryDiscardsThePreviousGesture();
            BottomBarGestureTests.cornersNeverDismissOrGoFullscreen();
            BottomBarGestureTests.bottomRegionNeverResizes();
            BottomBarGestureTests.cornerTapDoesNotResize();
            BottomBarGestureTests.cornerCancelRestoresAndAllowsANewResize();
            BottomBarGestureTests.strayEventsAndContractViolations();
            ProbeCheck.pass("BottomBarGestureTests");

            ProbeCheck.section("thresholds, speed and cancellation");
            SpeedAndSpeedThresholdTests.thresholdsComeFromDpExactlyOnce();
            SpeedAndSpeedThresholdTests.flingCommitsBelowTheDistanceThreshold();
            SpeedAndSpeedThresholdTests.highSpeedLowDisplacementNeverCommits();
            SpeedAndSpeedThresholdTests.degenerateTimeDeltasNeverThrow();
            SpeedAndSpeedThresholdTests.velocityIsMeasuredInPixelsPerSecond();
            SpeedAndSpeedThresholdTests.terminalPointerSampleGovernsTheVelocity();
            SpeedAndSpeedThresholdTests.reversalAfterResolutionPreventsCommit();
            SpeedAndSpeedThresholdTests.singleUpFlickCommitsWithoutAnyMove();
            SpeedAndSpeedThresholdTests.thresholdsAreParametersNotHiddenConstants();
            SpeedAndSpeedThresholdTests.dismissPreviewTravelIsCapped();
            ProbeCheck.pass("SpeedAndSpeedThresholdTests");

            ProbeCheck.section("resize geometry");
            ResizeGeometryTests.diagonalProjectionIsExact();
            ResizeGeometryTests.perpendicularDragDoesNotResize();
            ResizeGeometryTests.diagonalDeltaMapsLinearly();
            ResizeGeometryTests.leftRightSymmetry();
            ResizeGeometryTests.minPhysicalWidthFloorIsDensityCorrect();
            ResizeGeometryTests.everyLimitIsAppliedAsAScalarClamp();
            ResizeGeometryTests.anchorAndAspectRatioSurviveClamping();
            ResizeGeometryTests.restoreReportsTheUntouchedSnapshot();
            ResizeGeometryTests.uncommittedResizeNeverPollutesTheSnapshot();
            ResizeGeometryTests.progressIsMonotonicInScale();
            ResizeGeometryTests.unsupportedRegionAndBadArguments();
            ProbeCheck.pass("ResizeGeometryTests");

            ProbeCheck.section("resize boundaries and degradation");
            ResizeBoundaryTests.safeAreaEdgesStopExactlyOnTheBoundary();
            ResizeBoundaryTests.minAboveMaxAndEmptySafeAreaDegrade();
            ResizeBoundaryTests.degenerateLogicalSizesDegrade();
            ResizeBoundaryTests.startRectPartlyOutsideKeepsTheAnchor();
            ResizeBoundaryTests.landscapeAndSmallScreens();
            ResizeBoundaryTests.gridNeverEmitsIllegalGeometry();
            ProbeCheck.pass("ResizeBoundaryTests");

            System.out.println("ALL PASS assertions=" + ProbeCheck.assertions());
        } catch (Throwable failure) {
            System.err.println("FAIL after assertions=" + ProbeCheck.assertions() + ": " + failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
