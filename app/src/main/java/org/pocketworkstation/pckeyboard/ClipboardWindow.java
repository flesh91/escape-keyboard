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
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Clipboard history in a floating window over the keyboard. It is a popup of the keyboard's
 * own window, so it needs no "display over other apps" permission, and it goes away with the
 * keyboard. Drag it by the title bar (the place is remembered); tap an entry to paste it,
 * swipe it aside to delete it, hold it for pin/edit/delete. Nothing in it takes focus: a
 * focused popup would take it from the text field and close the keyboard.
 */
final class ClipboardWindow implements ClipboardHistory.Listener {
    private static final long UNDO_MS = 5000;

    private final LatinIME mIme;
    private final ClipboardHistory mHistory;
    private PopupWindow mPopup;
    private LinearLayout mList;
    private Context mCtx;
    private int mTextColor, mSecondaryColor, mPinnedColor, mDividerColor;
    private int mX, mY;
    // Where the keyboard area is: the window position is kept relative to its top left
    private int mAreaX, mAreaY, mWindowTopOnScreen, mStatusBar, mScreenWidth, mScreenHeight;
    private int mWidth, mHeight;
    private long mExpandedId = -1;  // entry showing its pin/edit/delete strip
    private LinearLayout mUndoBar;
    private ClipboardHistory.Entry mLastDeleted;
    private final Runnable mHideUndo = this::hideUndo;

    private static final String PREF_POSITION = "clipboard_window_";

    ClipboardWindow(LatinIME ime) {
        mIme = ime;
        mHistory = ClipboardHistory.get(ime);
    }

    boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    void dismiss() {
        if (mUndoBar != null) mUndoBar.removeCallbacks(mHideUndo);
        mUndoBar = null;
        mLastDeleted = null;
        mExpandedId = -1;
        if (mPopup != null) mPopup.dismiss();
        mPopup = null;
        mList = null;
        mHistory.setListener(null);
    }

