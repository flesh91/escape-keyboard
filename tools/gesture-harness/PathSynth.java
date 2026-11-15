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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.pocketworkstation.pckeyboard.gesture.GestureKeyMap;
import org.pocketworkstation.pckeyboard.gesture.GesturePath;

/**
 * A finger tracing a word: the centres of its keys (equal neighbours merged) each moved by
 * gaussian noise (the first and last key as much as the others: a finger lands anywhere on
 * a key), corners cut (optionally harder, --cut) and rounded off (Chaikin), the last segment
 * stopping short of the last key or going past it, then sampled at 120 Hz at a speed that
 * varies along the way, with a little jitter.
 */
final class PathSynth {
    private final Random mRandom;
    private final GestureKeyMap mKeys;
    private final float mNoise;
    private final float mCut;

    /** cut: how far each inner key is pulled towards the middle of its neighbours (0..1). */
    PathSynth(GestureKeyMap keys, float noise, float cut, long seed) {
        mKeys = keys;
        mNoise = noise;
        mCut = cut;
        mRandom = new Random(seed);
    }

    /** Key indices of the word, or null when the layout can't type it. */
    int[] keysOf(String word) {
        int[] out = new int[word.length()];
        int n = 0;
        int prev = -1;
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            if (GestureKeyMap.isSkipChar(c)) continue;
            final int k = mKeys.keyFor(c);
            if (k < 0) return null;
            if (k == prev) continue;
            out[n++] = k;
            prev = k;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    GesturePath trace(int[] seq) {
        final float kw = mKeys.getKeyWidth();
        final float kh = mKeys.getKeyHeight();
        final int m = seq.length;
        List<float[]> pts = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            pts.add(new float[] {
                    mKeys.unitX(seq[i]) * kw + (float) mRandom.nextGaussian() * mNoise * kw,
                    mKeys.unitY(seq[i]) * kh + (float) mRandom.nextGaussian() * mNoise * kh });
        }
        // Corners cut: the finger heads for the next key before reaching this one
        if (mCut > 0 && m >= 3) {
            List<float[]> cut = new ArrayList<>(m);
            cut.add(pts.get(0));
            for (int i = 1; i < m - 1; i++) {
                float[] a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
                cut.add(new float[] { b[0] + mCut * ((a[0] + c[0]) / 2 - b[0]),
                        b[1] + mCut * ((a[1] + c[1]) / 2 - b[1]) });
            }
            cut.add(pts.get(m - 1));
            pts = cut;
        }
        // The last segment stops short of the last key or goes past it
        if (m >= 2) {
            float[] a = pts.get(m - 2), b = pts.get(m - 1);
            float dx = b[0] - a[0], dy = b[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            if (len > 0) {
                float over = (mRandom.nextFloat() * 0.6f - 0.25f) * kw;
                if (over >= 0) {
                    pts.add(new float[] { b[0] + dx / len * over, b[1] + dy / len * over });
                } else {
                    over = Math.max(over, -0.5f * len);
                    pts.set(m - 1, new float[] { b[0] + dx / len * over, b[1] + dy / len * over });
                }
            }
        }
        for (int it = 0; it < 2; it++) pts = chaikin(pts);

        // Walk along the curve at a varying speed, one point every 1/120 s
        GesturePath path = new GesturePath(256);
        float speed = (1.0f + 1.5f * mRandom.nextFloat()) * kw / 100f; // px per ms
        final float dt = 1000f / 120f;
        long t = 0;
        float[] p = pts.get(0);
        path.add(p[0], p[1], t);
        int seg = 0;
        float along = 0;
        while (seg < pts.size() - 1) {
            float factor = 0.7f + 0.6f * mRandom.nextFloat();
            float step = speed * dt * factor;
            // Advance step px along the polyline
            while (step > 0 && seg < pts.size() - 1) {
                float[] a = pts.get(seg), b = pts.get(seg + 1);
                float segLen = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                float left = segLen - along;
                if (step < left) {
                    along += step;
                    step = 0;
                } else {
                    step -= left;
                    seg++;
                    along = 0;
                }
            }
            t += (long) dt;
            float[] a = pts.get(Math.min(seg, pts.size() - 1));
            float x, y;
            if (seg < pts.size() - 1) {
                float[] b = pts.get(seg + 1);
                float segLen = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                float f = segLen > 0 ? along / segLen : 0;
                x = a[0] + f * (b[0] - a[0]);
                y = a[1] + f * (b[1] - a[1]);
            } else {
                x = a[0];
                y = a[1];
            }
            x += (float) mRandom.nextGaussian() * 0.03f * kw;
            y += (float) mRandom.nextGaussian() * 0.03f * kw;
            path.add(x, y, t);
        }
        return path;
    }

    private static List<float[]> chaikin(List<float[]> in) {
        if (in.size() < 3) return in;
        List<float[]> out = new ArrayList<>(in.size() * 2);
        out.add(in.get(0));
        for (int i = 0; i + 1 < in.size(); i++) {
            float[] a = in.get(i), b = in.get(i + 1);
            if (i > 0) out.add(new float[] { 0.75f * a[0] + 0.25f * b[0], 0.75f * a[1] + 0.25f * b[1] });
            if (i + 2 < in.size()) out.add(new float[] { 0.25f * a[0] + 0.75f * b[0], 0.25f * a[1] + 0.75f * b[1] });
        }
        out.add(in.get(in.size() - 1));
        return out;
    }
}
