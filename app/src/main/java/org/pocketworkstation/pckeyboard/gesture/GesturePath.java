/*
 * Copyright (C) 2026 Yaroslav Bezzubets
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard.gesture;

/**
 * The touch points of one gesture, in keyboard pixels (the coordinates of the keys).
 * Points closer than {@link GestureParams#MIN_POINT_DIST_PX} to the previous one are
 * dropped; past {@link #MAX_POINTS} every other point is dropped, so a long gesture keeps
 * its whole shape.
 */
public final class GesturePath {
    public static final int MAX_POINTS = 1024;

    private float[] mX;
    private float[] mY;
    private long[] mT;
    private int mSize;

    public GesturePath(int capacity) {
        capacity = Math.max(4, Math.min(capacity, MAX_POINTS));
        mX = new float[capacity];
        mY = new float[capacity];
        mT = new long[capacity];
    }

    public void reset() {
        mSize = 0;
    }

    /** Adds a point unless it is too close to the previous one. */
    public void add(float x, float y, long time) {
        if (mSize > 0) {
            final float dx = x - mX[mSize - 1];
            final float dy = y - mY[mSize - 1];
            final float min = GestureParams.MIN_POINT_DIST_PX;
            if (dx * dx + dy * dy < min * min) return;
        }
        if (mSize == mX.length) {
            if (mSize >= MAX_POINTS) {
                halve();
            } else {
                grow();
            }
        }
        mX[mSize] = x;
        mY[mSize] = y;
        mT[mSize] = time;
        mSize++;
    }

    private void grow() {
        final int capacity = Math.min(mX.length * 2, MAX_POINTS);
        mX = java.util.Arrays.copyOf(mX, capacity);
        mY = java.util.Arrays.copyOf(mY, capacity);
        mT = java.util.Arrays.copyOf(mT, capacity);
    }

    // Keep the first point and every other one after it
    private void halve() {
        int j = 1;
        for (int i = 2; i < mSize; i += 2, j++) {
            mX[j] = mX[i];
            mY[j] = mY[i];
            mT[j] = mT[i];
        }
        mSize = j;
    }

    public int size() {
        return mSize;
    }

    public float getX(int i) {
        return mX[i];
    }

    public float getY(int i) {
        return mY[i];
    }

    public long getTime(int i) {
        return mT[i];
    }

    /** Length of the polyline through the points, in pixels. */
    public float length() {
        float len = 0;
        for (int i = 1; i < mSize; i++) {
            len += (float) Math.hypot(mX[i] - mX[i - 1], mY[i] - mY[i - 1]);
        }
        return len;
    }

    public GesturePath copy() {
        GesturePath p = new GesturePath(Math.max(4, mSize));
        System.arraycopy(mX, 0, p.mX, 0, mSize);
        System.arraycopy(mY, 0, p.mY, 0, mSize);
        System.arraycopy(mT, 0, p.mT, 0, mSize);
        p.mSize = mSize;
        return p;
    }
}
