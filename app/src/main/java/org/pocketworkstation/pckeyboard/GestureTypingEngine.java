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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.pocketworkstation.pckeyboard.Keyboard.Key;
import org.pocketworkstation.pckeyboard.gesture.GestureDecoder;
import org.pocketworkstation.pckeyboard.gesture.GestureKeyMap;
import org.pocketworkstation.pckeyboard.gesture.GestureParams;
import org.pocketworkstation.pckeyboard.gesture.GesturePath;
import org.pocketworkstation.pckeyboard.gesture.GestureResult;
import org.pocketworkstation.pckeyboard.gesture.Lexicon;
import org.pocketworkstation.pckeyboard.gesture.LexiconBinding;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

/**
 * Gesture typing on the device: the word lists (assets/gesture/<language>.txt) are read on
 * a background thread the first time a language is used, and laid onto the keyboard's
 * letter keys there too when the layout changes. Until a language is loaded, gestures are
 * not offered (LatinIME.isGestureTypingAllowed), so a slide types the keys as it always did.
 * Decoding takes about a millisecond and runs on the main thread when the finger lifts.
 * All methods are called on the main thread.
 */
final class GestureTypingEngine {
    private static final String TAG = "HK/Gesture";
    private static final boolean DEBUG = false;
    private static final String ASSET_DIR = "gesture";
    // A layout with fewer letters is not one to type words on (symbols, phone pad)
    private static final int MIN_LETTER_KEYS = 10;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler mWorker;

    private boolean mEnabled;
    private String mLanguage;
    private String[] mAssets;
    // The current language and the one before it, for quick switching (access order)
    private final LinkedHashMap<String, Lexicon> mLexicons = new LinkedHashMap<>(4, 0.75f, true);
    private final HashSet<String> mLoading = new HashSet<>();
    // The lexicon laid onto the current layout, and the one being built for it
    private LexiconBinding mBinding;
    private Lexicon mBuildingLexicon;
    private int mBuildingHash;
    private GestureKeyMap mPendingKeys;
    private Keyboard mPreparedKeyboard;
    private final GestureDecoder mDecoder = new GestureDecoder();

    // The user's own words in the current language: the user dictionary and learned words
    private String mExtrasLanguage;
    private final LinkedHashSet<String> mUserWords = new LinkedHashSet<>();
    private final LinkedHashSet<String> mLearnedWords = new LinkedHashSet<>();

