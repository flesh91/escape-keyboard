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

import java.util.ArrayList;
import java.util.Locale;

/**
 * Finds the words a gesture traced over the keys may mean. Each word has an ideal path, the
 * polyline through the centres of its keys. Gesture and paths are resampled to the same
 * number of points evenly spaced along their length and compared point by point: where
 * they are (location), what they look like scaled to the same size (shape), whether every
 * letter of the word was passed near, how close the ends are to the first and last letter.
 * The word frequency is added as a prior. Only the words that start near the first touch
 * point and end near the last one are compared, first coarsely, then the best ones fully.
 *
 * One instance is not thread safe: it keeps its work buffers between calls.
 */
public final class GestureDecoder {
    private static final float EPS = 1e-6f;

    // The gesture in key units
    private float[] mPx = new float[GesturePath.MAX_POINTS];
    private float[] mPy = new float[GesturePath.MAX_POINTS];
    // Resampled gesture: coarse, fine, fine normalized
    private float[] mCx, mCy, mFx, mFy, mNx, mNy;
    // A word's resampled path
    private float[] mTx, mTy;
    // A word's key centres
    private float[] mKx = new float[64];
    private float[] mKy = new float[64];
    private boolean[] mInStart = new boolean[0];
    private boolean[] mInEnd = new boolean[0];
    // The coarse pass keeps the best candidates in a min-heap on the score.
    // id >= 0: index in the main table, id < 0: -1 - index in the extras table.
    private int[] mHeapId = new int[0];
    private float[] mHeapScore = new float[0];
    private int mHeapSize;

    private void ensureBuffers() {
        final int nc = GestureParams.N_COARSE;
        final int nf = GestureParams.N_FINE;
        if (mCx == null || mCx.length != nc) {
            mCx = new float[nc];
            mCy = new float[nc];
        }
        if (mFx == null || mFx.length != nf) {
            mFx = new float[nf];
            mFy = new float[nf];
            mNx = new float[nf];
            mNy = new float[nf];
        }
        final int nt = Math.max(nc, nf);
        if (mTx == null || mTx.length != nt) {
            mTx = new float[nt];
            mTy = new float[nt];
        }
        if (mHeapId.length != GestureParams.KEEP_A) {
            mHeapId = new int[GestureParams.KEEP_A];
            mHeapScore = new float[GestureParams.KEEP_A];
        }
    }

    public GestureResult decode(GesturePath raw, LexiconBinding b, int maxOut) {
        final long t0 = System.nanoTime();
        ensureBuffers();
        final GestureKeyMap keys = b.keys;
        final int n = raw.size();
        if (n < 2 || keys.getKeyCount() < 2) return GestureResult.EMPTY;
        final float kw = keys.getKeyWidth();
        final float kh = keys.getKeyHeight();
        for (int i = 0; i < n; i++) {
            mPx[i] = raw.getX(i) / kw;
            mPy[i] = raw.getY(i) / kh;
        }
        final float lg = length(mPx, mPy, n);
        if (lg < GestureParams.MIN_GESTURE_LEN) return GestureResult.EMPTY;
        resample(mPx, mPy, n, lg, mCx, mCy, mCx.length);
        resample(mPx, mPy, n, lg, mFx, mFy, mFx.length);
        final float sx = mPx[0], sy = mPy[0];
        final float ex = mPx[n - 1], ey = mPy[n - 1];

        mHeapSize = 0;
        int scanned = coarsePass(b, lg, sx, sy, ex, ey,
                GestureParams.R_START, GestureParams.R_END);
        if (mHeapSize == 0) {
            scanned += coarsePass(b, lg, sx, sy, ex, ey,
                    GestureParams.R_WIDE, GestureParams.R_WIDE);
        }
        if (mHeapSize == 0) {
            return new GestureResult(new String[0], new float[0], scanned, System.nanoTime() - t0);
        }

        // Full pass over the kept candidates
        final int nf = mFx.length;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < nf; i++) {
            minX = Math.min(minX, mFx[i]);
            maxX = Math.max(maxX, mFx[i]);
            minY = Math.min(minY, mFy[i]);
            maxY = Math.max(maxY, mFy[i]);
        }
        final boolean shapeOn = Math.max(maxX - minX, maxY - minY) >= GestureParams.SHAPE_MIN_EXTENT;
        if (shapeOn) normalize(mFx, mFy, nf, mNx, mNy);

