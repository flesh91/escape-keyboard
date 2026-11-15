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

/** The words a gesture may mean, best first. */
public final class GestureResult {
    public static final GestureResult EMPTY = new GestureResult(new String[0], new float[0], 0, 0);

    public final String[] words;
    public final float[] scores;
    /** Words compared in the coarse pass. */
    public final int candidatesScanned;
    public final long nanos;

    public GestureResult(String[] words, float[] scores, int candidatesScanned, long nanos) {
        this.words = words;
        this.scores = scores;
        this.candidatesScanned = candidatesScanned;
        this.nanos = nanos;
    }

    public boolean isEmpty() {
        return words.length == 0;
    }
}
