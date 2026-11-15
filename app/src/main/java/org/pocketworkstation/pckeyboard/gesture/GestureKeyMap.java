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

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * The letter keys of one keyboard layout: which key types each letter, and where the keys
 * are. A letter without a key of its own is typed by the key that has it in its long-press
 * popup (ґ on г, ї on і, ъ on ь), else by the key of its base letter (é on e).
 */
public final class GestureKeyMap {
    /** Letters typed by another key even when they have one (ё sits in the digit row). */
    private static final String ALWAYS_ALIAS = "ёе";
    /** Characters of a word that the gesture does not need to pass (п'ять, кто-то). */
    private static final String SKIP_CHARS = "'ʼ’-";
    // Direct lookup up to the end of the Cyrillic block, a map beyond it
    private static final int TABLE_SIZE = 0x0500;

    private final int mKeyCount;
    private final int[] mCodes;
    private final float[] mCenterX;
    private final float[] mCenterY;
    private final float mKeyWidth;
    private final float mKeyHeight;
    private final int mGeometryHash;
    private final byte[] mTable = new byte[TABLE_SIZE];
    private final HashMap<Character, Integer> mOther = new HashMap<>();

    /**
     * @param codes the unshifted code of each key that types a character (no modifiers,
     *        no text keys); keys whose code is not a letter are ignored
     * @param popups the long-press characters of each key, or null
     * @param x left edge of each key, px
     * @param y top edge, px
     * @param w width, px
     * @param h height, px
     */
    public GestureKeyMap(int[] codes, String[] popups, int[] x, int[] y, int[] w, int[] h) {
        final int n = codes.length;
        int count = 0;
        int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = -1;
            if (Character.isLetter(codes[i]) && count < 127) index[i] = count++;
        }
        mKeyCount = count;
        mCodes = new int[count];
        mCenterX = new float[count];
        mCenterY = new float[count];
        float[] widths = new float[count];
        float[] heights = new float[count];
        Arrays.fill(mTable, (byte) -1);
        int hash = 17;
        for (int i = 0; i < n; i++) {
            final int k = index[i];
            if (k < 0) continue;
            final int code = Character.toLowerCase(codes[i]);
            mCodes[k] = code;
            mCenterX[k] = x[i] + w[i] / 2f;
            mCenterY[k] = y[i] + h[i] / 2f;
            widths[k] = w[i];
            heights[k] = h[i];
            if (lookup((char) code) < 0) put((char) code, k);
            hash = 31 * (31 * (31 * (31 * (31 * hash + code) + x[i]) + y[i]) + w[i]) + h[i];
        }
        mGeometryHash = hash;
        // Popup letters that have no key of their own
        for (int i = 0; i < n; i++) {
            final int k = index[i];
            if (k < 0 || popups[i] == null) continue;
            final String popup = popups[i];
            for (int j = 0; j < popup.length(); j++) {
                final char c = Character.toLowerCase(popup.charAt(j));
                if (Character.isLetter(c) && lookup(c) < 0) put(c, k);
            }
        }
        for (int j = 0; j + 1 < ALWAYS_ALIAS.length(); j += 2) {
            final int k = lookup(ALWAYS_ALIAS.charAt(j + 1));
            if (k >= 0) put(ALWAYS_ALIAS.charAt(j), k);
        }
        mKeyWidth = Math.max(1f, median(widths));
        mKeyHeight = Math.max(1f, median(heights));
    }

    private static float median(float[] v) {
        if (v.length == 0) return 1f;
        float[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private void put(char c, int key) {
        if (c < TABLE_SIZE) {
            mTable[c] = (byte) key;
        } else {
            mOther.put(c, key);
        }
    }

    private int lookup(char c) {
        if (c < TABLE_SIZE) return mTable[c];
        Integer k = mOther.get(c);
        return k == null ? -1 : k;
    }

    public static boolean isSkipChar(char c) {
        return SKIP_CHARS.indexOf(c) >= 0;
    }

    /** The key that types c, or -1. */
    public int keyFor(char c) {
        final char lower = Character.toLowerCase(c);
        int k = lookup(lower);
        if (k >= 0) return k;
        final String base = Normalizer.normalize(String.valueOf(lower), Normalizer.Form.NFD);
        if (base.length() > 0 && base.charAt(0) != lower) return lookup(base.charAt(0));
        return -1;
    }

    public int getKeyCount() {
        return mKeyCount;
    }

    public int getCode(int key) {
        return mCodes[key];
    }

    /** Key centre in key units (x / key width, y / key height). */
    public float unitX(int key) {
        return mCenterX[key] / mKeyWidth;
    }

    public float unitY(int key) {
        return mCenterY[key] / mKeyHeight;
    }

    public float getKeyWidth() {
        return mKeyWidth;
    }

    public float getKeyHeight() {
        return mKeyHeight;
    }

    public int getGeometryHash() {
        return mGeometryHash;
    }

    /** The key nearest to a point in key units. */
    public int nearestKey(float ux, float uy) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int k = 0; k < mKeyCount; k++) {
            final float dx = unitX(k) - ux;
            final float dy = unitY(k) - uy;
            final float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }
}