    /**
     * Opens where the user last dragged it (per orientation), at first above the keyboard area
     * on the right; over the keyboard only when there is no room above it.
     */
    void show(View anchor, View inputArea, boolean light) {
        dismiss();
        mCtx = new ContextThemeWrapper(mIme,
                light ? R.style.HK_EmojiPanel_Light : R.style.HK_EmojiPanel_Dark);
        float d = mIme.getResources().getDisplayMetrics().density;
        int surface = light ? 0xFFFFFFFF : 0xFF2D2F31;
        mTextColor = light ? 0xFF1F1F1F : 0xFFE3E3E3;
        mSecondaryColor = light ? 0xFF5F6368 : 0xFF9AA0A6;
        mPinnedColor = light ? 0xFFE8710A : 0xFFFFB74D;
        mDividerColor = light ? 0x1F000000 : 0x33FFFFFF;

        LinearLayout root = new LinearLayout(mCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundRect(surface, 16 * d));
        root.setClipToOutline(true);

        LinearLayout bar = new LinearLayout(mCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (16 * d), 0, (int) (4 * d), 0);
        TextView title = new TextView(mCtx);
        title.setText(R.string.clipboard_title);
        title.setTextColor(mTextColor);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(mCtx);
        clear.setText(R.string.clipboard_clear);
        clear.setTextColor(mTextColor);
        clear.setTextSize(14);
        clear.setTypeface(Typeface.DEFAULT_BOLD);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        clear.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        clear.setOnClickListener(v -> mHistory.clearUnpinned());
        clear.setOnTouchListener(mIme.feedbackOnTouch(0));
        bar.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView close = new ImageView(mCtx);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(mTextColor, PorterDuff.Mode.SRC_IN);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        close.setContentDescription(mIme.getString(R.string.clipboard_close));
        close.setOnClickListener(v -> dismiss());
        close.setOnTouchListener(mIme.feedbackOnTouch(0));
        bar.addView(close, new LinearLayout.LayoutParams((int) (48 * d),
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d)));
        root.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) d)));

        ScrollView scroll = new ScrollView(mCtx);
        mList = new LinearLayout(mCtx);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // "Entry deleted · Undo", for a few seconds after a delete
        mUndoBar = new LinearLayout(mCtx);
        mUndoBar.setOrientation(LinearLayout.HORIZONTAL);
        mUndoBar.setGravity(Gravity.CENTER_VERTICAL);
        mUndoBar.setPadding((int) (16 * d), 0, (int) (4 * d), 0);
        mUndoBar.setBackgroundColor(mDividerColor);
        TextView deleted = new TextView(mCtx);
        deleted.setText(R.string.clipboard_deleted);
        deleted.setTextColor(mSecondaryColor);
        deleted.setTextSize(14);
        mUndoBar.addView(deleted, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
            ClipboardHistory.Entry e = mLastDeleted;
            hideUndo();
            mHistory.restore(e);
        });
        mUndoBar.addView(undo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mUndoBar.setVisibility(View.GONE);
        root.addView(mUndoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d)));

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
        int height = (int) Math.min(mScreenHeight * 0.55f, 480 * d);
        boolean above = spaceAbove >= 200 * d;
        if (above) height = Math.min(height, spaceAbove);
        int width = (int) Math.min(anchorWidth - 16 * d, Math.max(anchorWidth * 0.75f, 300 * d));
        mWidth = width;
        mHeight = height;
        mPopup = new PopupWindow(root, width, height, false);
        mPopup.setBackgroundDrawable(roundRect(surface, 16 * d));  // outline for the shadow
        mPopup.setElevation(8 * d);
        mPopup.setOutsideTouchable(false);
        mPopup.setTouchable(true);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mIme);
        String key = PREF_POSITION + orientation();
        if (sp.contains(key + "_x")) {
            mX = mAreaX + sp.getInt(key + "_x", 0);
            mY = mAreaY + sp.getInt(key + "_y", 0);
        } else {
            mX = mAreaX + inputArea.getWidth() - width - margin;
            if (above) {
                mY = mAreaY - height - margin;
            } else {
                int[] loc = new int[2];
                anchor.getLocationInWindow(loc);
                mY = loc[1] + anchor.getHeight() - anchor.getPaddingBottom() - height - margin;
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
                (ClipboardManager) mIme.getSystemService(Context.CLIPBOARD_SERVICE));
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
        float d = mIme.getResources().getDisplayMetrics().density;
        List<ClipboardHistory.Entry> entries = mHistory.entries();
        if (entries.isEmpty()) {
            TextView empty = new TextView(mCtx);
            empty.setText(ClipboardHistory.isEnabled(mIme)
                    ? R.string.clipboard_empty : R.string.clipboard_off);
            empty.setTextColor(mSecondaryColor);
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
            if (i > 0) {
                mList.addView(divider(), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) d)));
            }
            LinearLayout row = new LinearLayout(mCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
            row.setBackgroundResource(attrResource(android.R.attr.selectableItemBackground));
            if (entry.pinned) {
                ImageView pin = new ImageView(mCtx);
                pin.setImageResource(R.drawable.ic_push_pin);
                pin.setColorFilter(mPinnedColor, PorterDuff.Mode.SRC_IN);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d));
                lp.setMarginEnd((int) (12 * d));
                row.addView(pin, lp);
            }
            TextView text = new TextView(mCtx);
            text.setText(entry.text);
            text.setTextColor(entry.pinned ? mPinnedColor : mTextColor);
            text.setTextSize(15);
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
            row.setOnTouchListener(new SwipeToDelete(row, id, d));
            mList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (id == mExpandedId) {
                mList.addView(actions(entry, d), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
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
        if (removed == null || mUndoBar == null) return;
        mLastDeleted = removed;
        mUndoBar.setVisibility(View.VISIBLE);
        mUndoBar.removeCallbacks(mHideUndo);
        mUndoBar.postDelayed(mHideUndo, UNDO_MS);
    }

    private void hideUndo() {
        mLastDeleted = null;
        if (mUndoBar == null) return;
        mUndoBar.removeCallbacks(mHideUndo);
        mUndoBar.setVisibility(View.GONE);
    }

    /**
     * Swiping a row sideways deletes it; vertical moves still scroll the list, and tap and
     * hold still work, until the finger has clearly moved sideways.
     */
    private final class SwipeToDelete implements View.OnTouchListener {
        private final View mRow;
        private final long mId;
        private final int mSlop;
        private final float mFling;
        private float mDownX, mDownY;
        private boolean mSwiping;
        private VelocityTracker mVelocity;

        SwipeToDelete(View row, long id, float d) {
            mRow = row;
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
                mRow.setTranslationX(dx);
                mRow.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / Math.max(1, mRow.getWidth())));
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
                float dx = mRow.getTranslationX();
                boolean away = e.getActionMasked() == MotionEvent.ACTION_UP
                        && (Math.abs(dx) > mRow.getWidth() * 0.35f
                            || (Math.abs(vx) > mFling && Math.signum(vx) == Math.signum(dx)));
                if (away) {
                    mRow.animate().translationX(Math.signum(dx) * mRow.getWidth()).alpha(0f)
                            .setDuration(150).withEndAction(() -> delete(mId)).start();
                } else {
                    mRow.animate().translationX(0f).alpha(1f).setDuration(150).start();
                }
                return true;
            }
            }
            return false;
        }
    }

    private View divider() {
        View line = new View(mCtx);
        line.setBackgroundColor(mDividerColor);
        return line;
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