        final LexiconBinding.Table main = b.main;
        final LexiconBinding.Table extras = b.extras();
        final int count = mHeapSize;
        final float[] scores = new float[count];
        final float[] locs = new float[count];
        final int[] ids = new int[count];
        final float sigmaShape = GestureParams.SIGMA_SHAPE;
        final float sigmaLoc = GestureParams.SIGMA_LOC;
        for (int c = 0; c < count; c++) {
            final int id = mHeapId[c];
            final LexiconBinding.Table t = id >= 0 ? main : extras;
            final int i = id >= 0 ? id : -1 - id;
            final int m = loadKeys(keys, t, i);
            resample(mKx, mKy, m, t.pathLen[i], mTx, mTy, nf);
            final float loc = meanDistance(mFx, mFy, mTx, mTy, nf);
            float shape = 0;
            if (shapeOn) {
                normalize(mTx, mTy, nf, mTx, mTy);
                shape = meanDistance(mNx, mNy, mTx, mTy, nf);
            }
            float miss = 0;
            for (int j = 0; j < m; j++) {
                final float d = distanceToPolyline(mKx[j], mKy[j], mFx, mFy, nf)
                        - GestureParams.MISS_FREE;
                if (d > 0) miss += d * d;
            }
            miss /= m;
            // And the other way round: the gesture must not wander far from the word's path
            float extra = 0;
            if (GestureParams.W_EXTRA > 0) {
                for (int j = 0; j < nf; j++) {
                    final float d = distanceToPolyline(mFx[j], mFy[j], mKx, mKy, m)
                            - GestureParams.MISS_FREE;
                    if (d > 0) extra += d * d;
                }
                extra /= nf;
            }
            final float end2 = sq(sx - mKx[0]) + sq(sy - mKy[0])
                    + sq(ex - mKx[m - 1]) + sq(ey - mKy[m - 1]);
            final float ns = shape / sigmaShape;
            final float nl = loc / sigmaLoc;
            scores[c] = -0.5f * ns * ns - 0.5f * nl * nl - GestureParams.W_MISS * miss
                    - GestureParams.W_EXTRA * extra
                    - GestureParams.W_END * end2 + prior(b, t, id, i);
            locs[c] = loc;
            ids[c] = id;
        }

