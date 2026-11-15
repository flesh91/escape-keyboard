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

package org.pocketworkstation.pckeyboard;

import org.pocketworkstation.pckeyboard.gesture.GesturePath;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * The trail a gesture leaves over the keys: the last few hundred milliseconds of the path,
 * thinner and fainter the older they are, fading out after the finger lifts. It is coloured
 * by the theme's palette, blending from the head to the tail, and each colour sits on a darker
 * edge of itself, like the side of a keycap, so it shows on light and dark keys alike. It lives in the
 * overlay of the keyboard's parent, so redrawing it does not redraw the keys (the keyboard
 * view is a software layer by default). Points are in key coordinates; setOffset moves them
 * to where the keys are drawn.
 */
final class GestureTrailDrawable extends Drawable {
    private static final int MAX_POINTS = 64;
    private static final long FADE_MS = 350;
    // How dark the edge under the trail is against its colour
    private static final float EDGE_SHADE = 0.68f;
    private static final long FRAME_MS = 16;

    private final float[] mX = new float[MAX_POINTS];
    private final float[] mY = new float[MAX_POINTS];
    private final long[] mT = new long[MAX_POINTS];
    private int mFirst;
    private int mCount;
    // Points of the path already taken in
    private int mSeen;
    private boolean mActive;
    private float mOffsetX;
    private float mOffsetY;
    private final float mMaxWidth;
    private final float mMinWidth;
    private final float mEdgeWidth;
    private int mAlpha = 0xFF;
    private int[] mColors = { 0xFF33B5E5 };
    private int[] mEdgeColors = { shade(0xFF33B5E5) };
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable mNextFrame = new Runnable() {
        public void run() {
            invalidateSelf();
        }
    };

    GestureTrailDrawable(float density) {
        mMaxWidth = 6 * density;
        mMinWidth = 2 * density;
        mEdgeWidth = 1.5f * density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The palette: the head of the trail takes the first colour, the tail the last. */
    void setColors(int[] colors) {
        mColors = new int[colors.length];
        mEdgeColors = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            mColors[i] = colors[i] | 0xFF000000;
            mEdgeColors[i] = shade(mColors[i]);
        }
    }

    private static int shade(int color) {
        return 0xFF000000
                | Math.round(((color >> 16) & 0xFF) * EDGE_SHADE) << 16
                | Math.round(((color >> 8) & 0xFF) * EDGE_SHADE) << 8
                | Math.round((color & 0xFF) * EDGE_SHADE);
    }

    /** The palette colour at u, from 0 (head) to 1 (tail). */
    private static int blend(int[] colors, float u) {
        if (colors.length == 1) return colors[0];
        final float at = Math.max(0f, Math.min(1f, u)) * (colors.length - 1);
        final int i = Math.min((int) at, colors.length - 2);
        final float f = at - i;
        final int a = colors[i];
        final int b = colors[i + 1];
        return 0xFF000000
                | Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f) << 16
                | Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f) << 8
                | Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
    }

    void setOffset(float x, float y) {
        mOffsetX = x;
        mOffsetY = y;
    }

    void start() {
        mFirst = 0;
        mCount = 0;
        mSeen = 0;
        mActive = true;
        invalidateSelf();
    }

    /** Takes in the points added to the path since the last call. */
    void update(GesturePath path) {
        if (!mActive) return;
        // The path drops every other point when it gets long: start again from its end
        if (path.size() < mSeen) mSeen = Math.max(0, path.size() - 1);
        for (int i = mSeen; i < path.size(); i++) {
            final int slot = (mFirst + mCount) % MAX_POINTS;
            mX[slot] = path.getX(i);
            mY[slot] = path.getY(i);
            mT[slot] = path.getTime(i);
            if (mCount < MAX_POINTS) {
                mCount++;
            } else {
                mFirst = (mFirst + 1) % MAX_POINTS;
            }
        }
        mSeen = path.size();
        invalidateSelf();
    }

    /** The finger lifted: the trail fades out on its own. */
    void finish() {
        mActive = false;
        invalidateSelf();
    }

    void clear() {
        mActive = false;
        mCount = 0;
        unscheduleSelf(mNextFrame);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        if (mCount < 2) return;
        // Point times are MotionEvent times, on the uptime clock
        final long now = SystemClock.uptimeMillis();
        boolean visible = false;
        // The edge first, all along, then the colours over it: an edge drawn segment by
        // segment between the colours would cut across the joins
        for (int pass = 0; pass < 2; pass++) {
            final boolean edge = pass == 0;
            for (int k = 1; k < mCount; k++) {
                final int a = (mFirst + k - 1) % MAX_POINTS;
                final int b = (mFirst + k) % MAX_POINTS;
                final long age = now - mT[b];
                if (age >= FADE_MS) continue;
                final float fresh = 1f - Math.max(0, age) / (float) FADE_MS;
                // 0 at the head of the trail, 1 at its oldest point
                final float u = (mCount - 1 - k) / (float) (mCount - 1);
                final float width = mMinWidth + (mMaxWidth - mMinWidth) * fresh;
                mPaint.setColor(blend(edge ? mEdgeColors : mColors, u));
                mPaint.setAlpha(Math.round(mAlpha * fresh));
                mPaint.setStrokeWidth(edge ? width + 2 * mEdgeWidth : width);
                canvas.drawLine(mX[a] + mOffsetX, mY[a] + mOffsetY,
                        mX[b] + mOffsetX, mY[b] + mOffsetY, mPaint);
                visible = true;
            }
        }
        if (visible) {
            unscheduleSelf(mNextFrame);
            scheduleSelf(mNextFrame, now + FRAME_MS);
        } else if (!mActive) {
            mCount = 0;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
