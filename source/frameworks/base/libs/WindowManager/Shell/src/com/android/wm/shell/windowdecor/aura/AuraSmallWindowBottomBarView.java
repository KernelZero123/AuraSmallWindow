/*
 * Copyright (C) 2026 Aura_A1 Project
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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.wm.shell.R;

/**
 * Bottom handle for the Aura scaled small window. The standalone native host reserves 24dp
 * over app content; its 96x3dp handle is centred 8dp above the bottom. Only a 12dp-high
 * input strip belongs to the handle; resizing starts outside the rounded app corners.
 *
 * Scope of this view (per GLM_SMALL_WINDOW_V2_HANDOFF):
 * <ul>
 *   <li>Hit region only: it accepts events inside the centered handle corridor and lets
 *       everything else fall through. The lower-left/right 44dp physical corner reserve
 *       belongs to the future resize gesture and is never claimed here.</li>
 *   <li>Event pass-through only: raw screen coordinates, pointer id and
 *       {@link android.os.SystemClock#uptimeMillis event time} are handed to the
 *       {@link OnBottomBarEventListener} unchanged. No thresholds, no direction
 *       detection, no scaling geometry - those live in the frozen
 *       {@code AuraWindowInteraction} recognizer (DeepSeek) and Codex-side actions.</li>
 *   <li>Visual feedback only: pressed tint while touched and a preview highlight that
 *       the host enables when a recognizer reports gesture preview.</li>
 * </ul>
 *
 * <p>All geometry constants are PHYSICAL dp multiplied by {@code displayDensity}, which
 * MUST come from a display snapshot (e.g. DisplayLayout density). Never pass the
 * density of the scale-compensated caption resources - that would double-compensate
 * the sizes.</p>
 *
 * <p>The decoration owns mounting and the content bottom inset channel. Gesture policy is
 * deliberately separate; rendering this handle does not enable gesture commits.</p>
 */
public class AuraSmallWindowBottomBarView extends View {

    /** Visible handle size, physical dp. */
    public static final float HANDLE_WIDTH_DP = 96f;
    public static final float HANDLE_HEIGHT_DP = 3f;
    public static final float HANDLE_BOTTOM_CENTER_DP = 8f;
    /** Handle corner radius, physical dp. */
    public static final float HANDLE_RADIUS_DP = 2f;
    /** Compact handle input strip; the rest of the footer passes through. */
    public static final float TOUCH_HEIGHT_DP = 12f;
    /** Lower corner resize zones that this bar must never claim, physical dp each side. */
    public static final float CORNER_RESERVE_DP = 44f;

    /** Gesture phases; values line up with the planned AuraWindowInteraction phases. */
    public static final int PHASE_DOWN = 0;
    public static final int PHASE_MOVE = 1;
    public static final int PHASE_UP = 2;
    public static final int PHASE_CANCEL = 3;

    /** Accessibility custom actions exposed by the handle. */
    // Resource ids cannot collide with framework ACTION_FOCUS (1)/CLEAR_FOCUS (2).
    public static final int ACTION_ID_CLOSE = R.id.aura_small_window_action_close;
    public static final int ACTION_ID_FULLSCREEN = R.id.aura_small_window_action_fullscreen;

    /** Region of the planned AuraWindowInteraction.Region this view reports. */
    public static final String REPORTED_REGION = "BOTTOM";

    /**
     * Receives raw gesture events from the handle corridor. Coordinates are raw screen
     * pixels; time is the MotionEvent event time in uptime millis. Invoked on the main
     * thread. The implementor (host/decor layer, later Codex binding) forwards them to
     * the frozen AuraWindowInteraction pipeline.
     */
    public interface OnBottomBarEventListener {
        void onBottomBarEvent(int phase, float rawX, float rawY, int pointerId,
                long eventTimeMillis, int pointerCount);
    }

    /**
     * Receives accessibility custom actions so they can be routed through the same
     * committed-action paths as gestures. Return true when the action was handled.
     */
    public interface OnBottomBarActionListener {
        boolean onBottomBarAction(int actionId);
    }

    private final float mDisplayDensity;
    private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mHandleRect = new RectF();
    private int mNormalColor;
    private int mPressedColor;
    private int mPreviewColor;

    private OnBottomBarEventListener mEventListener;
    private OnBottomBarActionListener mActionListener;
    private boolean mGesturePreview;
    private boolean mTrackingPointer;
    private int mActivePointerId = -1;
    private boolean mStandaloneHost;

    public AuraSmallWindowBottomBarView(Context context, float displayDensity) {
        this(context, displayDensity, null);
    }

    public AuraSmallWindowBottomBarView(Context context, float displayDensity,
            AttributeSet attrs) {
        super(context, attrs);
        if (displayDensity <= 0f) {
            throw new IllegalArgumentException(
                    "displayDensity must come from a display snapshot and be > 0");
        }
        mDisplayDensity = displayDensity;
        mNormalColor = context.getResources()
                .getColor(R.color.aura_small_window_handle_normal, null);
        mPressedColor = context.getResources()
                .getColor(R.color.aura_small_window_handle_pressed, null);
        mPreviewColor = context.getResources()
                .getColor(R.color.aura_small_window_handle_preview, null);
        setClickable(true);
        setFocusable(false);
        setContentDescription(context.getResources()
                .getString(R.string.aura_small_window_bottom_bar_description));
    }