    GestureTypingEngine(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Language code of an input language ("en_US" is "en"). */
    static String languageOf(String inputLanguage) {
        if (inputLanguage == null || inputLanguage.length() < 2) return null;
        return inputLanguage.substring(0, 2).toLowerCase(Locale.ROOT);
    }

    void setEnabled(boolean enabled) {
        if (enabled == mEnabled) return;
        mEnabled = enabled;
        if (enabled) {
            load(mLanguage);
        } else {
            mLexicons.clear();
            mBinding = null;
            mBuildingLexicon = null;
            mPendingKeys = null;
            mPreparedKeyboard = null;
            if (mThread != null) {
                mThread.quitSafely();
                mThread = null;
                mWorker = null;
            }
        }
    }

    void setLanguage(String inputLanguage) {
        final String language = languageOf(inputLanguage);
        if (language != null && language.equals(mLanguage)) return;
        mLanguage = language;
        mBinding = null;
        if (!mEnabled) return;
        load(language);
        // A word list still in memory is laid onto the keys again right away
        if (mPendingKeys != null && mLexicons.containsKey(language)) prepare(mPendingKeys);
    }

    /** The word list of the current language is in memory: gestures can be decoded. */
    boolean isReady() {
        return mEnabled && mLanguage != null && mLexicons.containsKey(mLanguage);
    }

    /**
     * The word list is laid onto this keyboard's keys, so decoding won't build that on the
     * main thread. If not, it is started in the background.
     */
    boolean isLaidOut(Keyboard keyboard) {
        if (!isReady() || keyboard == null) return false;
        if (keyboard != mPreparedKeyboard) prepare(keyboard);
        return mPendingKeys != null
                && isBound(mBinding, mLexicons.get(mLanguage), mPendingKeys.getGeometryHash());
    }

    /** Drops the word list of the language not in use (low memory). */
    void trim() {
        Iterator<String> it = mLexicons.keySet().iterator();
        while (it.hasNext()) {
            if (!it.next().equals(mLanguage)) it.remove();
        }
    }

    private boolean hasAsset(String language) {
        if (mAssets == null) {
            try {
                mAssets = mContext.getAssets().list(ASSET_DIR);
            } catch (IOException e) {
                mAssets = null;
            }
            if (mAssets == null) mAssets = new String[0];
        }
        return Arrays.asList(mAssets).contains(language + ".txt");
    }

    private Handler worker() {
        if (mWorker == null) {
            mThread = new HandlerThread("gesture-lexicon", Process.THREAD_PRIORITY_BACKGROUND);
            mThread.start();
            mWorker = new Handler(mThread.getLooper());
        }
        return mWorker;
    }

    private void load(final String language) {
        if (language == null || mLexicons.containsKey(language) || mLoading.contains(language)
                || !hasAsset(language)) {
            return;
        }
        mLoading.add(language);
        worker().post(new Runnable() {
            public void run() {
                Lexicon lexicon = null;
                final long start = System.nanoTime();
                try {
                    lexicon = Lexicon.load(mContext.getAssets().open(ASSET_DIR + "/" + language + ".txt"));
                    Log.i(TAG, "loaded " + language + ": " + lexicon.size() + " words in "
                            + (System.nanoTime() - start) / 1000000 + " ms");
                } catch (IOException e) {
                    Log.w(TAG, "can't read the " + language + " word list", e);
                }
                final Lexicon loaded = lexicon;
                mMain.post(new Runnable() {
                    public void run() {
                        onLoaded(language, loaded);
                    }
                });
            }
        });
    }

    private void onLoaded(String language, Lexicon lexicon) {
        mLoading.remove(language);
        if (!mEnabled || lexicon == null) return;
        mLexicons.put(language, lexicon);
        // Keep the current language and the previous one
        Iterator<String> it = mLexicons.keySet().iterator();
        while (mLexicons.size() > 2 && it.hasNext()) {
            if (!it.next().equals(mLanguage)) it.remove();
        }
        if (language.equals(mExtrasLanguage)) applyExtras();
        if (language.equals(mLanguage) && mPendingKeys != null) prepare(mPendingKeys);
    }

    /** The letter keys of a keyboard as the decoder sees them. */
    static GestureKeyMap keyMapOf(Keyboard keyboard) {
        List<Key> keys = keyboard.getKeys();
        final int n = keys.size();
        int[] codes = new int[n], x = new int[n], y = new int[n], w = new int[n], h = new int[n];
        String[] popups = new String[n];
        for (int i = 0; i < n; i++) {
            Key key = keys.get(i);
            // Not a letter key: code 0 is skipped
            if (key.codes != null && key.codes.length > 0 && key.text == null && !key.modifier) {
                codes[i] = key.codes[0];
            }
            popups[i] = key.popupCharacters != null ? key.popupCharacters.toString() : null;
            x[i] = key.x;
            y[i] = key.y;
            w[i] = key.width;
            h[i] = key.height;
        }
        return new GestureKeyMap(codes, popups, x, y, w, h);
    }

    /** The keyboard was laid out anew: lay the word list onto it in the background. */
    void prepare(Keyboard keyboard) {
        if (!mEnabled || keyboard == null) return;
        mPreparedKeyboard = keyboard;
        prepare(keyMapOf(keyboard));
    }

    private void prepare(final GestureKeyMap keys) {
        if (keys.getKeyCount() < MIN_LETTER_KEYS) {
            mPendingKeys = null; // no words on these keys (isLaidOut)
            return;
        }
        mPendingKeys = keys;
        final Lexicon lexicon = mLexicons.get(mLanguage);
        if (lexicon == null) {
            load(mLanguage);
            return;
        }
        final int hash = keys.getGeometryHash();
        if (isBound(mBinding, lexicon, hash)) return;
        if (mBuildingLexicon == lexicon && mBuildingHash == hash) return;
        mBuildingLexicon = lexicon;
        mBuildingHash = hash;
        worker().post(new Runnable() {
            public void run() {
                final LexiconBinding binding = new LexiconBinding(lexicon, keys);
                mMain.post(new Runnable() {
                    public void run() {
                        if (mBuildingLexicon == lexicon && mBuildingHash == hash) {
                            mBuildingLexicon = null;
                        }
                        if (mEnabled && mLexicons.get(mLanguage) == lexicon
                                && !isBound(mBinding, lexicon, hash)) {
                            mBinding = binding;
                        }
                    }
                });
            }
        });
    }

    private static boolean isBound(LexiconBinding binding, Lexicon lexicon, int hash) {
        return binding != null && binding.getLexicon() == lexicon
                && binding.getKeyMap().getGeometryHash() == hash;
    }

    /** The words the gesture may mean, best first; empty when it is none. */
    GestureResult decode(GesturePath path, Keyboard keyboard) {
        if (!isReady() || keyboard == null) return GestureResult.EMPTY;
        final GestureKeyMap keys = keyMapOf(keyboard);
        if (keys.getKeyCount() < MIN_LETTER_KEYS) return GestureResult.EMPTY;
        final Lexicon lexicon = mLexicons.get(mLanguage);
        if (!isBound(mBinding, lexicon, keys.getGeometryHash())) {
            // Not laid out in the background yet: once, here
            mBinding = new LexiconBinding(lexicon, keys);
        }
        final GestureResult result = mDecoder.decode(path, mBinding, GestureParams.MAX_OUT);
        if (DEBUG) {
            Log.i(TAG, "decoded " + result.candidatesScanned + " candidates in "
                    + result.nanos / 1000 + " us: " + Arrays.toString(result.words));
        }
        return result;
    }

    /**
     * The user's words for a language: the user dictionary and the learned words of the
     * auto dictionary. They replace the previous ones.
     */
    void setUserWords(String inputLanguage, List<String> userWords, List<String> learnedWords) {
        mExtrasLanguage = languageOf(inputLanguage);
        mUserWords.clear();
        mUserWords.addAll(userWords);
        mLearnedWords.clear();
        mLearnedWords.addAll(learnedWords);
        applyExtras();
    }

    /** A word became a known word of the auto dictionary. */
    void addLearnedWord(String word) {
        if (mExtrasLanguage == null || !mExtrasLanguage.equals(mLanguage)) return;
        if (mLearnedWords.add(word)) applyExtras();
    }

    /**
     * A word was accepted: it ranks higher for the rest of this session. As typed if the list
     * has it so ("I", names), else in small letters (a capital at the start of a sentence).
     */
    void noteAccepted(CharSequence word, boolean learn) {
        if (!learn || !isReady()) return;
        final Lexicon lexicon = mLexicons.get(mLanguage);
        if (lexicon.boost(word, GestureParams.LEARN_BOOST_STEP, GestureParams.LEARN_BOOST_MAX)) {
            return;
        }
        final String lower = word.toString().toLowerCase(new Locale(mLanguage));
        if (!lower.contentEquals(word)) {
            lexicon.boost(lower, GestureParams.LEARN_BOOST_STEP, GestureParams.LEARN_BOOST_MAX);
        }
    }

    /** Forgets what was learned (Settings, clear learned words). */
    void clearLearned() {
        for (Lexicon lexicon : mLexicons.values()) lexicon.clearBoost();
        mLearnedWords.clear();
        applyExtras();
    }

    private void applyExtras() {
        if (mExtrasLanguage == null) return;
        final Lexicon lexicon = mLexicons.get(mExtrasLanguage);
        if (lexicon == null) return;
        ArrayList<String> words = new ArrayList<>(mUserWords.size() + mLearnedWords.size());
        ArrayList<Integer> freqs = new ArrayList<>(words.size());
        for (String w : mUserWords) {
            words.add(w);
            freqs.add(GestureParams.USER_WORD_FREQ);
        }
        for (String w : mLearnedWords) {
            if (mUserWords.contains(w)) continue;
            words.add(w);
            freqs.add(GestureParams.USER_WORD_FREQ);
        }
        lexicon.setExtras(words, freqs);
    }
}
