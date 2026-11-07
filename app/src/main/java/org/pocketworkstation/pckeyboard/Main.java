/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

public class Main extends AppCompatActivity {

    private View mStepEnable;
    private View mStepSelect;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        SettingsActivity.applySystemBarIcons(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ((CollapsingToolbarLayout) findViewById(R.id.collapsing_toolbar))
                .setTitle(getString(R.string.english_ime_name));

        final View scroll = findViewById(R.id.main_scroll);
        final int extra = (int) (16 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, v.getPaddingTop(), bars.right, bars.bottom + extra);
            return insets;
        });

        mStepEnable = findViewById(R.id.step_enable);
        mStepSelect = findViewById(R.id.step_select);
        View stepLanguages = findViewById(R.id.step_languages);
        setStep(stepLanguages, R.drawable.ic_language, R.string.main_setup_input_lang,
                getString(R.string.main_step_languages_summary), false);
        roundGroup(new View[] { mStepEnable, mStepSelect, stepLanguages });

        mStepEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        mStepSelect.setOnClickListener(v -> {
            InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            mgr.showInputMethodPicker();
        });
        stepLanguages.setOnClickListener(v ->
                startActivity(new Intent(this, InputLanguageSelection.class)));
        findViewById(R.id.main_setup_btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, LatinIMESettings.class)));

        TextView description = findViewById(R.id.main_description);
        description.setMovementMethod(LinkMovementMethod.getInstance());
        description.setText(withoutQuoteStripes(Html.fromHtml(getString(R.string.main_body))));

        String version = getString(R.string.auto_version);
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // keep the resource version
        }
        ((TextView) findViewById(R.id.main_version)).setText(getString(R.string.main_version, version));
    }

    /** The help text uses <blockquote> for its lists; show them indented, without the blue bar. */
    private CharSequence withoutQuoteStripes(Spanned html) {
        SpannableStringBuilder text = new SpannableStringBuilder(html);
        int indent = (int) (12 * getResources().getDisplayMetrics().density);
        for (QuoteSpan quote : text.getSpans(0, text.length(), QuoteSpan.class)) {
            int start = text.getSpanStart(quote);
            int end = text.getSpanEnd(quote);
            text.removeSpan(quote);
            text.setSpan(new LeadingMarginSpan.Standard(indent), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSteps();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The input method picker is a dialog over us, not a new activity
        if (hasFocus) updateSteps();
    }

    private void updateSteps() {
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (imi.getPackageName().equals(getPackageName())) enabled = true;
        }
        boolean selected = false;
        try {
            String current = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            selected = current != null && current.startsWith(getPackageName() + "/");
        } catch (SecurityException e) {
            // not readable on this device: just don't show the tick
        }
        setStep(mStepEnable, enabled ? R.drawable.ic_check_circle : R.drawable.ic_radio_unchecked,
                R.string.main_step_enable_title,
                getString(enabled ? R.string.main_step_enable_done : R.string.main_step_enable_summary),
                enabled);
        setStep(mStepSelect, selected ? R.drawable.ic_check_circle : R.drawable.ic_radio_unchecked,
                R.string.main_step_select_title,
                getString(selected ? R.string.main_step_select_done : R.string.main_step_select_summary),
                selected);
    }

    private void setStep(View step, int icon, int title, String summary, boolean done) {
        ImageView iconView = step.findViewById(R.id.step_icon);
        iconView.setImageResource(icon);
        iconView.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(
                step, done ? androidx.appcompat.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurfaceVariant)));
        ((TextView) step.findViewById(R.id.step_title)).setText(title);
        ((TextView) step.findViewById(R.id.step_summary)).setText(summary);
    }

    /** Large outer corners on the first and last card of a group, like the settings screens. */
    private void roundGroup(View[] cards) {
        float outer = 24 * getResources().getDisplayMetrics().density;
        float inner = 4 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < cards.length; i++) {
            float top = i == 0 ? outer : inner;
            float bottom = i == cards.length - 1 ? outer : inner;
            MaterialCardView card = (MaterialCardView) cards[i];
            card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, top)
                    .setTopRightCorner(CornerFamily.ROUNDED, top)
                    .setBottomLeftCorner(CornerFamily.ROUNDED, bottom)
                    .setBottomRightCorner(CornerFamily.ROUNDED, bottom)
                    .build());
        }
    }
}
