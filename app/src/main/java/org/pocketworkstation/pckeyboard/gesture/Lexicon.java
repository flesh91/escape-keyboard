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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The words of one language with their frequencies (1..255, log scale: 20 units per e-fold
 * of rank). All words share one char pool. A few extra words (the user dictionary, words
 * learned from use) are kept apart and can be replaced at any time.
 */
public final class Lexicon {
    private char[] mPool;
    private int[] mOffset;
    private byte[] mFreq;
    // Frequency added to a word accepted in this session (see boost)
    private byte[] mBoost;
    private int mCount;

    private volatile Extras mExtras = new Extras(new String[0], new int[0]);

    /** User and learned words; their frequencies may be raised by boost. */
    static final class Extras {
        final String[] words;
        final int[] freq;
        // The frequency each word came with: boosts stay within max of it
        final int[] base;

        Extras(String[] words, int[] freq) {
            this.words = words;
            this.freq = freq;
            this.base = freq.clone();
        }
    }

    private Lexicon() {}

    /** Reads "word\tfrequency" lines (UTF-8). The stream is closed. */
    public static Lexicon load(InputStream in) throws IOException {
        Lexicon lex = new Lexicon();
        lex.mPool = new char[1 << 18];
        lex.mOffset = new int[1 << 12];
        lex.mFreq = new byte[1 << 12];
        int poolLen = 0;
        int count = 0;
        char[] buf = new char[8192];
        // The line being read: the word goes straight into the pool, the number after the tab
        int wordStart = 0;
        boolean inFreq = false;
        int freq = 0;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = r.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    final char c = buf[i];
                    if (c == '\n' || c == '\r') {
                        if (inFreq && poolLen > wordStart) {
                            if (count + 1 >= lex.mOffset.length) {
                                lex.mOffset = Arrays.copyOf(lex.mOffset, lex.mOffset.length * 2);
                                lex.mFreq = Arrays.copyOf(lex.mFreq, lex.mFreq.length * 2);
                            }
                            lex.mOffset[count] = wordStart;
                            lex.mFreq[count] = (byte) Math.max(1, Math.min(255, freq));
                            count++;
                        } else {
                            poolLen = wordStart;
                        }
                        wordStart = poolLen;
                        inFreq = false;
                        freq = 0;
                    } else if (inFreq) {
                        if (c >= '0' && c <= '9') freq = freq * 10 + (c - '0');
                    } else if (c == '\t') {
                        inFreq = true;
                    } else {
                        if (poolLen == lex.mPool.length) {
                            lex.mPool = Arrays.copyOf(lex.mPool, lex.mPool.length * 2);
                        }
                        lex.mPool[poolLen++] = c;
                    }
                }
            }
        }
        if (inFreq && poolLen > wordStart) {
            if (count + 1 >= lex.mOffset.length) {
                lex.mOffset = Arrays.copyOf(lex.mOffset, count + 2);
                lex.mFreq = Arrays.copyOf(lex.mFreq, count + 2);
            }
            lex.mOffset[count] = wordStart;
            lex.mFreq[count] = (byte) Math.max(1, Math.min(255, freq));
            count++;
        } else {
            poolLen = wordStart;
        }
        lex.mCount = count;
        lex.mPool = Arrays.copyOf(lex.mPool, poolLen);
        lex.mOffset = Arrays.copyOf(lex.mOffset, count + 1);
        lex.mOffset[count] = poolLen;
        lex.mFreq = Arrays.copyOf(lex.mFreq, count);
        lex.mBoost = new byte[count];
        return lex;
    }

    public int size() {
        return mCount;
    }

    char[] pool() {
        return mPool;
    }

    int start(int word) {
        return mOffset[word];
    }

    int end(int word) {
        return mOffset[word + 1];
    }

    /** Frequency with the session boost. */
    int freq(int word) {
        return (mFreq[word] & 0xFF) + mBoost[word];
    }

    public String word(int word) {
        return new String(mPool, mOffset[word], mOffset[word + 1] - mOffset[word]);
    }

    Extras extras() {
        return mExtras;
    }

    /** Replaces the extra words (user dictionary, learned words). */
    public void setExtras(List<String> words, List<Integer> freqs) {
        String[] w = new String[words.size()];
        int[] f = new int[w.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = words.get(i);
            f[i] = Math.max(1, Math.min(255, freqs.get(i)));
        }
        mExtras = new Extras(w, f);
    }

    /** The word's index in the main list, or -1. */
    public int indexOf(CharSequence word) {
        final int len = word.length();
        final char[] pool = mPool;
        outer:
        for (int i = 0; i < mCount; i++) {
            final int s = mOffset[i];
            if (mOffset[i + 1] - s != len) continue;
            for (int j = 0; j < len; j++) {
                if (pool[s + j] != word.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Raises the frequency of a word for this session (it was accepted). Returns false when
     * the word is in neither list.
     */
    public boolean boost(CharSequence word, int step, int max) {
        boolean found = false;
        final int i = indexOf(word);
        if (i >= 0) {
            mBoost[i] = (byte) Math.min(max, mBoost[i] + step);
            found = true;
        }
        final Extras extras = mExtras;
        for (int j = 0; j < extras.words.length; j++) {
            if (extras.words[j].contentEquals(word)) {
                extras.freq[j] = Math.min(Math.min(255, extras.base[j] + max),
                        extras.freq[j] + step);
                found = true;
            }
        }
        return found;
    }

    public void clearBoost() {
        Arrays.fill(mBoost, (byte) 0);
    }
}
