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

import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.speech.RecognizerIntent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

/**
 * What the mic key hands voice input to ("pref_voice_engine"): "auto" (the first voice typing
 * keyboard), "ime:<id>[:<subtype hash>]" (another keyboard, its voice subtype if it has one) or
 * "recognizer:<component>" (a speech recognizer app, through VoiceRecognitionActivity).
 * Escape Keyboard itself never records audio.
 */
final class VoiceEngines {
    static final String PREF_KEY = "pref_voice_engine";
    static final String AUTO = "auto";
    static final String PREFIX_IME = "ime:";
    static final String PREFIX_RECOGNIZER = "recognizer:";

    private VoiceEngines() {}

    /** Enabled keyboards other than this one. */
    static List<InputMethodInfo> otherInputMethods(Context context, InputMethodManager imm) {
        List<InputMethodInfo> list = new ArrayList<>();
        for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (!imi.getPackageName().equals(context.getPackageName())) list.add(imi);
        }
        return list;
    }

    /** The keyboard's enabled subtypes in "voice" mode. */
    static List<InputMethodSubtype> voiceSubtypes(InputMethodManager imm, InputMethodInfo imi) {
        List<InputMethodSubtype> list = new ArrayList<>();
        for (InputMethodSubtype subtype : imm.getEnabledInputMethodSubtypeList(imi, true)) {
            if ("voice".equals(subtype.getMode())) list.add(subtype);
        }
        return list;
    }

    static List<ResolveInfo> recognizers(Context context) {
        List<ResolveInfo> list = new ArrayList<>();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(intent, 0)) {
            if (ri.activityInfo == null) continue;
            if (ri.activityInfo.packageName.equals(context.getPackageName())) continue;
            list.add(ri);
        }
        return list;
    }

    /** Settings list: labels and stored values, "Automatic" first. */
    static void listChoices(Context context, List<CharSequence> labels, List<CharSequence> values) {
        PackageManager pm = context.getPackageManager();
        labels.add(context.getString(R.string.voice_engine_auto));
        values.add(AUTO);

        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> plain = new ArrayList<>();
        for (InputMethodInfo imi : otherInputMethods(context, imm)) {
            List<InputMethodSubtype> voice = voiceSubtypes(imm, imi);
            if (voice.isEmpty()) {
                plain.add(imi);
                continue;
            }
            CharSequence name = imi.loadLabel(pm);
            for (InputMethodSubtype subtype : voice) {
                if (voice.size() == 1) {
                    labels.add(context.getString(R.string.voice_engine_ime_voice, name));
                } else {
                    CharSequence sub = subtype.getDisplayName(context, imi.getPackageName(),
                            imi.getServiceInfo().applicationInfo);
                    labels.add(context.getString(R.string.voice_engine_ime_voice_subtype, name, sub));
                }
                values.add(PREFIX_IME + imi.getId() + ":" + subtype.hashCode());
            }
        }
        // Keyboards that don't say they do voice may still be voice-only ones
        for (InputMethodInfo imi : plain) {
            labels.add(context.getString(R.string.voice_engine_ime, imi.loadLabel(pm)));
            values.add(PREFIX_IME + imi.getId());
        }

        for (ResolveInfo ri : recognizers(context)) {
            labels.add(context.getString(R.string.voice_engine_recognizer, ri.loadLabel(pm)));
            values.add(PREFIX_RECOGNIZER + new ComponentName(
                    ri.activityInfo.packageName, ri.activityInfo.name).flattenToString());
        }
    }

    /** A keyboard and its voice subtype (null: the keyboard itself), as the mic key uses them. */
    static final class ImeChoice {
        final String id;
        final InputMethodSubtype subtype;

        ImeChoice(String id, InputMethodSubtype subtype) {
            this.id = id;
            this.subtype = subtype;
        }

        /** The value the settings list has for it. */
        String value() {
            return PREFIX_IME + id + (subtype != null ? ":" + subtype.hashCode() : "");
        }
    }

    /**
     * What a saved "ime:" value means now: the keyboard's voice subtype (its first one if the
     * saved one changed, or if the keyboard has one by now), or the keyboard itself when it has
     * none. Null when the keyboard is gone, or the voice subtype saved is (Automatic is used).
     * The mic key and the settings screen both go by this.
     */
    static ImeChoice resolveIme(Context context, InputMethodManager imm, String value) {
        String id = imeId(value);
        Integer hash = subtypeHash(value);
        for (InputMethodInfo imi : otherInputMethods(context, imm)) {
            if (!imi.getId().equals(id)) continue;
            List<InputMethodSubtype> voice = voiceSubtypes(imm, imi);
            if (voice.isEmpty()) return hash == null ? new ImeChoice(id, null) : null;
            InputMethodSubtype subtype = voice.get(0);
            for (InputMethodSubtype s : voice) {
                if (hash != null && s.hashCode() == hash) subtype = s;
            }
            return new ImeChoice(id, subtype);
        }
        return null;
    }

    /** The keyboard id in an "ime:" value. */
    static String imeId(String value) {
        String rest = value.substring(PREFIX_IME.length());
        int colon = rest.lastIndexOf(':');
        if (colon > 0 && parseHash(rest.substring(colon + 1)) != null) {
            return rest.substring(0, colon);
        }
        return rest;
    }

    /** The voice subtype's hash in an "ime:" value, or null when it names the keyboard only. */
    static Integer subtypeHash(String value) {
        String rest = value.substring(PREFIX_IME.length());
        int colon = rest.lastIndexOf(':');
        return colon > 0 ? parseHash(rest.substring(colon + 1)) : null;
    }

    private static Integer parseHash(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The recognizer in a "recognizer:" value if it's still installed, else null. */
    static ComponentName recognizer(Context context, String value) {
        ComponentName cn = ComponentName.unflattenFromString(
                value.substring(PREFIX_RECOGNIZER.length()));
        if (cn == null) return null;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setComponent(cn);
        return context.getPackageManager().queryIntentActivities(intent, 0).isEmpty() ? null : cn;
    }
}
