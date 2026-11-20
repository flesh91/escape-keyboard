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

import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Clipboard history in a floating window over the keyboard. It is a popup of the keyboard's
 * own window, so it needs no "display over other apps" permission, and it goes away with the
 * keyboard. Drag it by the title bar, resize it by a bottom corner (place and size are
 * remembered); tap an entry to paste it, swipe it aside to delete it, hold it for
 * pin/edit/delete. Nothing in it takes focus: a focused popup would take it from the text
 * field and close the keyboard.
 */
final class ClipboardWindow implements ClipboardHistory.Listener {
    private static final long UNDO_MS = 5000;
    /** How often the "5 min ago" lines are brought up to date while the window is open. */
    private static final long AGE_TICK_MS = 30000;
    /** The corner grips: touch target, and the smallest window they make. */
    private static final int GRIP_DP = 36, MIN_WIDTH_DP = 240, MIN_HEIGHT_DP = 160;

    private final LatinIME mIme;
    private final ClipboardHistory mHistory;
    private PopupWindow mPopup;
    private LinearLayout mList;
    private TextView mTitle;
    private Context mCtx;
    private int mTextColor, mSecondaryColor, mPinnedColor, mKeyColor, mBarTextColor;
    private float mKeyRadius;
    private int mX, mY;
    // Where the keyboard area is: the window position is kept relative to its top left
    private int mAreaX, mAreaY, mWindowTopOnScreen, mStatusBar, mScreenWidth, mScreenHeight;
    private int mWidth, mHeight;
    // Without a remembered size the window has this one; resizing stops at the minimum
    private int mDefaultWidth, mDefaultHeight, mMinWidth, mMinHeight;
    private long mExpandedId = -1;  // entry showing its pin/edit/delete strip
    private LinearLayout mUndoBar;
    private TextView mUndoText;
    private Runnable mUndo;  // puts back what the last delete or clear removed
    private final Runnable mHideUndo = this::hideUndo;
    private final List<TextView> mAges = new ArrayList<>();  // tagged with the copy time
    private final Runnable mAgeTick = this::updateAges;

    private static final String PREF_POSITION = "clipboard_window_";

    private static final Pattern CODE = Pattern.compile("\\d{4,8}");
    private static final Pattern DATE = Pattern.compile("\\d{1,4}([-/])\\d{1,2}\\1\\d{1,4}");
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*://");

    /** Colours and corners of the window; LatinIME matches them to the keyboard theme. */
    static final class Style {
        boolean light;     // ripples, and the plain colours below
        int bar, surface;  // title bar, list
        int text, secondary, accent, divider;
        int outline;       // a rim like the theme's keycaps, 0 for none
        int key;           // an entry's tile: the colour of the theme's letter keys
        int barText;       // Clear, close and the empty list, on the case (0: text)
        float keyRadius = 6; // dp, the keys' corners
        float radius = 16; // dp

        /** The plain window of the classic themes. */
        static Style plain(boolean light) {
            Style s = new Style();
            s.light = light;
            s.bar = s.surface = light ? 0xFFFFFFFF : 0xFF2D2F31;
            s.text = light ? 0xFF1F1F1F : 0xFFE3E3E3;
            s.secondary = light ? 0xFF5F6368 : 0xFF9AA0A6;
            s.accent = light ? 0xFFE8710A : 0xFFFFB74D;
            s.divider = light ? 0x1F000000 : 0x33FFFFFF;
            return s;
        }

        Style colors(android.content.res.Resources res, int bar, int surface, int text,
                int secondary, int accent) {
            this.bar = res.getColor(bar);
            this.surface = res.getColor(surface);
            this.text = res.getColor(text);
            this.secondary = res.getColor(secondary);
            this.accent = res.getColor(accent);
            return this;
        }
    }

    ClipboardWindow(LatinIME ime) {
        mIme = ime;
        mHistory = ClipboardHistory.get(ime);
    }

    boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    void dismiss() {
        if (mUndoBar != null) mUndoBar.removeCallbacks(mHideUndo);
        if (mList != null) mList.removeCallbacks(mAgeTick);
        mUndoBar = null;
        mUndoText = null;
        mUndo = null;
        mExpandedId = -1;
        if (mPopup != null) mPopup.dismiss();
        mPopup = null;
        mList = null;
        mTitle = null;
        mAges.clear();
        mHistory.setListener(null);
    }

    /**
     * Opens where the user last dragged it (per orientation), at first above the keyboard area
     * on the right; over the keyboard only when there is no room above it.
     */
    void show(View anchor, View inputArea, Style style) {
        dismiss();
        mCtx = new ContextThemeWrapper(mIme,
                style.light ? R.style.HK_EmojiPanel_Light : R.style.HK_EmojiPanel_Dark);
        float d = mIme.getResources().getDisplayMetrics().density;
        float radius = style.radius * d;
        mTextColor = style.text;
        mSecondaryColor = style.secondary;
        mBarTextColor = style.barText != 0 ? style.barText : style.text;
        mPinnedColor = style.accent;
        mKeyColor = style.key != 0 ? style.key : style.surface;
        mKeyRadius = style.keyRadius * d;

        LinearLayout root = new LinearLayout(mCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable frame = roundRect(style.surface, radius);
        int rim = 0;
        if (style.outline != 0) {
            // the content sits inside the rim, so the title bar doesn't paint over it
            rim = Math.max(1, Math.round(1.5f * d));
            frame.setStroke(rim, style.outline);
            root.setPadding(rim, rim, rim, rim);
        }
        root.setBackground(frame);
        root.setClipToOutline(true);
        float inner = Math.max(0, radius - rim);

        LinearLayout bar = new LinearLayout(mCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBackground = new GradientDrawable();
        barBackground.setColor(style.bar);
        barBackground.setCornerRadii(new float[] {inner, inner, inner, inner, 0, 0, 0, 0});
        bar.setBackground(barBackground);
        bar.setPadding((int) (16 * d), 0, (int) (4 * d), 0);
        // Just the count, "12/20" in a pill (showCount); TalkBack reads "Clipboard · 12/20"
        mTitle = new TextView(mCtx);
        mTitle.setTextColor(mSecondaryColor);
        mTitle.setTextSize(13);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setGravity(Gravity.CENTER);
        mTitle.setMinHeight((int) (24 * d));
        mTitle.setPadding((int) (10 * d), (int) (2 * d), (int) (10 * d), (int) (2 * d));
        mTitle.setBackground(roundRect(mKeyColor, Math.min(mKeyRadius, 12 * d)));  // a small key
        bar.addView(mTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(new View(mCtx), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView clear = new TextView(mCtx);
        clear.setText(R.string.clipboard_clear);
        clear.setTextColor(mBarTextColor);
        clear.setTextSize(14);
        clear.setTypeface(Typeface.DEFAULT_BOLD);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        clear.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        clear.setOnClickListener(v -> clear());
        clear.setOnTouchListener(mIme.feedbackOnTouch(0));
        bar.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView close = new ImageView(mCtx);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(mBarTextColor, PorterDuff.Mode.SRC_IN);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        close.setContentDescription(mIme.getString(R.string.clipboard_close));
        close.setOnClickListener(v -> dismiss());
        close.setOnTouchListener(mIme.feedbackOnTouch(0));
        bar.addView(close, new LinearLayout.LayoutParams((int) (48 * d),
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d)));

        ScrollView scroll = new ScrollView(mCtx);
        // room at the end to scroll the last entry clear of the corner grips
        int grip = (int) (GRIP_DP * d);
        scroll.setPadding(0, 0, 0, grip);
        scroll.setClipToPadding(false);
        mList = new LinearLayout(mCtx);
        mList.setOrientation(LinearLayout.VERTICAL);
        // entries as keys on the keyboard's case, with the gaps between keys
        mList.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
        mList.setClipToPadding(false);  // a tile swiped aside slides out under the window edge
        scroll.addView(mList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // "Entry deleted · Undo", for a few seconds after a delete or a clear. Undo ends
        // where the resize grip begins, so the grip doesn't take its taps.
        mUndoBar = new LinearLayout(mCtx);
        mUndoBar.setOrientation(LinearLayout.HORIZONTAL);
        mUndoBar.setGravity(Gravity.CENTER_VERTICAL);
        mUndoBar.setPadding((int) (16 * d), 0, grip, 0);
        mUndoBar.setBackground(roundRect(mKeyColor, mKeyRadius));
        mUndoText = new TextView(mCtx);
        mUndoText.setText(R.string.clipboard_deleted);
        mUndoText.setTextColor(mSecondaryColor);
        mUndoText.setTextSize(14);
        mUndoText.setSingleLine(true);
        mUndoText.setEllipsize(TextUtils.TruncateAt.END);
        mUndoBar.addView(mUndoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView undo = new TextView(mCtx);
        undo.setText(R.string.clipboard_undo);
        undo.setTextColor(mTextColor);
        undo.setTextSize(14);
        undo.setTypeface(Typeface.DEFAULT_BOLD);
        undo.setGravity(Gravity.CENTER);
        undo.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        undo.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        undo.setOnTouchListener(mIme.feedbackOnTouch(0));
        undo.setOnClickListener(v -> {
            Runnable restore = mUndo;
            hideUndo();
            if (restore != null) restore.run();
        });
        mUndoBar.addView(undo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mUndoBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d));
        undoParams.setMargins((int) (6 * d), 0, (int) (6 * d), (int) (6 * d));
        root.addView(mUndoBar, undoParams);

        // The resize grips lie over the content, in the bottom corners
        FrameLayout content = new FrameLayout(mCtx);
        content.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(new ResizeGrip(false, d),
                new FrameLayout.LayoutParams(grip, grip, Gravity.BOTTOM | Gravity.RIGHT));
        content.addView(new ResizeGrip(true, d),
                new FrameLayout.LayoutParams(grip, grip, Gravity.BOTTOM | Gravity.LEFT));

        int anchorWidth = anchor.getWidth();
        int margin = (int) (8 * d);
        int[] area = new int[2];
        inputArea.getLocationInWindow(area);
        int[] areaOnScreen = new int[2];
        inputArea.getLocationOnScreen(areaOnScreen);
        mAreaX = area[0];
        mAreaY = area[1];
        mWindowTopOnScreen = areaOnScreen[1] - area[1];
        mStatusBar = statusBarHeight(d);
        mScreenWidth = mIme.getResources().getDisplayMetrics().widthPixels;
        mScreenHeight = mIme.getResources().getDisplayMetrics().heightPixels;
        int spaceAbove = areaOnScreen[1] - mStatusBar - 2 * margin;
        // 30% smaller across the screen's long side: lower in portrait, narrower in landscape
        boolean landscape = "land".equals(orientation());
        int height = (int) (Math.min(mScreenHeight * 0.55f, 480 * d) * (landscape ? 1f : 0.7f));
        boolean above = spaceAbove >= 200 * d;
        if (above) height = Math.min(height, spaceAbove);
        int width = (int) (Math.min(anchorWidth - 16 * d, Math.max(anchorWidth * 0.75f, 300 * d))
                * (landscape ? 0.7f : 1f));
        mDefaultWidth = mWidth = width;
        mDefaultHeight = mHeight = height;
        // The smallest size leaves room for the bar's buttons and the count at its widest
        clear.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int max = mHistory.maxSize();
        int barWidth = 2 * rim + bar.getPaddingLeft() + bar.getPaddingRight()
                + clear.getMeasuredWidth() + (int) (48 * d)
                + mTitle.getPaddingLeft() + mTitle.getPaddingRight() + (int) Math.ceil(
                        mTitle.getPaint().measureText(mIme.getString(R.string.clipboard_count, max, max)));
        mMinWidth = Math.max((int) (MIN_WIDTH_DP * d), barWidth);
        mMinHeight = (int) (MIN_HEIGHT_DP * d);
        // the default size too has room for the whole title
        if (mDefaultWidth < mMinWidth) mDefaultWidth = mWidth = Math.min(mMinWidth, mScreenWidth);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mIme);
        String key = PREF_POSITION + orientation();
        if (sp.contains(key + "_w")) {
            // the size the user left it at, if it still fits this screen
            mWidth = sp.getInt(key + "_w", width);
            mHeight = sp.getInt(key + "_h", height);
            clampSize();
        }
        mPopup = new PopupWindow(content, mWidth, mHeight, false);
        mPopup.setBackgroundDrawable(roundRect(style.surface, radius));  // outline for the shadow
        mPopup.setElevation(8 * d);
        mPopup.setOutsideTouchable(false);
        mPopup.setTouchable(true);

        if (sp.contains(key + "_x")) {
            mX = mAreaX + sp.getInt(key + "_x", 0);
            mY = mAreaY + sp.getInt(key + "_y", 0);
        } else {
            mX = mAreaX + inputArea.getWidth() - mWidth - margin;
            if (above) {
                mY = mAreaY - mHeight - margin;
            } else {
                int[] loc = new int[2];
                anchor.getLocationInWindow(loc);
                mY = loc[1] + anchor.getHeight() - anchor.getPaddingBottom() - mHeight - margin;
            }
        }
        clampToScreen();
        bar.setOnTouchListener(new View.OnTouchListener() {
            private float mStartX, mStartY;
            private int mFromX, mFromY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mStartX = e.getRawX();
                    mStartY = e.getRawY();
                    mFromX = mX;
                    mFromY = mY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mPopup == null) return false;
                    mX = mFromX + (int) (e.getRawX() - mStartX);
                    mY = mFromY + (int) (e.getRawY() - mStartY);
                    clampToScreen();
                    mPopup.update(mX, mY, -1, -1);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePosition();
                    return true;
                }
                return false;
            }
        });
        mPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, mX, mY);

