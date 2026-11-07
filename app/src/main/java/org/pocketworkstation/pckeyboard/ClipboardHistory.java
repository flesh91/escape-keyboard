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

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Texts copied while Escape Keyboard is the active keyboard: newest first, pinned ones on top.
 * Kept in the app's no-backup storage, so it never leaves the device; copies an app marks
 * as sensitive (passwords) are not kept.
 */
final class ClipboardHistory {
    private static final String TAG = "PCKeyboard";
    static final String PREF_ENABLED = "clipboard_history";
    static final String PREF_SIZE = "clipboard_history_size";
    static final String DEFAULT_SIZE = "20";
    /** Longer texts are not kept: the window shows a few lines anyway. */
    private static final int MAX_TEXT_LENGTH = 20000;

    static final class Entry {
        final long id;
        String text;
        boolean pinned;
        long time;

        Entry(long id, String text, boolean pinned, long time) {
            this.id = id;
            this.text = text;
            this.pinned = pinned;
            this.time = time;
        }
    }

    interface Listener {
        void onClipboardHistoryChanged();
    }

    private static ClipboardHistory sInstance;

    private final Context mContext;
    private final AtomicFile mFile;
    private final List<Entry> mEntries = new ArrayList<>();
    private long mNextId = 1;
    private Listener mListener;

    static synchronized ClipboardHistory get(Context context) {
        if (sInstance == null) sInstance = new ClipboardHistory(context.getApplicationContext());
        return sInstance;
    }

    private ClipboardHistory(Context context) {
        mContext = context;
        mFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "clipboard_history.json"));
        load();
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, true);
    }

    /** Remembers what is on the clipboard now, if history is on and the copy isn't sensitive. */
    void rememberPrimaryClip(ClipboardManager cm) {
        if (cm == null || !isEnabled(mContext)) return;
        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (SecurityException e) {
            return; // not the active keyboard: Android doesn't let us read it
        }
        if (clip == null || clip.getItemCount() == 0) return;
        ClipDescription desc = clip.getDescription();
        PersistableBundle extras = desc != null && Build.VERSION.SDK_INT >= 24 ? desc.getExtras() : null;
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33), set by password managers and the like
        if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
        CharSequence text = clip.getItemAt(0).getText();
        if (text != null) add(text.toString());
    }

    synchronized void add(String text) {
        if (text.trim().isEmpty() || text.length() > MAX_TEXT_LENGTH) return;
        long now = System.currentTimeMillis();
        for (Entry e : mEntries) {
            if (e.text.equals(text)) {
                e.time = now; // copied again: back to the top
                changed();
                return;
            }
        }
        mEntries.add(new Entry(mNextId++, text, false, now));
        trim();
        changed();
    }

    /** Pinned entries first, then the newest. */
    synchronized List<Entry> entries() {
        List<Entry> list = new ArrayList<>(mEntries);
        Collections.sort(list, (a, b) -> a.pinned != b.pinned ? (a.pinned ? -1 : 1)
                : Long.compare(b.time, a.time));
        return list;
    }

    synchronized Entry find(long id) {
        for (Entry e : mEntries) if (e.id == id) return e;
        return null;
    }

    synchronized void togglePin(long id) {
        Entry e = find(id);
        if (e == null) return;
        e.pinned = !e.pinned;
        trim();
        changed();
    }

    /** Removes an entry and returns it (for undo), or null. */
    synchronized Entry remove(long id) {
        Entry e = find(id);
        if (e == null || !mEntries.remove(e)) return null;
        changed();
        return e;
    }

    /** Puts a removed entry back where it was. */
    synchronized void restore(Entry e) {
        if (e == null || find(e.id) != null) return;
        mEntries.add(e);
        trim();
        changed();
    }

    synchronized void update(long id, String text) {
        Entry e = find(id);
        if (e == null || text.trim().isEmpty()) return;
        e.text = text;
        changed();
    }

    /** Removes everything but the pinned entries. */
    synchronized void clearUnpinned() {
        List<Entry> keep = new ArrayList<>();
        for (Entry e : mEntries) if (e.pinned) keep.add(e);
        mEntries.clear();
        mEntries.addAll(keep);
        changed();
    }

    /** Applies the size setting (pinned entries don't count). */
    synchronized void applySizeSetting() {
        if (trim()) changed();
    }

    private int maxSize() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        try {
            return Integer.parseInt(sp.getString(PREF_SIZE, DEFAULT_SIZE));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_SIZE);
        }
    }

    /** Drops the oldest unpinned entries over the limit. */
    private boolean trim() {
        int max = maxSize();
        boolean removed = false;
        while (true) {
            int unpinned = 0;
            Entry oldest = null;
            for (Entry e : mEntries) {
                if (e.pinned) continue;
                unpinned++;
                if (oldest == null || e.time < oldest.time) oldest = e;
            }
            if (unpinned <= max || oldest == null) return removed;
            mEntries.remove(oldest);
            removed = true;
        }
    }

    private void changed() {
        save();
        if (mListener != null) mListener.onClipboardHistoryChanged();
    }

    private void load() {
        try (FileInputStream in = mFile.openRead()) {
            byte[] data = new byte[in.available()];
            int got = 0;
            while (got < data.length) {
                int n = in.read(data, got, data.length - got);
                if (n < 0) break;
                got += n;
            }
            JSONArray array = new JSONArray(new String(data, 0, got, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                mEntries.add(new Entry(mNextId++, o.getString("text"), o.optBoolean("pinned"),
                        o.optLong("time")));
            }
        } catch (java.io.FileNotFoundException e) {
            // nothing copied yet
        } catch (Exception e) {
            Log.w(TAG, "Clipboard history unreadable, starting empty", e);
            mEntries.clear();
        }
    }

    private void save() {
        FileOutputStream out = null;
        try {
            JSONArray array = new JSONArray();
            for (Entry e : mEntries) {
                array.put(new JSONObject().put("text", e.text).put("pinned", e.pinned)
                        .put("time", e.time));
            }
            out = mFile.startWrite();
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            mFile.finishWrite(out);
        } catch (Exception e) {
            if (out != null) mFile.failWrite(out);
            Log.w(TAG, "Clipboard history not saved", e);
        }
    }
}
