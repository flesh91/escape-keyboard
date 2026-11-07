/*
 * Modifications Copyright (C) 2026 Yaroslav Bezzubets
 * Licensed under the Apache License, Version 2.0
 */

package org.pocketworkstation.pckeyboard;

import java.util.Locale;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.slider.Slider;

/**
 * SeekBarPreference edits float-valued preferences with an inline Material slider.
 * The value is saved when the finger lifts, so the keyboard isn't rebuilt on every step.
 */
public class SeekBarPreference extends Preference {
    private float mMin;
    private float mMax;
    private float mVal;
    private float mStep;
    private boolean mAsPercent;
    private boolean mLogScale;
    private String mDisplayFormat;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_slider);
        setSelectable(false);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference);
        mMin = a.getFloat(R.styleable.SeekBarPreference_minValue, 0.0f);
        mMax = a.getFloat(R.styleable.SeekBarPreference_maxValue, 100.0f);
        mStep = a.getFloat(R.styleable.SeekBarPreference_step, 0.0f);
        mAsPercent = a.getBoolean(R.styleable.SeekBarPreference_asPercent, false);
        mLogScale = a.getBoolean(R.styleable.SeekBarPreference_logScale, false);
        mDisplayFormat = a.getString(R.styleable.SeekBarPreference_displayFormat);
        a.recycle();
    }

    @Override
    protected Object onGetDefaultValue(@NonNull TypedArray a, int index) {
        return a.getFloat(index, 0.0f);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        setVal(getPersistedFloat(defaultValue == null ? 0.0f : (Float) defaultValue));
    }

    private String formatFloatDisplay(Float val) {
        // Use current locale for format, this is for display only.
        if (mAsPercent) {
            return String.format("%d%%", (int) (val * 100));
        }

        if (mDisplayFormat != null) {
            return String.format(mDisplayFormat, val);
        } else {
            return Float.toString(val);
        }
    }

    protected void setVal(Float val) {
        mVal = val;
    }

    protected String getValString() {
        return Float.toString(mVal);
    }

    /** Saves the current value; subclasses choose the storage type. */
    protected void persistVal() {
        persistFloat(mVal);
    }

    private float percentToSteppedVal(int percent, float min, float max, float step, boolean logScale) {
        float val;
        if (logScale) {
            val = (float) Math.exp(percentToSteppedVal(percent, (float) Math.log(min), (float) Math.log(max), step, false));
        } else {
            float delta = percent * (max - min) / 100;
            if (step != 0.0f) {
                delta = Math.round(delta / step) * step;
            }
            val = min + delta;
        }
        // Hack: Round number to 2 significant digits so that it looks nicer.
        val = Float.valueOf(String.format(Locale.US, "%.2g", val));
        return val;
    }

    private int getPercent(float val, float min, float max) {
        return (int) (100 * (val - min) / (max - min));
    }

    private int getProgressVal() {
        int p;
        if (mLogScale) {
            p = getPercent((float) Math.log(mVal), (float) Math.log(mMin), (float) Math.log(mMax));
        } else {
            p = getPercent(mVal, mMin, mMax);
        }
        return Math.max(0, Math.min(100, p));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Slider slider = (Slider) holder.findViewById(R.id.slider);
        final TextView valText = (TextView) holder.findViewById(R.id.slider_value);
        slider.clearOnChangeListeners();
        slider.clearOnSliderTouchListeners();
        slider.setValue(getProgressVal());
        valText.setText(formatFloatDisplay(mVal));
        slider.setLabelFormatter(value -> formatFloatDisplay(
                percentToSteppedVal((int) value, mMin, mMax, mStep, mLogScale)));
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser) return;
            float newVal = percentToSteppedVal((int) value, mMin, mMax, mStep, mLogScale);
            if (newVal != mVal) {
                onChange(newVal);
            }
            setVal(newVal);
            valText.setText(formatFloatDisplay(mVal));
        });
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider s) {}

            @Override
            public void onStopTrackingTouch(@NonNull Slider s) {
                if (callChangeListener(mVal)) persistVal();
            }
        });
    }

    public void onChange(float val) {
        // override in subclasses
    }
}
