/*
 * Modifications Copyright (C) 2026 Yaroslav Bezzubets
 * Licensed under the Apache License, Version 2.0
 */

package org.pocketworkstation.pckeyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;

/**
 * Variant of SeekBarPreference that stores values as string preferences.
 *
 * This is for compatibility with existing preferences, switching types
 * leads to runtime errors when upgrading or downgrading.
 */
public class SeekBarPreferenceString extends SeekBarPreference {

    private static Pattern FLOAT_RE = Pattern.compile("(\\d+\\.?\\d*).*");

    public SeekBarPreferenceString(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Some saved preferences from old versions have " ms" or "%" suffix, remove that.
    private float floatFromString(String pref) {
        Matcher num = FLOAT_RE.matcher(pref);
        if (!num.matches()) return 0.0f;
        return Float.valueOf(num.group(1));
    }

    @Override
    protected Object onGetDefaultValue(@NonNull TypedArray a, int index) {
        return floatFromString(a.getString(index));
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        String persisted = getPersistedString(null);
        if (persisted != null) {
            setVal(floatFromString(persisted));
        } else {
            setVal(defaultValue == null ? 0.0f : (Float) defaultValue);
        }
    }

    @Override
    protected void persistVal() {
        persistString(getValString());
    }
}