        mHistory.setListener(this);
        mHistory.applySizeSetting();
        // A copy made while another keyboard was active isn't in the history yet
        mHistory.rememberPrimaryClip(
                (ClipboardManager) mIme.getSystemService(Context.CLIPBOARD_SERVICE),
                false, mIme.isIncognito());
        refresh();
    }

    /** Keeps the whole window on the screen, below the status bar. */
    private void clampToScreen() {
        // window coordinates: screen y = mWindowTopOnScreen + y; the keyboard window is full width
        int minY = mStatusBar - mWindowTopOnScreen;
        int maxY = mScreenHeight - mWindowTopOnScreen - mHeight;
        mY = Math.max(minY, Math.min(mY, maxY));
        mX = Math.max(0, Math.min(mX, mScreenWidth - mWidth));
    }

    /** Keeps a remembered size between the smallest usable one and the screen. */
    private void clampSize() {
        int maxHeight = mScreenHeight - mStatusBar;
        mWidth = Math.max(Math.min(mMinWidth, mScreenWidth), Math.min(mWidth, mScreenWidth));
        mHeight = Math.max(Math.min(mMinHeight, maxHeight), Math.min(mHeight, maxHeight));
    }

    /**
     * Resizing from a bottom corner: the bottom edge and that corner's side edge move, the
     * opposite edges stay where they are.
     */
    private void resize(boolean left, int fromX, int fromWidth, int fromHeight, int dx, int dy) {
        int minWidth = Math.min(mMinWidth, mScreenWidth);
        if (left) {
            int right = fromX + fromWidth;
            mX = Math.max(0, Math.min(fromX + dx, right - minWidth));
            mWidth = right - mX;
        } else {
            mWidth = Math.max(minWidth, Math.min(fromWidth + dx, mScreenWidth - mX));
        }
        // window coordinates, as in clampToScreen
        int maxHeight = mScreenHeight - mWindowTopOnScreen - mY;
        mHeight = Math.max(Math.min(mMinHeight, maxHeight), Math.min(fromHeight + dy, maxHeight));
        clampToScreen();
        mPopup.update(mX, mY, mWidth, mHeight);
    }

    /** Remembers the size, and the place too: the left grip moves the window's left edge. */
    private void saveSize() {
        String key = PREF_POSITION + orientation();
        PreferenceManager.getDefaultSharedPreferences(mIme).edit()
                .putInt(key + "_w", mWidth)
                .putInt(key + "_h", mHeight)
                .apply();
        savePosition();
    }

    /** A double tap on a grip: back to the default size, which is no longer remembered. */
    private void resetSize(boolean left) {
        if (mPopup == null) return;
        mIme.buttonFeedback(0);
        int right = mX + mWidth;
        mWidth = mDefaultWidth;
        mHeight = mDefaultHeight;
        if (left) mX = right - mWidth;
        clampToScreen();
        mPopup.update(mX, mY, mWidth, mHeight);
        String key = PREF_POSITION + orientation();
        PreferenceManager.getDefaultSharedPreferences(mIme).edit()
                .remove(key + "_w")
                .remove(key + "_h")
                .apply();
        if (left) savePosition();
    }

    private void savePosition() {
        String key = PREF_POSITION + orientation();
        PreferenceManager.getDefaultSharedPreferences(mIme).edit()
                .putInt(key + "_x", mX - mAreaX)
                .putInt(key + "_y", mY - mAreaY)
                .apply();
    }

    private String orientation() {
        return mIme.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? "land" : "port";
    }

    private int statusBarHeight(float d) {
        int id = mIme.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? mIme.getResources().getDimensionPixelSize(id) : (int) (24 * d);
    }

    @Override
    public void onClipboardHistoryChanged() {
        refresh();
    }

    private void refresh() {
        if (mList == null) return;
        mList.removeAllViews();
        mList.removeCallbacks(mAgeTick);
        mAges.clear();
        showCount();
        float d = mIme.getResources().getDisplayMetrics().density;
        List<ClipboardHistory.Entry> entries = mHistory.entries();
        if (entries.isEmpty()) {
            TextView empty = new TextView(mCtx);
            empty.setText(ClipboardHistory.isEnabled(mIme)
                    ? R.string.clipboard_empty : R.string.clipboard_off);
            empty.setTextColor(mBarTextColor);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            int pad = (int) (24 * d);
            empty.setPadding(pad, pad, pad, pad);
            mList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            ClipboardHistory.Entry entry = entries.get(i);
            // the entry as a key: the tile holds it and, when held, its pin/edit/delete strip
            LinearLayout tile = new LinearLayout(mCtx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(roundRect(mKeyColor, mKeyRadius));
            tile.setClipToOutline(true);
            LinearLayout row = new LinearLayout(mCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
            row.setBackgroundResource(attrResource(android.R.attr.selectableItemBackground));
            tile.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (entry.pinned) {
                ImageView pin = new ImageView(mCtx);
                pin.setImageResource(R.drawable.ic_push_pin);
                pin.setColorFilter(mPinnedColor, PorterDuff.Mode.SRC_IN);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d));
                lp.setMarginEnd((int) (12 * d));
                row.addView(pin, lp);
            }
            LinearLayout column = new LinearLayout(mCtx);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView text = new TextView(mCtx);
            text.setText(entry.text);
            text.setTextColor(entry.pinned ? mPinnedColor : mTextColor);
            text.setTextSize(15);
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(text);
            View meta = meta(entry, d);
            if (meta != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (4 * d);
                column.addView(meta, lp);
            }
            row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final long id = entry.id;
            final String value = entry.text;
            row.setOnClickListener(v -> {
                // On the tap, not the touch: a touch may start scrolling or a swipe
                mIme.buttonFeedback(0);
                if (mExpandedId != -1) {
                    mExpandedId = -1;
                    refresh();
                }
                mIme.pasteFromClipboardHistory(value);
                dismiss();
            });
            row.setOnLongClickListener(v -> {
                mIme.buttonFeedback(0);
                mExpandedId = mExpandedId == id ? -1 : id;
                refresh();
                return true;
            });
            row.setOnTouchListener(new SwipeToDelete(row, tile, id, d));
            if (id == mExpandedId) {
                tile.addView(actions(entry, d), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tileParams.bottomMargin = (int) (5 * d);
            mList.addView(tile, tileParams);
        }
        if (!mAges.isEmpty()) mList.postDelayed(mAgeTick, AGE_TICK_MS);
    }

    /**
     * "12/20" in the bar: the unpinned entries and the size setting, which doesn't count
     * pinned ones either.
     */
    private void showCount() {
        if (mTitle == null) return;
        int count = mHistory.unpinnedCount();
        int max = mHistory.maxSize();
        mTitle.setText(mIme.getString(R.string.clipboard_count, count, max));
        mTitle.setContentDescription(mIme.getString(R.string.clipboard_title_count, count, max));
    }

    /** The small line under an entry: what kind of text it is, and when it was copied. */
    private View meta(ClipboardHistory.Entry entry, float d) {
        int icon = kindIcon(entry.text);
        if (icon == 0 && entry.time <= 0) return null;
        LinearLayout line = new LinearLayout(mCtx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != 0) {
            ImageView image = new ImageView(mCtx);
            image.setImageResource(icon);
            image.setColorFilter(mSecondaryColor, PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d));
            lp.setMarginEnd((int) (4 * d));
            line.addView(image, lp);
        }
        if (entry.time > 0) {  // entries kept before times were saved have none
            TextView age = new TextView(mCtx);
            age.setTag(entry.time);
            age.setText(ageText(entry.time));
            age.setTextColor(mSecondaryColor);
            age.setTextSize(12);
            age.setSingleLine(true);
            line.addView(age);
            mAges.add(age);
        }
        return line;
    }

    /** "just now", "5 min. ago", "2 hr. ago", "yesterday", then a date. */
    private CharSequence ageText(long time) {
        long now = System.currentTimeMillis();
        if (now - time < DateUtils.MINUTE_IN_MILLIS) return mIme.getString(R.string.clipboard_just_now);
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    private void updateAges() {
        if (mList == null) return;
        for (TextView age : mAges) age.setText(ageText((Long) age.getTag()));
        mList.postDelayed(mAgeTick, AGE_TICK_MS);
    }

    /**
     * An icon for a link, an e-mail address, a phone number or a one-time code (the whole
     * entry is one), 0 for other text.
     */
    private static int kindIcon(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 2000) return 0;
        // 4 to 8 digits alone: a code more likely than a phone number
        if (CODE.matcher(t).matches()) return R.drawable.ic_vpn_key;
        if (Patterns.EMAIL_ADDRESS.matcher(t).matches()) return R.drawable.ic_mail;
        if (Patterns.WEB_URL.matcher(t).matches()) {
            // "example.com" or "file.txt" match too: a link has a scheme or www.
            String lower = t.toLowerCase(Locale.ROOT);
            return lower.startsWith("www.") || SCHEME.matcher(lower).lookingAt()
                    ? R.drawable.ic_link : 0;
        }
        // not a date, an IP address or a version number
        if (t.indexOf('.') < 0 && !DATE.matcher(t).matches() && Patterns.PHONE.matcher(t).matches()) {
            int digits = 0;
            for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) digits++;
            if (digits >= 7 && digits <= 15) return R.drawable.ic_call;
        }
        return 0;
    }

    /** Pin / Edit / Delete under a held entry, inside this window (no focusable menu). */
    private View actions(ClipboardHistory.Entry entry, float d) {
        final long id = entry.id;
        LinearLayout strip = new LinearLayout(mCtx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding((int) (8 * d), 0, (int) (8 * d), (int) (6 * d));
        strip.addView(action(R.drawable.ic_push_pin,
                entry.pinned ? R.string.clipboard_unpin : R.string.clipboard_pin, d, v -> {
                    mExpandedId = -1;
                    mHistory.togglePin(id);
                }), new LinearLayout.LayoutParams(0, (int) (40 * d), 1f));
        strip.addView(action(R.drawable.ic_edit, R.string.clipboard_edit, d, v -> {
            dismiss();
            mIme.editClipboardEntry(id);
        }), new LinearLayout.LayoutParams(0, (int) (40 * d), 1f));
        strip.addView(action(R.drawable.ic_delete, R.string.clipboard_delete, d, v -> delete(id)),
                new LinearLayout.LayoutParams(0, (int) (40 * d), 1f));
        return strip;
    }

    private View action(int icon, int label, float d, View.OnClickListener onClick) {
        LinearLayout button = new LinearLayout(mCtx);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(attrResource(android.R.attr.selectableItemBackground));
        ImageView image = new ImageView(mCtx);
        image.setImageResource(icon);
        image.setColorFilter(mTextColor, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d));
        lp.setMarginEnd((int) (6 * d));
        button.addView(image, lp);
        TextView text = new TextView(mCtx);
        text.setText(label);
        text.setTextColor(mTextColor);
        text.setTextSize(13);
        button.addView(text);
        button.setOnClickListener(onClick);
        button.setOnTouchListener(mIme.feedbackOnTouch(0));
        return button;
    }

    private void delete(long id) {
        if (mExpandedId == id) mExpandedId = -1;
        ClipboardHistory.Entry removed = mHistory.remove(id);
        if (removed == null) return;
        offerUndo(mIme.getString(R.string.clipboard_deleted), () -> mHistory.restore(removed));
    }

    /** "Clear": the unpinned entries go, and can be put back for a few seconds. */
    private void clear() {
        mExpandedId = -1;
        List<ClipboardHistory.Entry> removed = mHistory.clearUnpinned();
        if (removed.isEmpty()) return;
        offerUndo(mIme.getString(R.string.clipboard_cleared, removed.size()),
                () -> mHistory.restoreAll(removed));
    }

    /** Shows the undo bar; a newer delete or clear takes the place of the one before. */
    private void offerUndo(CharSequence text, Runnable undo) {
        if (mUndoBar == null) return;
        mUndo = undo;
        mUndoText.setText(text);
        mUndoBar.setVisibility(View.VISIBLE);
        mUndoBar.removeCallbacks(mHideUndo);
        mUndoBar.postDelayed(mHideUndo, UNDO_MS);
    }

    private void hideUndo() {
        mUndo = null;
        if (mUndoBar == null) return;
        mUndoBar.removeCallbacks(mHideUndo);
        mUndoBar.setVisibility(View.GONE);
    }

    /**
     * Swiping a row sideways deletes it; vertical moves still scroll the list, and tap and
     * hold still work, until the finger has clearly moved sideways.
     */
    private final class SwipeToDelete implements View.OnTouchListener {
        private final View mRow;   // takes the touches
        private final View mTile;  // moves aside
        private final long mId;
        private final int mSlop;
        private final float mFling;
        private float mDownX, mDownY;
        private boolean mSwiping;
        private VelocityTracker mVelocity;

        SwipeToDelete(View row, View tile, long id, float d) {
            mRow = row;
            mTile = tile;
            mId = id;
            mSlop = ViewConfiguration.get(mCtx).getScaledTouchSlop();
            mFling = 800 * d;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getRawX();
                mDownY = e.getRawY();
                mSwiping = false;
                if (mVelocity != null) mVelocity.recycle();
                mVelocity = VelocityTracker.obtain();
                mVelocity.addMovement(e);
                return false;  // the row still handles tap and hold
            case MotionEvent.ACTION_MOVE: {
                if (mVelocity != null) mVelocity.addMovement(e);
                float dx = e.getRawX() - mDownX;
                float dy = e.getRawY() - mDownY;
                if (!mSwiping && Math.abs(dx) > mSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    mSwiping = true;
                    mRow.getParent().requestDisallowInterceptTouchEvent(true);
                    // drop the row's pending tap and long press
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    mRow.onTouchEvent(cancel);
                    cancel.recycle();
                }
                if (!mSwiping) return false;
                mTile.setTranslationX(dx);
                mTile.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / Math.max(1, mTile.getWidth())));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    vx = mVelocity.getXVelocity();
                    mVelocity.recycle();
                    mVelocity = null;
                }
                if (!mSwiping) return false;
                mSwiping = false;
                float dx = mTile.getTranslationX();
                boolean away = e.getActionMasked() == MotionEvent.ACTION_UP
                        && (Math.abs(dx) > mTile.getWidth() * 0.35f
                            || (Math.abs(vx) > mFling && Math.signum(vx) == Math.signum(dx)));
                if (away) {
                    mTile.animate().translationX(Math.signum(dx) * mTile.getWidth()).alpha(0f)
                            .setDuration(150).withEndAction(() -> delete(mId)).start();
                } else {
                    mTile.animate().translationX(0f).alpha(1f).setDuration(150).start();
                }
                return true;
            }
            }
            return false;
        }
    }

    /**
     * A grip in a bottom corner, not drawn: drag the corner to resize the window, double tap
     * it for the default size. Only the corner itself is the
     * grip; a touch further in goes to the list under it.
     */
    private final class ResizeGrip extends View {
        private final boolean mLeft;
        private final float mReach;
        private final int mSlop, mDoubleTap;
        private float mStartX, mStartY;
        private int mFromX, mFromWidth, mFromHeight;
        private boolean mMoved, mSecondTap;
        private long mLastTap;

        ResizeGrip(boolean left, float d) {
            super(mCtx);
            mLeft = left;
            mReach = 28 * d;  // just the corner: an entry's text starts 16dp in
            mSlop = ViewConfiguration.get(mCtx).getScaledTouchSlop();
            mDoubleTap = ViewConfiguration.getDoubleTapTimeout();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float fromSide = mLeft ? e.getX() : getWidth() - e.getX();
                if (fromSide + getHeight() - e.getY() > mReach) return false;
                mStartX = e.getRawX();
                mStartY = e.getRawY();
                mFromX = mX;
                mFromWidth = mWidth;
                mFromHeight = mHeight;
                mMoved = false;
                mSecondTap = e.getEventTime() - mLastTap <= mDoubleTap;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mPopup == null) return false;
                float dx = e.getRawX() - mStartX;
                float dy = e.getRawY() - mStartY;
                if (!mMoved && Math.abs(dx) <= mSlop && Math.abs(dy) <= mSlop) return true;
                mMoved = true;
                resize(mLeft, mFromX, mFromWidth, mFromHeight, (int) dx, (int) dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (mMoved) {
                    mLastTap = 0;
                    if (mPopup != null) saveSize();
                } else if (mSecondTap) {
                    mLastTap = 0;
                    resetSize(mLeft);
                } else {
                    mLastTap = e.getEventTime();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mMoved && mPopup != null) saveSize();
                mLastTap = 0;
                return true;
            }
            return false;
        }
    }

    private int attrResource(int attr) {
        TypedValue value = new TypedValue();
        mCtx.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }
}
