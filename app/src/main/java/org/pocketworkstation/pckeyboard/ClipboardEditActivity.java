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

import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

/** Edits one clipboard history entry, in a dialog (the keyboard can't host a text field). */
public class ClipboardEditActivity extends AppCompatActivity {
    static final String EXTRA_ID = "id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final long id = getIntent().getLongExtra(EXTRA_ID, -1);
        final ClipboardHistory history = ClipboardHistory.get(this);
        ClipboardHistory.Entry entry = history.find(id);
        if (entry == null) {
            finish();
            return;
        }
        setContentView(R.layout.clipboard_edit);
        final EditText text = findViewById(R.id.clipboard_text);
        if (savedInstanceState == null) {
            text.setText(entry.text);
            text.setSelection(text.length());
        }
        text.requestFocus();
        findViewById(R.id.clipboard_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.clipboard_save).setOnClickListener(v -> {
            history.update(id, text.getText().toString());
            finish();
        });
    }
}
