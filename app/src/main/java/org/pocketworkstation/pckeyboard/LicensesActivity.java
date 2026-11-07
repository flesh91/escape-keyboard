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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

/** Shows the bundled NOTICE and Apache License 2.0 texts (assets/licenses). */
public class LicensesActivity extends SettingsActivity {

    @Override
    protected Fragment createFragment() {
        return new Licenses();
    }

    public static class Licenses extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            float d = getResources().getDisplayMetrics().density;
            NestedScrollView scroll = new NestedScrollView(requireContext());
            scroll.setClipToPadding(false);
            TextView text = new TextView(requireContext());
            int pad = (int) (20 * d);
            text.setPadding(pad, 0, pad, pad);
            text.setTextIsSelectable(true);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(12);
            text.setText(read("licenses/NOTICE.txt") + "\n\n" + read("licenses/LICENSE.txt"));
            scroll.addView(text);
            ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(bars.left, 0, bars.right, bars.bottom);
                return insets;
            });
            return scroll;
        }

        private String read(String asset) {
            StringBuilder out = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    requireContext().getAssets().open(asset), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) out.append(line).append('\n');
            } catch (IOException e) {
                out.append(asset).append(": ").append(e.getMessage());
            }
            return out.toString();
        }
    }
}