    /** Change foreground contrast without changing pressed/preview opacity. */
    public void setHandleTint(int color) {
        final int rgb = color & 0x00ffffff;
        final int normal = (Color.alpha(mNormalColor) << 24) | rgb;
        final int pressed = (Color.alpha(mPressedColor) << 24) | rgb;
        final int preview = (Color.alpha(mPreviewColor) << 24) | rgb;
        if (normal == mNormalColor && pressed == mPressedColor && preview == mPreviewColor) {
            return;
        }
        mNormalColor = normal;
        mPressedColor = pressed;
        mPreviewColor = preview;
        invalidate();
    }

    public void setOnBottomBarEventListener(OnBottomBarEventListener listener) {
        mEventListener = listener;
    }

    public void setOnBottomBarActionListener(OnBottomBarActionListener listener) {
        mActionListener = listener;
    }

    /** A dedicated physical-size host has no corner targets within this View's bounds. */
    public void setStandaloneHost(boolean standalone) {
        mStandaloneHost = standalone;
    }

    /**
     * Preview highlight driven by the recognizer's preview output (commit-preview state).
     * Purely visual; the view itself never decides when to preview.
     */
    public void setGesturePreview(boolean preview) {
        if (mGesturePreview != preview) {
            mGesturePreview = preview;
            invalidate();
        }
    }

    /**
     * Width in window-local pixels of the corridor where the bar claims input: centered,
     * excluding both corner reserves. Exposed so the host can wire the corner resize
     * zones without overlapping this bar.
     */
    public int getHitCorridorWidth() {
        return Math.max(0, getWidth() - 2 * cornerReserve());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float handleWidth = HANDLE_WIDTH_DP * mDisplayDensity;
        final float handleHeight = HANDLE_HEIGHT_DP * mDisplayDensity;
        final float radius = HANDLE_RADIUS_DP * mDisplayDensity;
        final float cx = getWidth() / 2f;
        final float cy = mStandaloneHost ? getHeight() - HANDLE_BOTTOM_CENTER_DP * mDisplayDensity
                : getHeight() / 2f;
        mHandleRect.set(cx - handleWidth / 2f, cy - handleHeight / 2f,
                cx + handleWidth / 2f, cy + handleHeight / 2f);
        mHandlePaint.setColor(isPressed() ? mPressedColor
                : mGesturePreview ? mPreviewColor : mNormalColor);
        canvas.drawRoundRect(mHandleRect, radius, radius, mHandlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The corner reserves are excluded from DOWN; events outside the corridor are
        // not consumed so they reach the corner-resize handler or the app below.
        if (!mTrackingPointer && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!isInHitCorridor(event.getX())) {
                return false;
            }
            mTrackingPointer = true;
            mActivePointerId = event.getPointerId(0);
            setPressed(true);
            dispatchToListener(PHASE_DOWN, event, 0);
            return true;
        }
        if (!mTrackingPointer) {
            return false;
        }
        final int index = event.findPointerIndex(mActivePointerId);
        if (index < 0 || event.getPointerCount() != 1) {
            setPressed(false);
            dispatchToListener(PHASE_CANCEL, event, Math.max(index, 0));
            mTrackingPointer = false;
            mActivePointerId = -1;
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                // Single-pointer rule: an extra finger converts the gesture to cancel.
                setPressed(false);
                dispatchToListener(PHASE_CANCEL, event, Math.max(index, 0));
                mTrackingPointer = false;
                mActivePointerId = -1;
                return true;
            case MotionEvent.ACTION_MOVE:
                dispatchToListener(PHASE_MOVE, event, Math.max(index, 0));
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                dispatchToListener(PHASE_UP, event, Math.max(index, 0));
                mTrackingPointer = false;
                mActivePointerId = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
            default:
                setPressed(false);
                dispatchToListener(PHASE_CANCEL, event, Math.max(index, 0));
                mTrackingPointer = false;
                mActivePointerId = -1;
                return true;
        }
    }

    private boolean isInHitCorridor(float localX) {
        final int reserve = cornerReserve();
        return localX >= reserve && localX < getWidth() - reserve;
    }

    private int cornerReserve() {
        return mStandaloneHost ? 0 : Math.round(CORNER_RESERVE_DP * mDisplayDensity);
    }

    private void dispatchToListener(int phase, MotionEvent event, int pointerIndex) {
        if (mEventListener != null && pointerIndex >= 0
                && pointerIndex < event.getPointerCount()) {
            // Raw screen pixels and event time, unchanged; no velocity, no thresholds.
            mEventListener.onBottomBarEvent(phase,
                    event.getRawX(pointerIndex), event.getRawY(pointerIndex),
                    event.getPointerId(pointerIndex), event.getEventTime(), event.getPointerCount());
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return AuraSmallWindowBottomBarView.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setContentDescription(getContext().getResources()
                .getString(R.string.aura_small_window_bottom_bar_description));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ID_CLOSE,
                getContext().getResources()
                        .getString(R.string.aura_small_window_bottom_bar_close_action)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ID_FULLSCREEN,
                getContext().getResources()
                        .getString(R.string.aura_small_window_bottom_bar_fullscreen_action)));
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (mActionListener != null && mActionListener.onBottomBarAction(action)) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
