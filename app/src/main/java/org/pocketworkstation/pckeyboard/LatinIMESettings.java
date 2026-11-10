/*
 * Copyright (C) 2008 The Android Open Source Project
 * Modifications Copyright (C) 2026 Yaroslav Bezzubets
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

import java.util.HashMap;
import java.util.Map;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.AutoText;
import android.text.InputType;
import android.util.Log;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

public class LatinIMESettings extends SettingsActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private static final String QUICK_FIXES_KEY = "quick_fixes";
    private static final String PREDICTION_SETTINGS_KEY = "prediction_settings";
    private static final String VOICE_SETTINGS_KEY = "voice_mode";
    /* package */ static final String PREF_SETTINGS_KEY = "pref_settings_key";
    static final String INPUT_CONNECTION_INFO = "input_connection_info";    
    private static final String DEBUGGING_KEY = "debugging";
    private static final String CLEAR_LEARNED_WORDS_KEY = "clear_learned_words";
    private static final String PREF_DEBUG_UNLOCKED = "debug_settings_unlocked";
    private static final int DEBUG_TAPS = 5;

    private static final String TAG = "LatinIMESettings";

    @Override
    protected Fragment createFragment() {
        return new MainPrefs();
    }

    /** A section row on the main screen opens its screen in place, with its title. */
    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        Bundle args = new Bundle(pref.getExtras());
        args.putCharSequence(ARG_TITLE, pref.getTitle());
        Fragment f = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), pref.getFragment());
        f.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.settings_open_enter, R.anim.settings_open_exit,
                        R.anim.settings_close_enter, R.anim.settings_close_exit)
                .replace(R.id.settings_container, f)
                .addToBackStack(null)
                .commit();
        return true;
    }

    /** Every settings screen: the main list (no arguments) or one section (argument "xml"). */
    public static class MainPrefs extends PrefsFragment {
        private Preference mQuickFixes;
        private ListPreference mVoicePreference;
        private ListPreference mKeyboardModePortraitPreference;
        private ListPreference mKeyboardModeLandscapePreference;
        private Preference mInputConnectionInfo;
        private Preference mLabelVersion;
        private Preference mDebugging;
        private int mVersionTaps;
        private Toast mToast;

        @Override
        protected int getPreferencesResId() {
            String xml = getArguments() != null ? getArguments().getString("xml") : null;
            if (xml == null) return R.xml.prefs;
            switch (xml) {
                case "prefs_layout": return R.xml.prefs_layout;
                case "prefs_appearance": return R.xml.prefs_appearance;
                case "prefs_typing": return R.xml.prefs_typing;
                case "prefs_correction": return R.xml.prefs_correction;
                case "prefs_clipboard": return R.xml.prefs_clipboard;
                case "prefs_feedback": return R.xml.prefs_feedback;
                case "prefs_actions": return R.xml.prefs_actions;
                case "prefs_terminal": return R.xml.prefs_terminal;
                case "prefs_advanced": return R.xml.prefs_advanced;
                default: return R.xml.prefs;
            }
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            mQuickFixes = findPreference(QUICK_FIXES_KEY);
            mVoicePreference = findPreference(VOICE_SETTINGS_KEY);
            mInputConnectionInfo = findPreference(INPUT_CONNECTION_INFO);
            mLabelVersion = findPreference("label_version");
            if (mLabelVersion != null) setupDebugging();
            Preference clearLearnedWords = findPreference(CLEAR_LEARNED_WORDS_KEY);
            if (clearLearnedWords != null) {
                clearLearnedWords.setOnPreferenceClickListener(p -> {
                    confirmClearLearnedWords();
                    return true;
                });
            }
            // "Always incognito" lives on another screen: say here why nothing new is kept
            androidx.preference.SwitchPreferenceCompat clipboardHistory =
                    findPreference(ClipboardHistory.PREF_ENABLED);
            if (clipboardHistory != null && getPreferenceManager().getSharedPreferences()
                    .getBoolean(LatinIME.PREF_INCOGNITO_ALWAYS,
                            getResources().getBoolean(R.bool.default_incognito_always))) {
                clipboardHistory.setSummaryOn(R.string.summary_clipboard_history_incognito);
            }
            Preference licenses = findPreference("licenses");
            if (licenses != null) {
                licenses.setIntent(new android.content.Intent(requireContext(), LicensesActivity.class));
            }

            // TODO(klausw): remove these when no longer needed
            mKeyboardModePortraitPreference = findPreference("pref_keyboard_mode_portrait");
            mKeyboardModeLandscapePreference = findPreference("pref_keyboard_mode_landscape");
        }

        /**
         * The debugging section is hidden until "Version" is tapped 5 times, like Android's
         * developer options; it stays shown until hidden again from inside the section.
         */
        private void setupDebugging() {
            final SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            mDebugging = findPreference(DEBUGGING_KEY);
            mDebugging.setVisible(prefs.getBoolean(PREF_DEBUG_UNLOCKED, false));
            mLabelVersion.setOnPreferenceClickListener(p -> {
                if (mDebugging.isVisible()) {
                    toast(getString(R.string.debug_already_shown));
                    return true;
                }
                int left = DEBUG_TAPS - ++mVersionTaps;
                if (left <= 0) {
                    prefs.edit().putBoolean(PREF_DEBUG_UNLOCKED, true).apply();
                    mDebugging.setVisible(true);
                    toast(getString(R.string.debug_shown));
                } else if (left <= 3) {
                    toast(getResources().getQuantityString(R.plurals.debug_taps_left, left, left));
                }
                return true;
            });
            findPreference("debug_settings_hide").setOnPreferenceClickListener(p -> {
                prefs.edit().putBoolean(PREF_DEBUG_UNLOCKED, false).apply();
                androidx.preference.SwitchPreferenceCompat touchPos = findPreference("pref_touch_pos");
                if (touchPos != null) touchPos.setChecked(false);
                mDebugging.setVisible(false);
                mVersionTaps = 0;
                toast(getString(R.string.debug_hidden));
                return true;
            });
        }

        private void confirmClearLearnedWords() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_clear_learned_words)
                    .setMessage(R.string.confirm_clear_learned_words)
                    .setPositiveButton(R.string.button_clear_learned_words, (dialog, which) -> {
                        LatinIME.clearLearnedWords(requireContext());
                        toast(getString(R.string.learned_words_cleared));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void toast(String text) {
            if (mToast != null) mToast.cancel();
            mToast = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT);
            mToast.show();
        }

        @Override
        public void onResume() {
            super.onResume();
            if (mQuickFixes != null && AutoText.getSize(getListView()) < 1) {
                ((PreferenceGroup) findPreference(PREDICTION_SETTINGS_KEY))
                        .removePreference(mQuickFixes);
                mQuickFixes = null;
            }

            Log.i(TAG, "compactModeEnabled=" + LatinIME.sKeyboardSettings.compactModeEnabled);
            if (!LatinIME.sKeyboardSettings.compactModeEnabled && mKeyboardModePortraitPreference != null) {
                CharSequence[] oldEntries = mKeyboardModePortraitPreference.getEntries();
                CharSequence[] oldValues = mKeyboardModePortraitPreference.getEntryValues();

                if (oldEntries.length > 2) {
                    CharSequence[] newEntries = new CharSequence[] { oldEntries[0], oldEntries[2] };
                    CharSequence[] newValues = new CharSequence[] { oldValues[0], oldValues[2] };
                    mKeyboardModePortraitPreference.setEntries(newEntries);
                    mKeyboardModePortraitPreference.setEntryValues(newValues);
                    mKeyboardModeLandscapePreference.setEntries(newEntries);
                    mKeyboardModeLandscapePreference.setEntryValues(newValues);
                }
            }

            updateSummaries();
            updateVoiceModeSummary();

            ListPreference renderMode = findPreference(LatinIME.PREF_RENDER_MODE);
            if (renderMode != null && LatinKeyboardBaseView.sSetRenderMode == null) {
                renderMode.setEnabled(false);
                renderMode.setSummary(R.string.render_mode_unavailable);
            }

            if (mLabelVersion != null) {
                String version = "custom";
                try {
                    version = requireContext().getPackageManager()
                            .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Could not find version info.");
                }
                mLabelVersion.setSummary(version);
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            super.onSharedPreferenceChanged(prefs, key);
            updateVoiceModeSummary();
            updateSummaries();
        }

        private void updateSummaries() {
            Resources res = getResources();

            if (mInputConnectionInfo != null) mInputConnectionInfo.setSummary(String.format("%s type=%s",
                    LatinIME.sKeyboardSettings.editorPackageName,
                    inputTypeDesc(LatinIME.sKeyboardSettings.editorInputType)
                    ));
        }

        private void updateVoiceModeSummary() {
            if (mVoicePreference == null) return;
            int index = mVoicePreference.findIndexOfValue(mVoicePreference.getValue());
            if (index >= 0) {
                mVoicePreference.setSummary(
                        getResources().getStringArray(R.array.voice_input_modes_summary)[index]);
            }
        }
    }

    static Map<Integer, String> INPUT_CLASSES = new HashMap<Integer, String>();
    static Map<Integer, String> DATETIME_VARIATIONS = new HashMap<Integer, String>();
    static Map<Integer, String> TEXT_VARIATIONS = new HashMap<Integer, String>();
    static Map<Integer, String> NUMBER_VARIATIONS = new HashMap<Integer, String>();
    static {
        INPUT_CLASSES.put(0x00000004, "DATETIME");
        INPUT_CLASSES.put(0x00000002, "NUMBER");
        INPUT_CLASSES.put(0x00000003, "PHONE");
        INPUT_CLASSES.put(0x00000001, "TEXT"); 
        INPUT_CLASSES.put(0x00000000, "NULL");
        
        DATETIME_VARIATIONS.put(0x00000010, "DATE");
        DATETIME_VARIATIONS.put(0x00000020, "TIME");

        NUMBER_VARIATIONS.put(0x00000010, "PASSWORD");

        TEXT_VARIATIONS.put(0x00000020, "EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000030, "EMAIL_SUBJECT");
        TEXT_VARIATIONS.put(0x000000b0, "FILTER");
        TEXT_VARIATIONS.put(0x00000050, "LONG_MESSAGE");
        TEXT_VARIATIONS.put(0x00000080, "PASSWORD");
        TEXT_VARIATIONS.put(0x00000060, "PERSON_NAME");
        TEXT_VARIATIONS.put(0x000000c0, "PHONETIC");
        TEXT_VARIATIONS.put(0x00000070, "POSTAL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000040, "SHORT_MESSAGE");
        TEXT_VARIATIONS.put(0x00000010, "URI");
        TEXT_VARIATIONS.put(0x00000090, "VISIBLE_PASSWORD");
        TEXT_VARIATIONS.put(0x000000a0, "WEB_EDIT_TEXT");
        TEXT_VARIATIONS.put(0x000000d0, "WEB_EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x000000e0, "WEB_PASSWORD");

    }
    
    private static void addBit(StringBuffer buf, int bit, String str) {
        if (bit != 0) {
            buf.append("|");
            buf.append(str);
        }
    }

    private static String inputTypeDesc(int type) {
        int cls = type & 0x0000000f; // MASK_CLASS
        int flags = type & 0x00fff000; // MASK_FLAGS
        int var = type &  0x00000ff0; // MASK_VARIATION

        StringBuffer out = new StringBuffer();
        String clsName = INPUT_CLASSES.get(cls);
        out.append(clsName != null ? clsName : "?");
        
        if (cls == InputType.TYPE_CLASS_TEXT) {
            String varName = TEXT_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00010000, "AUTO_COMPLETE");
            addBit(out, flags & 0x00008000, "AUTO_CORRECT");
            addBit(out, flags & 0x00001000, "CAP_CHARACTERS");
            addBit(out, flags & 0x00004000, "CAP_SENTENCES");
            addBit(out, flags & 0x00002000, "CAP_WORDS");
            addBit(out, flags & 0x00040000, "IME_MULTI_LINE");
            addBit(out, flags & 0x00020000, "MULTI_LINE");
            addBit(out, flags & 0x00080000, "NO_SUGGESTIONS");
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            String varName = NUMBER_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00002000, "DECIMAL");
            addBit(out, flags & 0x00001000, "SIGNED");        
        } else if (cls == InputType.TYPE_CLASS_DATETIME) {
            String varName = DATETIME_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
        }
        return out.toString();
    }

}
