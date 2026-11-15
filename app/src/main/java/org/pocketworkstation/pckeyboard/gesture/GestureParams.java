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

import java.lang.reflect.Field;

/**
 * Tuning constants of the gesture decoder. Distances are in key units (a median letter key
 * is 1 x 1) unless the name says px. Not final, so the offline harness can try other values
 * (tools/gesture-harness, --set NAME=value).
 */
public final class GestureParams {
    private GestureParams() {}

    /** Points the gesture and the templates are resampled to: the coarse pass and the full one. */
    public static int N_COARSE = 12;
    public static int N_FINE = 40;

    /** Keys a word may start / end on: within this radius of the first / last touch point. */
    public static float R_START = 1.3f;
    public static float R_END = 1.5f;
    /** Second try with both radii widened, when nothing was found. */
    public static float R_WIDE = 2.2f;

    /** Allowed difference of the gesture length and a word's ideal path: relative + absolute. */
    public static float LEN_REL = 0.45f;
    public static float LEN_ABS = 1.0f;

    /** Candidates kept after the coarse pass. */
    public static int KEEP_A = 200;

    /** Spread of the shape (normalized) and location (key units) distances. */
    public static float SIGMA_SHAPE = 0.12f;
    public static float SIGMA_LOC = 0.55f;
    /** Every letter of a word must be passed near: squared distance beyond MISS_FREE costs W_MISS. */
    public static float W_MISS = 1.0f;
    public static float MISS_FREE = 0.6f;
    /** The same the other way round: gesture points far from the word's path cost W_EXTRA. */
    public static float W_EXTRA = 1.0f;
    /**
     * Squared distance of the first and last touch point from the word's first and last key.
     * Set where it holds up with ends as sloppy as the rest of the path and on words not tuned
     * on (harness --noise 0.3, --skip 1000, --cut 0.15), not only on the tuning set.
     */
    public static float W_END = 2.0f;
    /** Weight of the word frequency (log scale, 20 frequency units = one e-fold of rank). */
    public static float LAMBDA = 0.4f;
    /** Below this extent the shape is noise (a short gesture), only the location counts. */
    public static float SHAPE_MIN_EXTENT = 0.8f;
    /** The best word is further than this from the gesture on average: no word. */
    public static float REJECT_LOC = 1.5f;
    /** Gestures shorter than this are not words. */
    public static float MIN_GESTURE_LEN = 0.5f;

    public static int MAX_OUT = 5;

    /** Frequency of a user dictionary word (about rank 300 on the word list scale). */
    public static int USER_WORD_FREQ = 140;
    /** Frequency added each time a word is accepted, and the most it may get, in this session. */
    public static int LEARN_BOOST_STEP = 6;
    public static int LEARN_BOOST_MAX = 30;

    /** Touch points closer than this to the previous one are dropped. */
    public static float MIN_POINT_DIST_PX = 2f;
    /** A touch becomes a gesture after moving this far (key units), and at least this far, dp. */
    public static float GESTURE_START_DIST = 0.55f;
    public static float GESTURE_START_MIN_DP = 16f;
    /** No gesture starts this soon after a key was typed (typing with taps), ms. */
    public static int GESTURE_AFTER_TAP_MS = 350;
    /** A gesture shorter than this that is no word was a tap that slid: its first key is typed. */
    public static float GESTURE_TAP_FALLBACK_LEN = 1.5f;
    /** A fling from a letter wins over a gesture only when this quick and this straight. */
    public static int FLING_MAX_MS = 200;
    public static float FLING_STRAIGHTNESS = 0.95f;

    /** Sets a constant by name, for the harness. Returns false for an unknown name. */
    public static boolean set(String name, String value) {
        try {
            Field f = GestureParams.class.getField(name);
            if (f.getType() == int.class) {
                f.setInt(null, Integer.parseInt(value));
            } else {
                f.setFloat(null, Float.parseFloat(value));
            }
            return true;
        } catch (ReflectiveOperationException | NumberFormatException e) {
            return false;
        }
    }
}
