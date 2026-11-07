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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

/**
 * Android 16+ settings style: the items of each category sit in a rounded card,
 * as separate tiles with large outer and small inner corners.
 */
class GroupedCardDecoration extends RecyclerView.ItemDecoration {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mSide, mGap, mGroupGap;
    private final float mOuter, mInner;
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    GroupedCardDecoration(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        mSide = (int) (16 * d);
        mGap = (int) (2 * d);
        mGroupGap = (int) (16 * d);
        mOuter = 24 * d;
        mInner = 4 * d;
        mPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceBright, 0xFFFFFFFF));
    }

    @SuppressLint("RestrictedApi")
    private static Preference itemAt(RecyclerView parent, int position) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) return null;
        if (position < 0 || position >= adapter.getItemCount()) return null;
        return ((PreferenceGroupAdapter) adapter).getItem(position);
    }

    private static boolean isTile(Preference p) {
        return p != null && !(p instanceof PreferenceCategory);
    }

    private float[] radii(boolean first, boolean last) {
        float t = first ? mOuter : mInner, b = last ? mOuter : mInner;
        return new float[] { t, t, t, t, b, b, b, b };
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        Preference p = itemAt(parent, pos);
        out.left = mSide;
        out.right = mSide;
        if (!isTile(p)) return;
        boolean first = !isTile(itemAt(parent, pos - 1));
        boolean last = !isTile(itemAt(parent, pos + 1));
        out.top = first ? 0 : mGap;
        out.bottom = last ? mGroupGap : 0;
        // Keep the press ripple inside the rounded tile
        if (Build.VERSION.SDK_INT >= 30) {
            final float[] r = radii(first, last);
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    Path path = new Path();
                    path.addRoundRect(new RectF(0, 0, v.getWidth(), v.getHeight()), r, Path.Direction.CW);
                    outline.setPath(path);
                }
            });
            view.setClipToOutline(true);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || !isTile(itemAt(parent, pos))) continue;
            boolean first = !isTile(itemAt(parent, pos - 1));
            boolean last = !isTile(itemAt(parent, pos + 1));
            mRect.set(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY(),
                    child.getRight() + child.getTranslationX(), child.getBottom() + child.getTranslationY());
            mPath.reset();
            mPath.addRoundRect(mRect, radii(first, last), Path.Direction.CW);
            c.drawPath(mPath, mPaint);
        }
    }
}
