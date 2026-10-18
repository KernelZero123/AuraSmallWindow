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
 * Tiny assertion helper for the standalone probe: no JUnit, no android, plain {@code java}
 * entry point. Every check increments a counter that the runner prints, so a passing run
 * reports how much was actually verified.
 */
public final class ProbeCheck {

    private static int sAssertions;

    private ProbeCheck() {
    }

    public static int assertions() {
        return sAssertions;
    }

    public static void check(boolean condition, String message) {
        sAssertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equal(Object expected, Object actual, String message) {
        sAssertions++;
        final boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " | expected=" + expected + " actual=" + actual);
        }
    }

    public static void equalInt(int expected, int actual, String message) {
        sAssertions++;
        if (expected != actual) {
            throw new AssertionError(message + " | expected=" + expected + " actual=" + actual);
        }
    }

    public static void equalFloat(float expected, float actual, float tolerance, String message) {
        sAssertions++;
        if (!(Math.abs(expected - actual) <= tolerance)) {
            throw new AssertionError(message + " | expected=" + expected + " actual=" + actual
                    + " tolerance=" + tolerance);
        }
    }

    /** Fails unless the expected exception type is thrown. */
    public static void throwsIllegalArgument(Runnable action, String message) {
        sAssertions++;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message + " | expected an IllegalArgumentException/StateException");
    }

    public static void section(String name) {
        System.out.println("--- " + name);
    }

    public static void pass(String name) {
        System.out.println("PASS " + name + " assertions=" + sAssertions);
    }
}
