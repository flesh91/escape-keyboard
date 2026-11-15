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

import java.util.Arrays;

/**
 * A lexicon laid onto one keyboard layout: every word as the keys a finger passes (equal
 * neighbours merged: hello is h-e-l-o), the length of that ideal path, and the words grouped
 * by their first and last key. Words of one key, or with a letter the layout can't type,
 * are left out.
 */
public final class LexiconBinding {
    final Lexicon lexicon;
    final GestureKeyMap keys;
    final Table main;
    /** Words of each (first key, last key) pair: main.word[bucketStart[b] .. bucketStart[b+1]). */
    final int[] bucketStart;

    private Lexicon.Extras mExtrasSource;
    private Table mExtras;

    /** Words as key sequences. word[i] is the index in the lexicon (or in its extras). */
    static final class Table {
        int count;
        int[] word;
        int[] seqOff;
        byte[] seq;
        float[] pathLen;

        int first(int i) {
            return seq[seqOff[i]];
        }

        int last(int i) {
            return seq[seqOff[i + 1] - 1];
        }
    }

    public LexiconBinding(Lexicon lexicon, GestureKeyMap keys) {
        this.lexicon = lexicon;
        this.keys = keys;
        final int n = lexicon.size();
        final char[] pool = lexicon.pool();
        final int k = Math.max(1, keys.getKeyCount());

        // Key sequences in lexicon order first
        int[] seqOff = new int[n + 1];
        byte[] seq = new byte[Math.max(16, pool.length)];
        int[] wordOf = new int[n];
        int count = 0;
        int seqLen = 0;
        for (int w = 0; w < n; w++) {
            final int len = toKeys(pool, lexicon.start(w), lexicon.end(w), seq, seqLen);
            if (len < 2) continue;
            wordOf[count] = w;
            seqOff[count] = seqLen;
            seqLen += len;
            count++;
        }
        seqOff[count] = seqLen;

        // Then sorted by bucket (counting sort, stable: frequent words first in each bucket)
        int[] bucketStart = new int[k * k + 1];
        for (int i = 0; i < count; i++) {
            bucketStart[seq[seqOff[i]] * k + seq[seqOff[i + 1] - 1] + 1]++;
        }
        for (int b = 0; b < k * k; b++) bucketStart[b + 1] += bucketStart[b];
        int[] fill = Arrays.copyOf(bucketStart, k * k);
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[fill[seq[seqOff[i]] * k + seq[seqOff[i + 1] - 1]]++] = i;
        }
        Table t = new Table();
        t.count = count;
        t.word = new int[count];
        t.seqOff = new int[count + 1];
        t.seq = new byte[seqLen];
        t.pathLen = new float[count];
        int pos = 0;
        for (int j = 0; j < count; j++) {
            final int i = order[j];
            final int len = seqOff[i + 1] - seqOff[i];
            t.word[j] = wordOf[i];
            t.seqOff[j] = pos;
            System.arraycopy(seq, seqOff[i], t.seq, pos, len);
            t.pathLen[j] = pathLength(t.seq, pos, len);
            pos += len;
        }
        t.seqOff[count] = pos;
        main = t;
        this.bucketStart = bucketStart;
    }

    /**
     * Writes the keys of chars[from..to) into out at pos; returns how many, or -1 when a
     * letter has no key.
     */
    private int toKeys(char[] chars, int from, int to, byte[] out, int pos) {
        int len = 0;
        int prev = -1;
        for (int i = from; i < to; i++) {
            final char c = chars[i];
            if (GestureKeyMap.isSkipChar(c)) continue;
            final int key = keys.keyFor(c);
            if (key < 0) return -1;
            if (key == prev) continue;
            out[pos + len++] = (byte) key;
            prev = key;
        }
        return len;
    }

    float pathLength(byte[] seq, int off, int len) {
        float total = 0;
        for (int i = off + 1; i < off + len; i++) {
            final float dx = keys.unitX(seq[i]) - keys.unitX(seq[i - 1]);
            final float dy = keys.unitY(seq[i]) - keys.unitY(seq[i - 1]);
            total += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    /** The extra words laid onto the layout, rebuilt when the lexicon's extras change. */
    Table extras() {
        final Lexicon.Extras source = lexicon.extras();
        if (source != mExtrasSource) {
            Table t = new Table();
            final int n = source.words.length;
            t.word = new int[n];
            t.seqOff = new int[n + 1];
            t.pathLen = new float[n];
            byte[] seq = new byte[64];
            int count = 0;
            int pos = 0;
            for (int w = 0; w < n; w++) {
                final char[] chars = source.words[w].toCharArray();
                if (seq.length < pos + chars.length) seq = Arrays.copyOf(seq, (pos + chars.length) * 2);
                final int len = toKeys(chars, 0, chars.length, seq, pos);
                if (len < 2) continue;
                t.word[count] = w;
                t.seqOff[count] = pos;
                t.pathLen[count] = pathLength(seq, pos, len);
                pos += len;
                count++;
            }
            t.seqOff[count] = pos;
            t.seq = seq;
            t.count = count;
            mExtras = t;
            mExtrasSource = source;
        }
        return mExtras;
    }

    public Lexicon getLexicon() {
        return lexicon;
    }

    public GestureKeyMap getKeyMap() {
        return keys;
    }

    /** Words that can be typed by a gesture on this layout. */
    public int size() {
        return main.count;
    }
}
