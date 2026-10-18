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

package com.android.wm.shell.windowdecor;

import android.hardware.input.InputManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;
import java.util.function.Supplier;
import android.os.Handler;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.util.SparseBooleanArray;

import com.android.wm.shell.windowdecor.aura.AuraSmallWindowBottomBarView;

/**
 * Observes pointers and recognizes resize starts in the outer ring of a rounded lower corner.
 * Only an accepted outer-corner DOWN pilfers its stream. All app-content touches remain passive.
 * No full-width input overlay and no task-cropped corner hosts are needed.
 */
final class AuraSmallWindowPointerMonitor {
    private static final String NAME = "AuraSmallWindowPointerMonitor";

    private final AuraSmallWindowInteractionController mController;
    private final InputMonitor mInputMonitor;
    private final Receiver mReceiver;
    private final Supplier<RunningTaskInfo> mTaskInfo;
    private final float mDensity;
    private int mOuterRegion = -1;
    private int mOuterPointerId = -1;
    private final SparseBooleanArray mPointers = new SparseBooleanArray();

    private int mDeviceId = -1;
    private int mSource;
    private int mHostPointerId = -1;
    private int mHostRegion = -1;
    private boolean mClosed;

    AuraSmallWindowPointerMonitor(InputManager inputManager, int displayId, Handler handler,
            AuraSmallWindowInteractionController controller,
            Supplier<RunningTaskInfo> taskInfo, float density) {
        if (inputManager == null || handler == null || controller == null || taskInfo == null
                || !Float.isFinite(density) || density <= 0f) {
            throw new IllegalArgumentException("InputManager, Handler, controller and snapshot required");
        }
        mController = controller;
        mTaskInfo = taskInfo;
        mDensity = density;
        mInputMonitor = inputManager.monitorGestureInput(NAME, displayId);
        if (mInputMonitor == null) {
            throw new IllegalStateException("Unable to create passive input monitor");
        }
        final InputChannel channel = mInputMonitor.getInputChannel();
        if (channel == null) {
            mInputMonitor.dispose();
            throw new IllegalStateException("Input monitor has no channel");
        }
        mReceiver = new Receiver(channel, handler.getLooper());
    }

    /**
     * Returns whether the host DOWN may begin an interaction. The sole monitor pointer is allowed
     * when it is this same pointer; any other held pointer means a multi-touch stream is active.
     */
    boolean canStart(int pointerId) {
        if (mClosed || pointerId < 0) return false;
        if (mPointers.size() == 0) return true;
        return mPointers.size() == 1 && mPointers.get(pointerId);
    }

    /** Record the host's DOWN/UP/CANCEL so monitor-first and host-first delivery both work. */
    void onHostEvent(int region, int phase, int pointerId, long timeMillis) {
        if (mClosed) return;
        if (phase == AuraSmallWindowBottomBarView.PHASE_DOWN) {
            mHostPointerId = pointerId;
            mHostRegion = region;
            // The host asks canStart() before beginning. This callback only records owner
            // identity; treating the host's primary DOWN as external would cancel itself.
            return;
        }
        if (pointerId == mHostPointerId
                && phase == AuraSmallWindowBottomBarView.PHASE_CANCEL) {
            mHostPointerId = -1;
            mHostRegion = -1;
        } else if (pointerId == mHostPointerId
                && phase == AuraSmallWindowBottomBarView.PHASE_UP) {
            mHostPointerId = -1;
            mHostRegion = -1;
        }
    }

    void close() {
        if (mClosed) return;
        mClosed = true;
        mReceiver.dispose();
        mInputMonitor.dispose();
        mPointers.clear();
        mHostPointerId = -1;
        mHostRegion = -1;
        mController.onExternalPointersLost();
    }

