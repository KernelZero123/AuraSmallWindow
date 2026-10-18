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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.app.ActivityManager.RunningTaskInfo;
import android.os.SystemClock;
import android.window.WindowContainerToken;
import android.os.SystemProperties;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;
import android.window.TransitionInfo;

import com.android.wm.shell.windowdecor.AuraSmallWindowViewport;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.transition.Transitions;

import java.util.function.Consumer;

/** Timed mode changes only. Gesture previews and IME ownership are integrated separately. */
public final class AuraSmallWindowTransitionAnimation {
    private static GestureStart sGestureStart;

    private static final class GestureStart {
        WindowContainerToken token;
        int taskId, displayId, rotation, width, height;
        float left, top, visibleWidth, visibleHeight;
        boolean dismiss, dismissSettled;
        long created;
    }

    /** Main-thread, one-shot pose transfer; carries no SurfaceControl or app capture. */
    public static void registerGestureStart(RunningTaskInfo info, float left, float top,
            float width, float height, boolean dismiss) {
        if (info == null || info.token == null || !Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0f || height <= 0f) return;
        final GestureStart pose = new GestureStart();
        pose.token = info.token; pose.taskId = info.taskId; pose.displayId = info.displayId;
        pose.rotation = info.configuration.windowConfiguration.getRotation();
        final Rect canvas = info.configuration.windowConfiguration.getBounds();
        pose.width = canvas.width(); pose.height = canvas.height();
        pose.left = left; pose.top = top; pose.visibleWidth = width; pose.visibleHeight = height;
        pose.dismiss = dismiss; pose.created = SystemClock.uptimeMillis();
        sGestureStart = pose;
    }

    public static void clearGestureStart(WindowContainerToken token) {
        if (sGestureStart != null && sGestureStart.token.equals(token)) sGestureStart = null;
    }

    /** The interaction owner already animated the old canvas fully out of view. */
    public static void registerSettledDismiss(RunningTaskInfo info) {
        final Rect bounds = info.configuration.windowConfiguration.getBounds();
        registerGestureStart(info, bounds.left, bounds.top,
                bounds.width() * info.auraSmallWindowScale,
                bounds.height() * info.auraSmallWindowScale, true);
        if (sGestureStart != null && sGestureStart.token.equals(info.token)) {
            sGestureStart.dismissSettled = true;
        }
    }
    private final DisplayController mDisplays;

    AuraSmallWindowTransitionAnimation(DisplayController displays) {
        mDisplays = displays;
    }

