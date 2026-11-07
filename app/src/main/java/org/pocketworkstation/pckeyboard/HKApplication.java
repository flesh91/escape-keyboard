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

import android.app.Application;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;

/** Applies Material You wallpaper colours to the app's screens on Android 12+. */
public class HKApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        // "settings_key" was a list (0 auto, 1 show, 2 hide); it is the switch "show_settings_key" now
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.contains("settings_key")) {
            prefs.edit()
                    .putBoolean(LatinIMESettings.PREF_SETTINGS_KEY, !"2".equals(prefs.getString("settings_key", "0")))
                    .remove("settings_key")
                    .apply();
        }
    }
}