    private void onInputEvent(InputEvent inputEvent) {
        if (!(inputEvent instanceof MotionEvent)) return;
        final MotionEvent event = (MotionEvent) inputEvent;
        if ((event.getSource() & android.view.InputDevice.SOURCE_CLASS_POINTER) == 0) return;
        final int action = event.getActionMasked();
        handleOuterCorner(event);
        final int deviceId = event.getDeviceId();
        final int source = event.getSource();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (mDeviceId != -1 && (mDeviceId != deviceId || mSource != source)) {
                resetOldStream(event.getEventTime());
            }
            if (mDeviceId == -1) {
                mDeviceId = deviceId;
                mSource = source;
            }
            final int id = event.getPointerId(event.getActionIndex());
            final boolean alreadyKnown = mPointers.get(id);
            mPointers.put(id, true);
            // The first DOWN only establishes the candidate primary pointer. Every additional
            // pointer is foreign, even when it lands outside all window decoration hosts.
            if (!alreadyKnown && mPointers.size() > 1) {
                mController.onExternalPointerDown(id);
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            final int id = event.getPointerId(event.getActionIndex());
            if (mPointers.get(id)) {
                mPointers.delete(id);
                mController.onExternalPointerUp(id);
            }
            if (mPointers.size() == 0) clearStream();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            if (mController.isInteractionActive() && mHostPointerId >= 0 && mHostRegion >= 0) {
                mController.onEvent(mHostRegion, AuraSmallWindowBottomBarView.PHASE_CANCEL,
                        0f, 0f, mHostPointerId, event.getEventTime(), 1);
            }
            clearStream();
        }
    }

    private void handleOuterCorner(MotionEvent event) {
        final int action = event.getActionMasked();
        if (mOuterRegion >= 0) {
            final int index = event.findPointerIndex(mOuterPointerId);
            final boolean cancel = index < 0 || event.getPointerCount() != 1
                    || event.getDeviceId() != mDeviceId || event.getSource() != mSource
                    || action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_CANCEL;
            final int phase = cancel ? AuraSmallWindowBottomBarView.PHASE_CANCEL
                    : action == MotionEvent.ACTION_UP ? AuraSmallWindowBottomBarView.PHASE_UP
                    : AuraSmallWindowBottomBarView.PHASE_MOVE;
            if (index >= 0) {
                mController.onEvent(mOuterRegion, phase, event.getRawX(index), event.getRawY(index),
                        mOuterPointerId, event.getEventTime(), event.getPointerCount());
            } else {
                mController.onEvent(mOuterRegion, phase, 0f, 0f, mOuterPointerId,
                        event.getEventTime(), 0);
            }
            if (cancel || action == MotionEvent.ACTION_UP) {
                mOuterRegion = -1;
                mOuterPointerId = -1;
                mHostRegion = -1;
                mHostPointerId = -1;
            }
            return;
        }
        if (action != MotionEvent.ACTION_DOWN || event.getPointerCount() != 1
                || mPointers.size() != 0 || mController.isInteractionActive()) return;
        final RunningTaskInfo info = mTaskInfo.get();
        if (!CaptionWindowDecoration.isAuraSmallWindowTask(info) || !info.isVisible
                || info.auraSmallWindowMini) return;
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        final int region = AuraSmallWindowCornerHitTest.findRegion(bounds.left, bounds.top,
                bounds.width() * info.auraSmallWindowScale,
                bounds.height() * info.auraSmallWindowScale,
                info.configuration.densityDpi > 0 ? info.configuration.densityDpi / 160f : mDensity,
                event.getRawX(), event.getRawY());
        if (region < 0) return;
        final int pointer = event.getPointerId(0);
        mController.onEvent(region, AuraSmallWindowBottomBarView.PHASE_DOWN,
                event.getRawX(), event.getRawY(), pointer, event.getEventTime(), 1);
        // A busy transition/IME owner may refuse DOWN. Never steal in that case.
        if (!mController.isInteractionActive()) return;
        mOuterRegion = region;
        mOuterPointerId = pointer;
        onHostEvent(region, AuraSmallWindowBottomBarView.PHASE_DOWN, pointer, event.getEventTime());
        mInputMonitor.pilferPointers();
    }

    private void resetOldStream(long timeMillis) {
        if (mController.isInteractionActive() && mHostPointerId >= 0 && mHostRegion >= 0) {
            mController.onEvent(mHostRegion, AuraSmallWindowBottomBarView.PHASE_CANCEL,
                    0f, 0f, mHostPointerId, timeMillis, 1);
        }
        mPointers.clear();
        clearStream();
    }

    private void clearStream() {
        mOuterRegion = -1;
        mOuterPointerId = -1;
        mPointers.clear();
        mDeviceId = -1;
        mSource = 0;
        mController.onExternalPointersLost();
    }

    private final class Receiver extends InputEventReceiver {
        Receiver(InputChannel channel, android.os.Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                AuraSmallWindowPointerMonitor.this.onInputEvent(event);
            } finally {
                finishInputEvent(event, false);
            }
        }
    }
}
