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
package android.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.Binder;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Cross-process and coalescing regressions for the position-plus-scale commit. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowContainerTransactionAuraTest {
    private final WindowContainerToken mToken = new WindowContainerToken(IWindowContainerToken.Stub.asInterface(new Binder()));

    @Test
    public void parcelPreservesGeometryAndFollowingChange() {
        final WindowContainerToken other = new WindowContainerToken(IWindowContainerToken.Stub.asInterface(new Binder()));
        final Rect bounds = new Rect(80, 120, 800, 1400);
        final WindowContainerTransaction original = new WindowContainerTransaction()
                .setAuraSmallWindowGeometry(mToken, bounds, .68f).setFocusable(other, false);
        final Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final WindowContainerTransaction decoded =
                    WindowContainerTransaction.CREATOR.createFromParcel(parcel);
            assertEquals(0, parcel.dataAvail());
            final WindowContainerTransaction.Change change = decoded.getChanges()
                    .get(mToken.asBinder());
            assertEquals(.68f, change.getAuraSmallWindowScale(), 0f);
            assertEquals(bounds, change.getConfiguration().windowConfiguration.getBounds());
            assertTrue((change.getChangeMask()
                    & WindowContainerTransaction.Change.CHANGE_AURA_SMALL_WINDOW_GEOMETRY) != 0);
            assertEquals(false, decoded.getChanges().get(other.asBinder()).getFocusable());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void mergeUsesLatestGeometryWithoutAliasingBounds() {
        for (boolean transfer : new boolean[] {false, true}) {
            final WindowContainerTransaction first = new WindowContainerTransaction()
                    .setAuraSmallWindowGeometry(mToken, new Rect(40, 90, 760, 1370), .74f);
            final Rect latest = new Rect(60, 100, 780, 1380);
            final WindowContainerTransaction second = new WindowContainerTransaction()
                    .setAuraSmallWindowGeometry(mToken, latest, .63f);
            first.merge(second, transfer);
            latest.offset(500, 500);
            final WindowContainerTransaction.Change change = first.getChanges()
                    .get(mToken.asBinder());
            assertEquals(.63f, change.getAuraSmallWindowScale(), 0f);
            assertEquals(new Rect(60, 100, 780, 1380),
                    change.getConfiguration().windowConfiguration.getBounds());
        }
    }

    @Test
    public void ordinaryChangeDoesNotEraseScaleDuringMerge() {
        final WindowContainerTransaction transaction = new WindowContainerTransaction()
                .setAuraSmallWindowGeometry(mToken, new Rect(0, 0, 720, 1280), .61f);
        transaction.merge(new WindowContainerTransaction().setFocusable(mToken, false), false);
        assertEquals(.61f, transaction.getChanges().get(mToken.asBinder())
                .getAuraSmallWindowScale(), 0f);
        assertEquals(false, transaction.getChanges().get(mToken.asBinder()).getFocusable());
    }

    @Test
    public void parcelAndMergePreserveMiniStateAndClearOnOrdinaryResize() {
        final WindowContainerTransaction original = new WindowContainerTransaction()
                .setAuraSmallWindowGeometry(mToken, new Rect(16, 112, 736, 1392), .267f, true);
        final Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final WindowContainerTransaction decoded =
                    WindowContainerTransaction.CREATOR.createFromParcel(parcel);
            assertEquals(0, parcel.dataAvail());
            assertTrue(decoded.getChanges().get(mToken.asBinder()).getAuraSmallWindowMini());
            decoded.merge(new WindowContainerTransaction().setFocusable(mToken, false), false);
            assertTrue(decoded.getChanges().get(mToken.asBinder()).getAuraSmallWindowMini());
            decoded.merge(new WindowContainerTransaction().setAuraSmallWindowGeometry(mToken,
                    new Rect(93, 212, 813, 1492), .74f), false);
            assertEquals(false, decoded.getChanges().get(mToken.asBinder()).getAuraSmallWindowMini());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void invalidArgumentsDoNotLeavePartialBoundsChange() {
        for (float scale : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE,
                0f, -1f, .96f}) {
            final WindowContainerTransaction transaction = new WindowContainerTransaction();
            assertThrows(IllegalArgumentException.class, () -> transaction
                    .setAuraSmallWindowGeometry(mToken, new Rect(0, 0, 720, 1280), scale));
            assertTrue(transaction.isEmpty());
        }
        final WindowContainerTransaction transaction = new WindowContainerTransaction();
        assertThrows(IllegalArgumentException.class, () -> transaction
                .setAuraSmallWindowGeometry(mToken, new Rect(), .74f));
        assertTrue(transaction.isEmpty());
    }
}
