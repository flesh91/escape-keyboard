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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * Invisible go-between for a speech recognizer app: a keyboard can't receive an activity
 * result, so this asks the recognizer and hands the best text to LatinIME, which types it
 * once the field is back. The recognizer does the listening; this app never records audio.
 */
public class VoiceRecognitionActivity extends Activity {
    static final String EXTRA_RECOGNIZER = "recognizer";
    static final String EXTRA_LANGUAGE = "language";
    private static final int REQUEST_SPEECH = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // recreated: the recognizer is already up
        String flat = getIntent().getStringExtra(EXTRA_RECOGNIZER);
        ComponentName recognizer = flat != null ? ComponentName.unflattenFromString(flat) : null;
        if (recognizer == null) {
            finish();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .setComponent(recognizer)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        String language = getIntent().getStringExtra(EXTRA_LANGUAGE);
        if (language != null) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        try {
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.voice_engine_failed, Toast.LENGTH_LONG).show();
            LatinIME.onVoiceResult(null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH) return;
        ArrayList<String> results = data != null
                ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        String text = results != null && !results.isEmpty() ? results.get(0) : null;
        if (resultCode == RESULT_OK && !TextUtils.isEmpty(text)) {
            LatinIME.onVoiceResult(text);
        } else {
            Toast.makeText(this, R.string.voice_no_match, Toast.LENGTH_SHORT).show();
            LatinIME.onVoiceResult(null);
        }
        finish();
    }
}