    Animator prepare(TransitionInfo info, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, Consumer<Animator> onEnd) {
        if (SystemProperties.getBoolean("ro.aura.small_window", false)) {
            for (TransitionInfo.Change change : info.getChanges()) {
                if (info.getChanges().stream().filter(c -> c.getTaskInfo() != null
                        && TransitionInfo.isIndependent(c, info)).count() == 1) {
                    final Animator mini = prepareMini(change, info, startT, finishT, onEnd);
                    if (mini != null) return mini;
                }
            }
            final Animator gesture = prepareGesture(info, startT, finishT, onEnd);
            if (gesture != null) return gesture;
            final Animator opening = prepareOpeningOrMini(info, startT, finishT, onEnd);
            if (opening != null) return opening;
        }
        if (!SystemProperties.getBoolean("ro.aura.small_window", false)
                || !SystemProperties.getBoolean("persist.wm.debug.aura_v2_transitions", false)
                || info.getRootCount() == 0
                || (info.getType() != TRANSIT_CHANGE
                        && info.getType() != Transitions.TRANSIT_MAXIMIZE
                        && info.getType() != Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE)) {
            return null;
        }
        TransitionInfo.Change selected = null;
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() == null || !TransitionInfo.isIndependent(change, info)) {
                continue;
            }
            // Do not consume a mixed transition: another independent task still needs its
            // original handler, even when this phone window participates in the same batch.
            if (selected != null || !isModeChange(change)) return null;
            selected = change;
        }
        if (selected == null || selected.getParent() != null) return null;
        final DisplayLayout layout = mDisplays.getDisplayLayout(DEFAULT_DISPLAY);
        if (layout == null) return null;
        final InsetsState insets = mDisplays.getInsetsState(DEFAULT_DISPLAY);
        final InsetsSource ime = insets == null ? null : insets.peekSource(InsetsSource.ID_IME);
        // Until a gesture/IME ownership transfer exists, leave keyboard-visible transitions
        // on the existing path; never race a live IME lift with a second surface animator.
        if (ime != null && ime.isVisible()) return null;

        final TransitionInfo.Change change = selected;
        final Rect start = new Rect(change.getStartAbsBounds());
        final Rect end = new Rect(change.getEndAbsBounds());
        if (start.isEmpty() || end.isEmpty()) return null;
        final float startScale = change.getAuraSmallWindowStartScale();
        final float endScale = change.getAuraSmallWindowEndScale();
        final TransitionInfo.Root root = TransitionUtil.getRootFor(change, info);
        final AuraSmallWindowTransitionGeometry geometry =
                new AuraSmallWindowTransitionGeometry(start, startScale, end, endScale,
                        24f * layout.density(), root.getOffset().x, root.getOffset().y);
        final AuraSmallWindowTransitionGeometry.Frame frame =
                new AuraSmallWindowTransitionGeometry.Frame();
        final SurfaceControl leash = change.getLeash();
        final SurfaceControl snapshot = change.getSnapshot() != null
                && change.getSnapshot().isValid() ? change.getSnapshot() : null;
        // Use only the snapshot supplied by WM's normal secure/protected capture policy.
        // No additional capture or application visibility changes are made here.
        startT.reparent(leash, root.getLeash()).show(leash).setAlpha(leash, 1f);
        if (snapshot != null) {
            startT.reparent(snapshot, root.getLeash())
                    .setRelativeLayer(snapshot, leash, 1).show(snapshot).setAlpha(snapshot, 1f);
            finishT.hide(snapshot).reparent(snapshot, null);
        }
        geometry.evaluate(0f, frame);
        apply(startT, leash, snapshot, frame, start, end, 0f);
        // Core finishT restores the task's parent/position. Its final crop and scale must match
        // the ready end canvas rather than the source buffer used at the first frame.
        finishT.setMatrix(leash, endScale, 0f, 0f, endScale)
                .setWindowCrop(leash, end.width(), end.height()).setAlpha(leash, 1f)
                .setCornerRadius(leash, endScale < 1f ? 24f * layout.density() / endScale : 0f);

        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(endScale < 1f ? 220 : 260);
        animator.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animator.addUpdateListener(value -> {
            final float fraction = (float) value.getAnimatedValue();
            geometry.evaluate(fraction, frame);
            apply(transaction, leash, snapshot, frame, start, end, fraction);
            transaction.apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mFinished;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mFinished) return;
                mFinished = true;
                transaction.close();
                onEnd.accept(animation);
            }
        });
        return animator;
    }

    /** One task's mini surface, kept outside Quickstep's app targets during recents. */
    public static Animator prepareMini(TransitionInfo.Change change, TransitionInfo info,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            Consumer<Animator> onEnd) {
        final RunningTaskInfo task = change.getTaskInfo();
        if (!SystemProperties.getBoolean("ro.aura.small_window", false) || task == null
                || !task.auraSmallWindowMini || !task.isFreeform()
                || task.displayId != DEFAULT_DISPLAY || !TransitionInfo.isIndependent(change, info)
                || change.getStartRotation() != change.getEndRotation()
                || change.getStartDisplayId() != change.getEndDisplayId()
                || !change.getLeash().isValid()) return null;
        final Rect start = change.getStartAbsBounds(), end = change.getEndAbsBounds();
        final float ss = change.getAuraSmallWindowStartScale();
        final float es = change.getAuraSmallWindowEndScale();
        if (start.isEmpty() || end.isEmpty() || ss <= 0f || es <= 0f || es >= 1f
                || start.width() != end.width() || start.height() != end.height()) return null;
        final SurfaceControl leash = change.getLeash();
        final TransitionInfo.Root root = TransitionUtil.getRootFor(change, info);
        final Rect crop = AuraSmallWindowViewport.crop(task);
        final float density = task.configuration.densityDpi / 160f;
        final boolean changing = ss != es || !start.equals(end);
        startT.reparent(leash, root.getLeash()).setLayer(leash, info.getChanges().size() * 4 + 1)
                .show(leash).setAlpha(leash, 1f);
        finishT.setMatrix(leash, es, 0, 0, es).setWindowCrop(leash, crop)
                .setCornerRadius(leash, 24f * density / es).setAlpha(leash, 1f);
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(changing ? 300 : 0);
        animator.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        final Consumer<Float> pose = f -> {
            final float scale = ss + (es - ss) * f;
            tx.setPosition(leash, start.left + (end.left - start.left) * f - root.getOffset().x,
                    start.top + (end.top - start.top) * f - root.getOffset().y)
                    .setMatrix(leash, scale, 0, 0, scale)
                    .setWindowCrop(leash, new Rect(0, Math.round(crop.top * f), crop.right,
                            end.height() - Math.round((end.height() - crop.bottom) * f)))
                    .setCornerRadius(leash, 24f * density / scale).setAlpha(leash, 1f).show(leash);
        };
        // An existing mini is already cropped; do not flash its hidden chrome back into view.
        if (!changing) {
            startT.setPosition(leash, end.left - root.getOffset().x,
                    end.top - root.getOffset().y).setMatrix(leash, es, 0, 0, es)
                    .setWindowCrop(leash, crop).setCornerRadius(leash, 24f * density / es);
        } else {
            startT.setPosition(leash, start.left - root.getOffset().x,
                    start.top - root.getOffset().y).setMatrix(leash, ss, 0, 0, ss)
                    .setWindowCrop(leash, end.width(), end.height());
        }
        animator.addUpdateListener(value -> { pose.accept(changing
                ? (float) value.getAnimatedValue() : 1f); tx.apply(); });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean done;
            @Override public void onAnimationEnd(Animator animation) {
                if (done) return; done = true;
                if (leash.isValid()) { pose.accept(1f); tx.apply(); }
                tx.close(); onEnd.accept(animation);
            }
        });
        android.util.Log.i("AuraAutoMini", "animate task=" + task.taskId + " changed=" + changing);
        return animator;
    }

    /** Opening and mini restore use the ready task buffers, never a delayed app relaunch. */
    private Animator prepareOpeningOrMini(TransitionInfo info, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, Consumer<Animator> onEnd) {
        if (info.getRootCount() == 0) return null;
        final int type = info.getType();
        if (type != android.view.WindowManager.TRANSIT_OPEN && type != TRANSIT_TO_FRONT
                && type != android.view.WindowManager.TRANSIT_CLOSE
                && type != TRANSIT_CHANGE && type != Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE) {
            return null;
        }
        final DisplayLayout layout = mDisplays.getDisplayLayout(DEFAULT_DISPLAY);
        if (layout == null) return null;
        TransitionInfo.Change selected = null;
        for (TransitionInfo.Change change : info.getChanges()) {
            final RunningTaskInfo task = change.getTaskInfo();
            if (task == null || !TransitionInfo.isIndependent(change, info)) continue;
            final float endScale = change.getAuraSmallWindowEndScale();
            if (task.displayId == DEFAULT_DISPLAY
                    && task.getActivityType() == ACTIVITY_TYPE_STANDARD
                    && task.isFreeform() && endScale > 0f && endScale < 1f
                    && change.getParent() == null) {
                if (selected != null) return null;
                selected = change;
            }
        }
        if (selected == null || selected.getTaskInfo().auraSmallWindowMini
                || selected.getStartRotation() != selected.getEndRotation()
                || selected.getStartDisplayId() != selected.getEndDisplayId()) return null;
        final TransitionInfo.Change change = selected;
        final Rect end = new Rect(change.getEndAbsBounds());
        if (end.isEmpty() || !change.getLeash().isValid()) return null;
        final float endScale = change.getAuraSmallWindowEndScale();
        float startScale = change.getAuraSmallWindowStartScale();
        final Rect start = new Rect(change.getStartAbsBounds());
        final boolean miniRestore = startScale * start.width() < 220f * layout.density()
                && startScale < endScale && start.width() == end.width()
                && start.height() == end.height();
        final boolean orderingOnly = (type == TRANSIT_TO_FRONT || type == TRANSIT_CHANGE)
                && (change.getMode() == TRANSIT_TO_FRONT
                || change.getMode() == TRANSIT_CHANGE) && start.equals(end)
                && startScale == endScale;
        final boolean opening = change.getMode() == android.view.WindowManager.TRANSIT_OPEN
                || (change.getMode() == TRANSIT_TO_FRONT && !orderingOnly);
        final boolean modeRestore = startScale == 1f && endScale < 1f
                && (type == Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE || type == TRANSIT_CHANGE);
        final boolean canvasRotation = startScale < 1f && endScale < 1f && !start.isEmpty()
                && start.width() != end.width()
                && start.width() == end.height() && start.height() == end.width();
        // Only the canvas's axis change belongs here during an activity CLOSE.
        // Normal in-window back navigation retains its original activity animation.
        if (type == android.view.WindowManager.TRANSIT_CLOSE && !canvasRotation) return null;
        if (!miniRestore && !opening && !modeRestore && !orderingOnly && !canvasRotation) return null;
        for (TransitionInfo.Change other : info.getChanges()) {
            if (other == change || other.getTaskInfo() == null
                    || !TransitionInfo.isIndependent(other, info)) continue;
            if (other.getTaskInfo().getActivityType() == ACTIVITY_TYPE_STANDARD
                    && (other.getMode() != android.view.WindowManager.TRANSIT_TO_BACK
                    || !other.getStartAbsBounds().equals(other.getEndAbsBounds())
                    || other.getAuraSmallWindowStartScale()
                        != other.getAuraSmallWindowEndScale())) return null;
        }
        final boolean fadeIn = opening && !modeRestore && !miniRestore;
        if (fadeIn) {
            // ColorOS grows the surface while fading it in. Anchor the virtual source about the
            // target center; app content and decoration remain on the same uniformly scaled task.
            startScale = endScale * .65f;
            start.set(end.left + Math.round(end.width() * (endScale - startScale) / 2f),
                    end.top + Math.round(end.height() * (endScale - startScale) / 2f), 0, 0);
            start.right = start.left + end.width(); start.bottom = start.top + end.height();
        }
        if (start.isEmpty()) return null;
        final TransitionInfo.Root root = TransitionUtil.getRootFor(change, info);
        final AuraSmallWindowTransitionGeometry geometry = new AuraSmallWindowTransitionGeometry(
                start, startScale, end, endScale, 24f * layout.density(),
                root.getOffset().x, root.getOffset().y);
        final AuraSmallWindowTransitionGeometry.Frame frame =
                new AuraSmallWindowTransitionGeometry.Frame();
        final SurfaceControl leash = change.getLeash();
        final SurfaceControl snapshot = !fadeIn && change.getSnapshot() != null
                && change.getSnapshot().isValid() ? change.getSnapshot() : null;
        startT.reparent(leash, root.getLeash()).show(leash);
        if (snapshot != null) {
            startT.reparent(snapshot, root.getLeash()).setRelativeLayer(snapshot, leash, 1)
                    .show(snapshot).setAlpha(snapshot, 1f);
            finishT.hide(snapshot).reparent(snapshot, null);
        }
        geometry.evaluate(0f, frame);
        apply(startT, leash, snapshot, frame, start, end, 0f);
        final Rect miniStartCrop = miniRestore ? AuraSmallWindowViewport.miniCrop(
                end.width(), end.height(), layout.density(),
                change.getTaskInfo().auraSmallWindowDecorScale) : null;
        if (miniStartCrop != null) startT.setWindowCrop(leash, miniStartCrop);
        startT.setAlpha(leash, fadeIn ? 0f : 1f);
        finishT.setMatrix(leash, endScale, 0f, 0f, endScale)
                .setWindowCrop(leash, end.width(), end.height()).setAlpha(leash, 1f)
                .setCornerRadius(leash, 24f * layout.density() / endScale);
        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(orderingOnly ? 0 : 350);
        animator.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animator.addUpdateListener(value -> {
            final float fraction = (float) value.getAnimatedValue();
            geometry.evaluate(fraction, frame);
            apply(transaction, leash, snapshot, frame, start, end, fraction);
            if (miniStartCrop != null) transaction.setWindowCrop(leash,
                    new Rect(0, Math.round(miniStartCrop.top * (1f - fraction)), end.width(),
                            end.height() - Math.round((end.height() - miniStartCrop.bottom)
                                    * (1f - fraction))));
            transaction.setAlpha(leash, fadeIn ? Math.min(1f, fraction / .6f) : 1f).apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mFinished;
            @Override public void onAnimationEnd(Animator animation) {
                if (mFinished) return;
                mFinished = true; transaction.close(); onEnd.accept(animation);
            }
        });
        android.util.Log.i("AuraWindowEnter", "task=" + change.getTaskInfo().taskId
                + " miniRestore=" + miniRestore + " opening=" + opening
                + " orderingOnly=" + orderingOnly);
        return animator;
    }

    /** Continues from the final finger pose using the newly ready mode-change buffers.
     * ColorOS FlexibleTaskScaleManager uses zero-bounce response .35s for both exit/fullscreen,
     * exit scale 1 -> .3 about the visible center, and alpha responses .3s/.2s respectively. */
    private Animator prepareGesture(TransitionInfo info, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, Consumer<Animator> onEnd) {
        final GestureStart pose = sGestureStart;
        if (pose == null) return null;
        if (SystemClock.uptimeMillis() - pose.created > 5000L) {
            sGestureStart = null;
            return null;
        }
        TransitionInfo.Change target = null;
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() != null && pose.token.equals(change.getContainer())) {
                target = change; break;
            }
        }
        if (target == null) return null;
        // An unrelated metadata CHANGE for this task must not consume the pending handoff.
        if (pose.dismiss ? (info.getType() != android.view.WindowManager.TRANSIT_CLOSE
                        && info.getType() != Transitions.TRANSIT_MINIMIZE)
                : info.getType() != Transitions.TRANSIT_MAXIMIZE) return null;
        sGestureStart = null; // Matching transition consumes the pose once, including refusals.
        final RunningTaskInfo task = target.getTaskInfo();
        if (pose.dismissSettled && task.taskId == pose.taskId
                && task.displayId == pose.displayId && pose.displayId == DEFAULT_DISPLAY
                && task.getActivityType() == ACTIVITY_TYPE_STANDARD
                && target.getLeash().isValid()) {
            // Core may have obtained a fullscreen buffer or started hiding the keyboard by
            // now. Neither participates visually: the held-buffer exit has already completed.
            startT.hide(target.getLeash());
            finishT.hide(target.getLeash()).setAlpha(target.getLeash(), 1f);
            if (target.getSnapshot() != null && target.getSnapshot().isValid()) {
                startT.hide(target.getSnapshot());
                finishT.hide(target.getSnapshot()).reparent(target.getSnapshot(), null);
            }
            final ValueAnimator settled = ValueAnimator.ofFloat(0f, 1f);
            settled.setDuration(0L);
            settled.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { onEnd.accept(animation); }
            });
            android.util.Log.i("AuraGestureFinish", "task=" + pose.taskId
                    + " dismiss=true alreadySettled=true");
            return settled;
        }
        if ((pose.dismiss ? (info.getType() != android.view.WindowManager.TRANSIT_CLOSE
                        && info.getType() != Transitions.TRANSIT_MINIMIZE)
                : info.getType() != Transitions.TRANSIT_MAXIMIZE)
                || info.getRootCount() == 0 || task.taskId != pose.taskId
                || task.displayId != pose.displayId || pose.displayId != DEFAULT_DISPLAY
                || task.getActivityType() != ACTIVITY_TYPE_STANDARD || target.getParent() != null
                || target.getStartRotation() != pose.rotation
                || target.getEndRotation() != pose.rotation
                || target.getStartDisplayId() != target.getEndDisplayId()
                || target.getStartAbsBounds().width() != pose.width
                || target.getStartAbsBounds().height() != pose.height
                || (pose.dismiss ? (target.getMode() != android.view.WindowManager.TRANSIT_CLOSE
                        && target.getMode() != android.view.WindowManager.TRANSIT_TO_BACK
                        && target.getMode() != TRANSIT_CHANGE)
                    : target.getAuraSmallWindowEndScale() != 1f)) return null;
        final DisplayLayout layout = mDisplays.getDisplayLayout(pose.displayId);
        if (layout == null) return null;
        final InsetsState insets = mDisplays.getInsetsState(pose.displayId);
        final InsetsSource ime = insets == null ? null : insets.peekSource(InsetsSource.ID_IME);
        // Preserve the existing keyboard-visible mode path rather than race a new IME lift.
        if (ime != null && ime.isVisible()) return null;
        final TransitionInfo.Change change = target;
        final Rect end = new Rect(change.getEndAbsBounds());
        if ((!pose.dismiss && end.isEmpty()) || !change.getLeash().isValid()) return null;
        for (TransitionInfo.Change other : info.getChanges()) {
            if (other != change && other.getTaskInfo() != null
                    && TransitionInfo.isIndependent(other, info)
                    && other.getTaskInfo().getActivityType() == ACTIVITY_TYPE_STANDARD) {
                // Maximize reorders the previously focused fullscreen app TO_BACK; close may
                // reveal it TO_FRONT. These unchanged background tasks need no separate animator.
                final int expected = pose.dismiss ? android.view.WindowManager.TRANSIT_TO_FRONT
                        : android.view.WindowManager.TRANSIT_TO_BACK;
                if (other.getMode() != expected
                        || other.getTaskInfo().displayId != pose.displayId
                        || !other.getStartAbsBounds().equals(other.getEndAbsBounds())
                        || other.getStartRotation() != other.getEndRotation()
                        || other.getAuraSmallWindowStartScale()
                            != other.getAuraSmallWindowEndScale()) return null;
            }
        }
        final TransitionInfo.Root root = TransitionUtil.getRootFor(change, info);
        final SurfaceControl leash = change.getLeash();
        final SurfaceControl snapshot = change.getSnapshot() != null
                && change.getSnapshot().isValid() ? change.getSnapshot() : null;
        // Snapshot is supplied by WM, respecting protected/secure capture policy.
        startT.reparent(leash, root.getLeash()).show(leash);
        if (snapshot != null) {
            startT.reparent(snapshot, root.getLeash()).setRelativeLayer(snapshot, leash, 1)
                    .show(snapshot);
            finishT.hide(snapshot).reparent(snapshot, null);
        }
        if (pose.dismiss) finishT.hide(leash).setAlpha(leash, 1f);
        else finishT.setMatrix(leash, 1f, 0f, 0f, 1f).setAlpha(leash, 1f)
                .setWindowCrop(leash, end.width(), end.height()).setCornerRadius(leash, 0f);
        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(350L);
        animator.setInterpolator(input -> input);
        final Consumer<Float> frame = fraction -> {
            final float geometry = criticalResponse(fraction, .35f);
            final float alpha = criticalResponse(fraction, pose.dismiss ? .30f : .20f);
            applyGesture(transaction, leash, snapshot, pose, end, root, layout.density(),
                    geometry, alpha);
            transaction.apply();
        };
        applyGesture(startT, leash, snapshot, pose, end, root, layout.density(), 0f, 0f);
        animator.addUpdateListener(value -> frame.accept((float) value.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mFinished;
            @Override public void onAnimationEnd(Animator animation) {
                if (mFinished) return;
                mFinished = true; transaction.close(); onEnd.accept(animation);
            }
        });
        android.util.Log.i("AuraGestureFinish", "task=" + pose.taskId + " dismiss=" + pose.dismiss
                + " start=" + pose.left + "," + pose.top + "," + pose.visibleWidth + ","
                + pose.visibleHeight + " snapshot=" + (snapshot != null));
        return animator;
    }

    public static float criticalResponse(float fraction, float response) {
        final double omega = 2d * Math.PI / response;
        final double time = Math.max(0f, Math.min(1f, fraction)) * .35d;
        final double end = 1d - (1d + omega * .35d) * Math.exp(-omega * .35d);
        return (float) ((1d - (1d + omega * time) * Math.exp(-omega * time)) / end);
    }

    private static void applyGesture(SurfaceControl.Transaction tx, SurfaceControl leash,
            SurfaceControl snapshot, GestureStart pose, Rect end, TransitionInfo.Root root,
            float density, float fraction, float alphaProgress) {
        final float relativeScale = 1f - .7f * fraction;
        final float width = pose.dismiss ? pose.visibleWidth * relativeScale
                : pose.visibleWidth + (end.width() - pose.visibleWidth) * fraction;
        final float height = pose.dismiss ? pose.visibleHeight * relativeScale
                : pose.visibleHeight + (end.height() - pose.visibleHeight) * fraction;
        final float left = pose.dismiss ? pose.left + (pose.visibleWidth - width) / 2f
                : pose.left + (end.left - pose.left) * fraction;
        final float top = pose.dismiss ? pose.top + (pose.visibleHeight - height) / 2f
                : pose.top + (end.top - pose.top) * fraction;
        final float radius = 24f * density * (pose.dismiss ? relativeScale : 1f - fraction);
        final int canvasWidth = pose.dismiss ? pose.width : end.width();
        final int canvasHeight = pose.dismiss ? pose.height : end.height();
        final float sx = width / canvasWidth, sy = height / canvasHeight;
        final float alpha = pose.dismiss ? 1f - alphaProgress : alphaProgress;
        tx.setPosition(leash, left - root.getOffset().x, top - root.getOffset().y)
                .setMatrix(leash, sx, 0f, 0f, sy).setWindowCrop(leash, canvasWidth, canvasHeight)
                .setCornerRadius(leash, radius / Math.max(sx, sy))
                .setAlpha(leash, pose.dismiss || snapshot != null ? alpha : 1f);
        if (snapshot != null) {
            final float oldX = width / pose.width, oldY = height / pose.height;
            tx.setPosition(snapshot, left - root.getOffset().x, top - root.getOffset().y)
                    .setMatrix(snapshot, oldX, 0f, 0f, oldY)
                    .setWindowCrop(snapshot, pose.width, pose.height)
                    .setCornerRadius(snapshot, radius / Math.max(oldX, oldY))
                    .setAlpha(snapshot, 1f - alphaProgress);
        }
    }

    private static boolean isModeChange(TransitionInfo.Change change) {
        final float start = change.getAuraSmallWindowStartScale();
        final float end = change.getAuraSmallWindowEndScale();
        return change.getTaskInfo().displayId == DEFAULT_DISPLAY
                && change.getTaskInfo().getActivityType() == ACTIVITY_TYPE_STANDARD
                && change.getStartDisplayId() == change.getEndDisplayId()
                && change.getStartRotation() == change.getEndRotation()
                && (change.getMode() == TRANSIT_CHANGE || change.getMode() == TRANSIT_TO_FRONT)
                && ((start < 1f && end == 1f) || (start == 1f && end < 1f));
    }

    private static void apply(SurfaceControl.Transaction t, SurfaceControl leash,
            SurfaceControl snapshot, AuraSmallWindowTransitionGeometry.Frame frame,
            Rect start, Rect end, float fraction) {
        t.setPosition(leash, frame.x, frame.y)
                .setMatrix(leash, frame.scaleX, 0f, 0f, frame.scaleY)
                .setWindowCrop(leash, end.width(), end.height())
                .setCornerRadius(leash, frame.localCornerRadius);
        if (snapshot != null) {
            t.setPosition(snapshot, frame.x, frame.y)
                    .setMatrix(snapshot, frame.snapshotScaleX, 0f, 0f, frame.snapshotScaleY)
                    .setWindowCrop(snapshot, start.width(), start.height())
                    .setCornerRadius(snapshot, frame.snapshotCornerRadius)
                    .setAlpha(snapshot, 1f - Math.max(0f, (fraction - .65f) / .35f));
        }
    }
}
