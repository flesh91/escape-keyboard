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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.annotation.NonNull;

import com.google.android.material.slider.Slider;

/**
 * Slider that keeps its fine steps but marks only every tenth of the range,
 * instead of a dot on each of the 100 steps.
 */
public class TickSlider extends Slider {
    private static final int MARKS = 10;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRadius;

    public TickSlider(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        setTickVisible(false);
        mRadius = 2 * context.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float left = getTrackSidePadding();
        float width = getTrackWidth();
        float y = getHeight() / 2f;
        float fraction = (getValue() - getValueFrom()) / (getValueTo() - getValueFrom());
        ColorStateList active = getTickActiveTintList();
        ColorStateList inactive = getTickInactiveTintList();
        int[] state = getDrawableState();
        // Ends and the thumb position are skipped: the track caps and the thumb cover them
        for (int i = 1; i < MARKS; i++) {
            float at = (float) i / MARKS;
            if (Math.abs(at - fraction) < 0.02f) continue;
            ColorStateList tint = at < fraction ? active : inactive;
            mPaint.setColor(tint.getColorForState(state, tint.getDefaultColor()));
            canvas.drawCircle(left + width * at, y, mRadius, mPaint);
        }
    }
}