        // Best first; the same text only once
        Integer[] order = new Integer[count];
        for (int c = 0; c < count; c++) order[c] = c;
        java.util.Arrays.sort(order, (p, q) -> Float.compare(scores[q], scores[p]));
        if (locs[order[0]] > GestureParams.REJECT_LOC) {
            return new GestureResult(new String[0], new float[0], scanned, System.nanoTime() - t0);
        }
        ArrayList<String> words = new ArrayList<>(maxOut);
        ArrayList<String> seen = new ArrayList<>(maxOut);
        float[] outScores = new float[maxOut];
        final Lexicon.Extras extraWords = b.lexicon.extras();
        for (int k = 0; k < count && words.size() < maxOut; k++) {
            final int id = ids[order[k]];
            final String w = id >= 0 ? b.lexicon.word(main.word[id])
                    : extraWords.words[extras.word[-1 - id]];
            final String key = w.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            outScores[words.size()] = scores[order[k]];
            words.add(w);
        }
        return new GestureResult(words.toArray(new String[0]),
                java.util.Arrays.copyOf(outScores, words.size()), scanned, System.nanoTime() - t0);
    }

    /** Coarse pass over the words starting and ending near the gesture's ends. */
    private int coarsePass(LexiconBinding b, float lg, float sx, float sy, float ex, float ey,
            float rStart, float rEnd) {
        final GestureKeyMap keys = b.keys;
        final int k = keys.getKeyCount();
        if (mInStart.length != k) {
            mInStart = new boolean[k];
            mInEnd = new boolean[k];
        }
        markNear(keys, sx, sy, rStart, mInStart);
        markNear(keys, ex, ey, rEnd, mInEnd);
        int scanned = 0;
        final LexiconBinding.Table main = b.main;
        for (int ks = 0; ks < k; ks++) {
            if (!mInStart[ks]) continue;
            for (int ke = 0; ke < k; ke++) {
                if (!mInEnd[ke]) continue;
                final int bucket = ks * k + ke;
                for (int i = b.bucketStart[bucket]; i < b.bucketStart[bucket + 1]; i++) {
                    scanned += coarseScore(b, main, i, i, lg, sx, sy, ex, ey);
                }
            }
        }
        final LexiconBinding.Table extras = b.extras();
        for (int i = 0; i < extras.count; i++) {
            if (mInStart[extras.first(i)] && mInEnd[extras.last(i)]) {
                scanned += coarseScore(b, extras, i, -1 - i, lg, sx, sy, ex, ey);
            }
        }
        return scanned;
    }

    private int coarseScore(LexiconBinding b, LexiconBinding.Table t, int i, int id, float lg,
            float sx, float sy, float ex, float ey) {
        final float lt = t.pathLen[i];
        if (Math.abs(lg - lt) > GestureParams.LEN_REL * Math.max(lg, lt) + GestureParams.LEN_ABS) {
            return 0;
        }
        final GestureKeyMap keys = b.keys;
        final int first = t.first(i);
        final int last = t.last(i);
        final float end2 = sq(sx - keys.unitX(first)) + sq(sy - keys.unitY(first))
                + sq(ex - keys.unitX(last)) + sq(ey - keys.unitY(last));
        final float bound = prior(b, t, id, i) - GestureParams.W_END * end2;
        final boolean full = mHeapSize == mHeapId.length;
        if (full && bound <= mHeapScore[0]) return 1;
        final int m = loadKeys(keys, t, i);
        final int nc = mCx.length;
        resample(mKx, mKy, m, lt, mTx, mTy, nc);
        final float nl = meanDistance(mCx, mCy, mTx, mTy, nc) / GestureParams.SIGMA_LOC;
        final float score = bound - 0.5f * nl * nl;
        if (!full) {
            heapPush(id, score);
        } else if (score > mHeapScore[0]) {
            mHeapId[0] = id;
            mHeapScore[0] = score;
            siftDown(0);
        }
        return 1;
    }

    private float prior(LexiconBinding b, LexiconBinding.Table t, int id, int i) {
        final int freq = id >= 0 ? b.lexicon.freq(t.word[i]) : b.lexicon.extras().freq[t.word[i]];
        return GestureParams.LAMBDA * (freq - 255) / 20f;
    }

    private int loadKeys(GestureKeyMap keys, LexiconBinding.Table t, int i) {
        final int off = t.seqOff[i];
        final int m = t.seqOff[i + 1] - off;
        if (mKx.length < m) {
            mKx = new float[m * 2];
            mKy = new float[m * 2];
        }
        for (int j = 0; j < m; j++) {
            final int key = t.seq[off + j];
            mKx[j] = keys.unitX(key);
            mKy[j] = keys.unitY(key);
        }
        return m;
    }

    private static void markNear(GestureKeyMap keys, float x, float y, float r, boolean[] out) {
        boolean any = false;
        final float r2 = r * r;
        for (int k = 0; k < out.length; k++) {
            out[k] = sq(keys.unitX(k) - x) + sq(keys.unitY(k) - y) <= r2;
            any |= out[k];
        }
        if (!any) out[keys.nearestKey(x, y)] = true;
    }

    private void heapPush(int id, float score) {
        int i = mHeapSize++;
        while (i > 0) {
            final int parent = (i - 1) / 2;
            if (mHeapScore[parent] <= score) break;
            mHeapId[i] = mHeapId[parent];
            mHeapScore[i] = mHeapScore[parent];
            i = parent;
        }
        mHeapId[i] = id;
        mHeapScore[i] = score;
    }

    private void siftDown(int i) {
        final int id = mHeapId[i];
        final float score = mHeapScore[i];
        final int size = mHeapSize;
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) break;
            if (child + 1 < size && mHeapScore[child + 1] < mHeapScore[child]) child++;
            if (mHeapScore[child] >= score) break;
            mHeapId[i] = mHeapId[child];
            mHeapScore[i] = mHeapScore[child];
            i = child;
        }
        mHeapId[i] = id;
        mHeapScore[i] = score;
    }

    private static float sq(float v) {
        return v * v;
    }

    static float length(float[] x, float[] y, int n) {
        float len = 0;
        for (int i = 1; i < n; i++) {
            len += (float) Math.sqrt(sq(x[i] - x[i - 1]) + sq(y[i] - y[i - 1]));
        }
        return len;
    }

    /**
     * Resamples the polyline (x, y, n points, total length len) to count points evenly spaced
     * along it. A polyline of length 0 gives copies of its first point.
     */
    static void resample(float[] x, float[] y, int n, float len, float[] ox, float[] oy,
            int count) {
        if (n == 1 || len < EPS) {
            for (int j = 0; j < count; j++) {
                ox[j] = x[0];
                oy[j] = y[0];
            }
            return;
        }
        final float step = len / (count - 1);
        int seg = 0;
        float segStart = 0;
        float segLen = (float) Math.sqrt(sq(x[1] - x[0]) + sq(y[1] - y[0]));
        for (int j = 0; j < count; j++) {
            final float d = j * step;
            while (seg < n - 2 && segStart + segLen < d) {
                segStart += segLen;
                seg++;
                segLen = (float) Math.sqrt(sq(x[seg + 1] - x[seg]) + sq(y[seg + 1] - y[seg]));
            }
            float f = segLen > EPS ? (d - segStart) / segLen : 0;
            if (f > 1) f = 1;
            if (f < 0) f = 0;
            ox[j] = x[seg] + f * (x[seg + 1] - x[seg]);
            oy[j] = y[seg] + f * (y[seg + 1] - y[seg]);
        }
    }

    /** Moves the centroid to 0 and scales the larger side of the bounding box to 1. */
    private static void normalize(float[] x, float[] y, int n, float[] ox, float[] oy) {
        float cx = 0, cy = 0;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            cx += x[i];
            cy += y[i];
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        cx /= n;
        cy /= n;
        final float extent = Math.max(maxX - minX, maxY - minY);
        final float scale = extent > EPS ? 1f / extent : 1f;
        for (int i = 0; i < n; i++) {
            ox[i] = (x[i] - cx) * scale;
            oy[i] = (y[i] - cy) * scale;
        }
    }

    private static float meanDistance(float[] ax, float[] ay, float[] bx, float[] by, int n) {
        float sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (float) Math.sqrt(sq(ax[i] - bx[i]) + sq(ay[i] - by[i]));
        }
        return sum / n;
    }

    private static float distanceToPolyline(float px, float py, float[] x, float[] y, int n) {
        if (n == 1) return (float) Math.sqrt(sq(px - x[0]) + sq(py - y[0]));
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 1 < n; i++) {
            final float dx = x[i + 1] - x[i];
            final float dy = y[i + 1] - y[i];
            final float l2 = dx * dx + dy * dy;
            float f = l2 > EPS ? ((px - x[i]) * dx + (py - y[i]) * dy) / l2 : 0;
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            final float d2 = sq(px - x[i] - f * dx) + sq(py - y[i] - f * dy);
            if (d2 < best) best = d2;
        }
        return (float) Math.sqrt(best);
    }
}
