/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.util.List;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import org.pocketworkstation.pckeyboard.Keyboard.Key;

import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.widget.PopupWindow;
import android.widget.TextView;

public class LatinKeyboardView extends LatinKeyboardBaseView {
    static final String TAG = "HK/LatinKeyboardView";

	// The keycode list needs to stay in sync with the
	// res/values/keycodes.xml file.

	// FIXME: The following keycodes should really be renumbered
	// since they conflict with existing KeyEvent keycodes.
    static final int KEYCODE_OPTIONS = -100;
    static final int KEYCODE_OPTIONS_LONGPRESS = -101;
    static final int KEYCODE_VOICE = -102;
    static final int KEYCODE_EMOJI = -144;
    static final int KEYCODE_CLIPBOARD = -145;
    // Sent by the view itself, not by keys
    static final int KEYCODE_CURSOR_LEFT = -146;
    static final int KEYCODE_CURSOR_RIGHT = -147;
    static final int KEYCODE_ONE_HAND_SWAP = -148;
    static final int KEYCODE_ONE_HAND_EXIT = -149;
    /** Long press on Enter: opens the clipboard history window */
    static final int KEYCODE_CLIPBOARD_LONGPRESS = -150;
    /** Long press on space while its swipe moves the cursor: the next language */
    static final int KEYCODE_SPACE_LONGPRESS = -151;
    static final int KEYCODE_F1 = -103;
    static final int KEYCODE_NEXT_LANGUAGE = -104;
    static final int KEYCODE_PREV_LANGUAGE = -105;
    static final int KEYCODE_COMPOSE = -10024;
    
	// The following keycodes match (negative) KeyEvent keycodes.
    // Would be better to use the real KeyEvent values, but many
    // don't exist prior to the Honeycomb API (level 11).
    static final int KEYCODE_DPAD_UP = -19;
    static final int KEYCODE_DPAD_DOWN = -20;
    static final int KEYCODE_DPAD_LEFT = -21;
    static final int KEYCODE_DPAD_RIGHT = -22;
    static final int KEYCODE_DPAD_CENTER = -23;
    static final int KEYCODE_ALT_LEFT = -57;
    static final int KEYCODE_PAGE_UP = -92;
    static final int KEYCODE_PAGE_DOWN = -93;
    static final int KEYCODE_ESCAPE = -111;
    static final int KEYCODE_FORWARD_DEL = -112;
    static final int KEYCODE_CTRL_LEFT = -113;
    static final int KEYCODE_CAPS_LOCK = -115;
    static final int KEYCODE_SCROLL_LOCK = -116;
    static final int KEYCODE_META_LEFT = -117;
    static final int KEYCODE_FN = -119;
    static final int KEYCODE_SYSRQ = -120;
    static final int KEYCODE_BREAK = -121;
    static final int KEYCODE_HOME = -122;
    static final int KEYCODE_END = -123;
    static final int KEYCODE_INSERT = -124;
    static final int KEYCODE_FKEY_F1 = -131;
    static final int KEYCODE_FKEY_F2 = -132;
    static final int KEYCODE_FKEY_F3 = -133;
    static final int KEYCODE_FKEY_F4 = -134;
    static final int KEYCODE_FKEY_F5 = -135;
    static final int KEYCODE_FKEY_F6 = -136;
    static final int KEYCODE_FKEY_F7 = -137;
    static final int KEYCODE_FKEY_F8 = -138;
    static final int KEYCODE_FKEY_F9 = -139;
    static final int KEYCODE_FKEY_F10 = -140;
    static final int KEYCODE_FKEY_F11 = -141;
    static final int KEYCODE_FKEY_F12 = -142;
    static final int KEYCODE_NUM_LOCK = -143;

    private Keyboard mPhoneKeyboard;

    /** Whether the extension of this keyboard is visible */
    private boolean mExtensionVisible;
    /** The view that is shown as an extension of this keyboard view */
    private LatinKeyboardView mExtension;
    /** The popup window that contains the extension of this keyboard */
    private PopupWindow mExtensionPopup;
    /** Whether this view is an extension of another keyboard */
    private boolean mIsExtensionType;
    private boolean mFirstEvent;

    /** Whether we've started dropping move events because we found a big jump */
    private boolean mDroppingEvents;
    /**
     * Whether multi-touch disambiguation needs to be disabled for any reason. There are 2 reasons
     * for this to happen - (1) if a real multi-touch event has occured and (2) we've opened an 
     * extension keyboard.
     */
    private boolean mDisableDisambiguation;
    /** The distance threshold at which we start treating the touch session as a multi-touch */
    private int mJumpThresholdSquare = Integer.MAX_VALUE;
    /** The y coordinate of the last row */
    private int mLastRowY;
    private int mExtensionLayoutResId = 0;
    private LatinKeyboard mExtensionKeyboard;

    public LatinKeyboardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LatinKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // TODO(klausw): migrate attribute styles to LatinKeyboardView?
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.LatinKeyboardBaseView, defStyle, R.style.LatinKeyboardBaseView);
        LayoutInflater inflate =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        int previewLayout = 0;
        int n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);


            if(attr== R.styleable.LatinKeyboardBaseView_keyPreviewLayout){
                previewLayout = a.getResourceId(attr, 0);
                if (previewLayout == R.layout.null_layout) previewLayout = 0;}
            else if(attr== R.styleable.LatinKeyboardBaseView_keyPreviewOffset)
                mPreviewOffset = a.getDimensionPixelOffset(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyPreviewHeight)
                mPreviewHeight = a.getDimensionPixelSize(attr, 80);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyPreviewBackground)
                mPreviewBackground = a.getResourceId(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyPreviewTextColor)
                mPreviewTextColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_popupLayout){
                mPopupLayout = a.getResourceId(attr, 0);
                if (mPopupLayout == R.layout.null_layout) mPopupLayout = 0;}
            
        }

        final Resources res = getResources();

        // If true, popups are forced to remain inside the keyboard area. If false,
        // they can extend above it. Enable clipping just for Android P since drawing
        // outside the keyboard area doesn't work on that version.
        boolean clippingEnabled = (Build.VERSION.SDK_INT >= 28 /* Build.VERSION_CODES.P */);

        if (previewLayout != 0) {
            mPreviewPopup = new PopupWindow(context);
            if (!isInEditMode())
                Log.i(TAG, "new mPreviewPopup " + mPreviewPopup + " from " + this);
            mPreviewText = (TextView) inflate.inflate(previewLayout, null);
            mPreviewTextSizeLarge = (int) res.getDimension(R.dimen.key_preview_text_size_large);
            if (mPreviewTextColor != 0) mPreviewText.setTextColor(mPreviewTextColor);
            mPreviewPopup.setContentView(mPreviewText);
            mPreviewPopup.setBackgroundDrawable(null);
            mPreviewPopup.setTouchable(false);
            mPreviewPopup.setAnimationStyle(R.style.KeyPreviewAnimation);
            mPreviewPopup.setClippingEnabled(clippingEnabled);
        } else {
            mShowPreview = false;
        }

        if (mPopupLayout != 0) {
            mMiniKeyboardParent = this;
            mMiniKeyboardPopup = new PopupWindow(context);
            if (!isInEditMode())
                Log.i(TAG, "new mMiniKeyboardPopup " + mMiniKeyboardPopup + " from " + this);
            mMiniKeyboardPopup.setBackgroundDrawable(null);
            mMiniKeyboardPopup.setAnimationStyle(R.style.MiniKeyboardAnimation);
            mMiniKeyboardPopup.setClippingEnabled(clippingEnabled);
            mMiniKeyboardVisible = false;
        }
    }

    public void setPhoneKeyboard(Keyboard phoneKeyboard) {
        mPhoneKeyboard = phoneKeyboard;
    }

    public void setExtensionLayoutResId (int id) {
        mExtensionLayoutResId = id;
    }
    
    @Override
    public void setPreviewEnabled(boolean previewEnabled) {
        if (getKeyboard() == mPhoneKeyboard) {
            // Phone keyboard never shows popup preview (except language switch).
            super.setPreviewEnabled(false);
        } else {
            super.setPreviewEnabled(previewEnabled);
        }
    }

    @Override
    public void setKeyboard(Keyboard newKeyboard) {
        final Keyboard oldKeyboard = getKeyboard();
        if (oldKeyboard instanceof LatinKeyboard) {
            // Reset old keyboard state before switching to new keyboard.
            ((LatinKeyboard)oldKeyboard).keyReleased();
        }
        super.setKeyboard(newKeyboard);
        // One-seventh of the keyboard width seems like a reasonable threshold
        mJumpThresholdSquare = newKeyboard.getMinWidth() / 7;
        mJumpThresholdSquare *= mJumpThresholdSquare;
        // Get Y coordinate of the last row based on the row count, assuming equal height
        int numRows = newKeyboard.mRowCount;
        mLastRowY = (newKeyboard.getHeight() * (numRows - 1)) / numRows;
        mExtensionKeyboard = ((LatinKeyboard) newKeyboard).getExtension();
        if (mExtensionKeyboard != null && mExtension != null) mExtension.setKeyboard(mExtensionKeyboard);
        setKeyboardLocal(newKeyboard);
    }

    @Override
    /*package*/ boolean enableSlideKeyHack() {
        return true;
    }

    @Override
    protected boolean onLongPress(Key key) {
        PointerTracker.clearSlideKeys();

        int primaryCode = key.codes[0];
        if (primaryCode == KEYCODE_OPTIONS) {
            return invokeOnKey(KEYCODE_OPTIONS_LONGPRESS);
        } else if (primaryCode == KEYCODE_DPAD_CENTER) {
            return invokeOnKey(KEYCODE_COMPOSE);
        } else if (primaryCode == KEYCODE_DPAD_LEFT && !key.repeatable) {
            // Compact layouts have no Home/End keys: long-press the arrows instead
            // (only where the arrow doesn't auto-repeat, see kbd_compact.xml)
            return invokeOnKey(KEYCODE_HOME);
        } else if (primaryCode == KEYCODE_DPAD_RIGHT && !key.repeatable) {
            return invokeOnKey(KEYCODE_END);
        } else if (primaryCode == LatinIME.ASCII_ENTER) {
            return invokeOnKey(KEYCODE_CLIPBOARD_LONGPRESS);
        } else if (primaryCode == LatinIME.ASCII_SPACE && LatinIME.sKeyboardSettings.spaceSwipeCursor
                && getKeyboard() instanceof LatinKeyboard
                && ((LatinKeyboard) getKeyboard()).getLocaleCount() > 1) {
            // A swipe along the space bar moves the cursor, so a long press switches the language
            return invokeOnKey(KEYCODE_SPACE_LONGPRESS);
        } else if (primaryCode == '0' && getKeyboard() == mPhoneKeyboard) {
            // Long pressing on 0 in phone number keypad gives you a '+'.
            return invokeOnKey('+');
        } else {
            return super.onLongPress(key);
        }
    }

    private boolean invokeOnKey(int primaryCode) {
        getOnKeyboardActionListener().onKey(primaryCode, null,
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE,
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE);
        return true;
    }

    /**
     * This function checks to see if we need to handle any sudden jumps in the pointer location
     * that could be due to a multi-touch being treated as a move by the firmware or hardware.
     * Once a sudden jump is detected, all subsequent move events are discarded
     * until an UP is received.<P>
     * When a sudden jump is detected, an UP event is simulated at the last position and when
     * the sudden moves subside, a DOWN event is simulated for the second key.
     * @param me the motion event
     * @return true if the event was consumed, so that it doesn't continue to be handled by
     * KeyboardView.
     */
    private boolean handleSuddenJump(MotionEvent me) {
        final int action = me.getAction();
        final int x = (int) me.getX();
        final int y = (int) me.getY();
        boolean result = false;

        // Real multi-touch event? Stop looking for sudden jumps
        if (me.getPointerCount() > 1) {
            mDisableDisambiguation = true;
        }
        if (mDisableDisambiguation) {
            // If UP, reset the multi-touch flag
            if (action == MotionEvent.ACTION_UP) mDisableDisambiguation = false;
            return false;
        }

        switch (action) {
        case MotionEvent.ACTION_DOWN:
            // Reset the "session"
            mDroppingEvents = false;
            mDisableDisambiguation = false;
            break;
        case MotionEvent.ACTION_MOVE:
            // Is this a big jump?
            final int distanceSquare = (mLastX - x) * (mLastX - x) + (mLastY - y) * (mLastY - y);
            // Check the distance and also if the move is not entirely within the bottom row
            // If it's only in the bottom row, it might be an intentional slide gesture
            // for language switching
            if (distanceSquare > mJumpThresholdSquare
                    && (mLastY < mLastRowY || y < mLastRowY)) {
                // If we're not yet dropping events, start dropping and send an UP event
                if (!mDroppingEvents) {
                    mDroppingEvents = true;
                    // Send an up event
                    MotionEvent translated = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                            MotionEvent.ACTION_UP,
                            mLastX, mLastY, me.getMetaState());
                    super.onTouchEvent(translated);
                    translated.recycle();
                }
                result = true;
            } else if (mDroppingEvents) {
                // If moves are small and we're already dropping events, continue dropping
                result = true;
            }
            break;
        case MotionEvent.ACTION_UP:
            if (mDroppingEvents) {
                // Send a down event first, as we dropped a bunch of sudden jumps and assume that
                // the user is releasing the touch on the second key.
                MotionEvent translated = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                        MotionEvent.ACTION_DOWN,
                        x, y, me.getMetaState());
                super.onTouchEvent(translated);
                translated.recycle();
                mDroppingEvents = false;
                // Let the up event get processed as well, result = false
            }
            break;
        }
        // Track the previous coordinate
        mLastX = x;
        mLastY = y;
        return result;
    }

    // Swiping along the space bar moves the cursor (pref), one step per mCursorStep pixels
    private boolean mSpaceCursorArmed;
    private boolean mSpaceCursorActive;
    private float mSpaceCursorStartX;
    private float mSpaceCursorLastX;

    private boolean handleSpaceCursor(MotionEvent me) {
        if (!LatinIME.sKeyboardSettings.spaceSwipeCursor || mIsExtensionType) return false;
        final float d = getResources().getDisplayMetrics().density;
        switch (me.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
            mSpaceCursorActive = false;
            Key space = getKeyboard() instanceof LatinKeyboard
                    ? ((LatinKeyboard) getKeyboard()).getSpaceKey() : null;
            int x = (int) me.getX() - getPaddingLeft();
            int y = (int) me.getY() - getPaddingTop();
            mSpaceCursorArmed = space != null && x >= space.x && x < space.x + space.width
                    && y >= space.y && y < space.y + space.height;
            mSpaceCursorStartX = mSpaceCursorLastX = me.getX();
            return false; // the space key goes down as usual until the finger moves
        }
        case MotionEvent.ACTION_MOVE: {
            if (!mSpaceCursorArmed) return false;
            if (!mSpaceCursorActive) {
                if (me.getPointerCount() > 1
                        || Math.abs(me.getX() - mSpaceCursorStartX) < 16 * d) return false;
                // A swipe after all: the space key is let go without typing a space
                mSpaceCursorActive = true;
                MotionEvent cancel = MotionEvent.obtain(me);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
                mSpaceCursorLastX = me.getX();
            }
            float step = 10 * d;
            int steps = (int) ((me.getX() - mSpaceCursorLastX) / step);
            for (int i = 0; i < Math.abs(steps); i++) {
                getOnKeyboardActionListener().onKey(
                        steps < 0 ? KEYCODE_CURSOR_LEFT : KEYCODE_CURSOR_RIGHT, null, 0, 0);
            }
            mSpaceCursorLastX += steps * step;
            return true;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL: {
            boolean consumed = mSpaceCursorActive;
            mSpaceCursorArmed = mSpaceCursorActive = false;
            return consumed;
        }
        default:
            return mSpaceCursorActive;
        }
    }

    // One-handed mode: the keys leave an empty side (padding) holding two buttons,
    // move to the other side and back to full width
    private int mOneHandGap;
    private boolean mOneHandKeysRight;
    private int mOneHandButton = -1; // being pressed: 0 move, 1 full width
    private android.graphics.drawable.Drawable mOneHandSwapIcon, mOneHandExitIcon;
    private final Paint mOneHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    void setOneHandGap(int gap, boolean keysRight) {
        mOneHandGap = gap;
        mOneHandKeysRight = keysRight;
        invalidate();
    }

    private int oneHandGapLeft() {
        return mOneHandKeysRight ? getPaddingLeft() - mOneHandGap : getWidth() - getPaddingRight();
    }

    private int oneHandButtonAt(MotionEvent me) {
        int left = oneHandGapLeft();
        if (me.getX() < left || me.getX() >= left + mOneHandGap) return -1;
        int keysTop = getPaddingTop(), keysBottom = getHeight() - getPaddingBottom();
        if (me.getY() < keysTop || me.getY() >= keysBottom) return -1;
        return me.getY() < (keysTop + keysBottom) / 2f ? 0 : 1;
    }

    private boolean handleOneHandButtons(MotionEvent me) {
        if (mOneHandGap <= 0 || mIsExtensionType) return false;
        switch (me.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            mOneHandButton = oneHandButtonAt(me);
            if (mOneHandButton >= 0) invalidate();
            return mOneHandButton >= 0;
        case MotionEvent.ACTION_UP:
            if (mOneHandButton < 0) return false;
            int button = mOneHandButton;
            mOneHandButton = -1;
            invalidate();
            if (oneHandButtonAt(me) == button) {
                getOnKeyboardActionListener().onKey(
                        button == 0 ? KEYCODE_ONE_HAND_SWAP : KEYCODE_ONE_HAND_EXIT, null, 0, 0);
            }
            return true;
        case MotionEvent.ACTION_CANCEL:
            if (mOneHandButton < 0) return false;
            mOneHandButton = -1;
            invalidate();
            return true;
        default:
            return mOneHandButton >= 0;
        }
    }

    private void drawOneHandButtons(Canvas c) {
        if (mOneHandGap <= 0 || mIsExtensionType) return;
        if (mOneHandSwapIcon == null) {
            mOneHandSwapIcon = getContext().getDrawable(R.drawable.ic_one_hand_swap).mutate();
            mOneHandExitIcon = getContext().getDrawable(R.drawable.ic_one_hand_exit).mutate();
        }
        int color = getKeyTextColor();
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(Math.min(28 * d, mOneHandGap * 0.6f));
        int cx = oneHandGapLeft() + mOneHandGap / 2;
        int keysTop = getPaddingTop(), keysBottom = getHeight() - getPaddingBottom();
        int quarter = (keysBottom - keysTop) / 4;
        android.graphics.drawable.Drawable[] icons = { mOneHandSwapIcon, mOneHandExitIcon };
        for (int i = 0; i < 2; i++) {
            int cy = keysTop + quarter * (i * 2 + 1);
            if (i == mOneHandButton) {
                mOneHandPaint.setColor((color & 0x00FFFFFF) | 0x26000000);
                c.drawCircle(cx, cy, size * 0.9f, mOneHandPaint);
            }
            icons[i].setTint(color);
            icons[i].setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            icons[i].draw(c);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        LatinKeyboard keyboard = (LatinKeyboard) getKeyboard();
        if (LatinIME.sKeyboardSettings.showTouchPos || DEBUG_LINE) {
            mLastX = (int) me.getX();
            mLastY = (int) me.getY();
            invalidate();
        }
        if (handleOneHandButtons(me)) return true;
        if (handleSpaceCursor(me)) return true;

        // If an extension keyboard is visible or this is an extension keyboard, don't look
        // for sudden jumps. Otherwise, if there was a sudden jump, return without processing the
        // actual motion event.
        if (!mExtensionVisible && !mIsExtensionType
                && handleSuddenJump(me)) return true;
        // Reset any bounding box controls in the keyboard
        if (me.getAction() == MotionEvent.ACTION_DOWN) {
            keyboard.keyReleased();
        }

        if (me.getAction() == MotionEvent.ACTION_UP) {
            int languageDirection = keyboard.getLanguageChangeDirection();
            if (languageDirection != 0) {
                getOnKeyboardActionListener().onKey(
                        languageDirection == 1 ? KEYCODE_NEXT_LANGUAGE : KEYCODE_PREV_LANGUAGE,
                        null, mLastX, mLastY);
                me.setAction(MotionEvent.ACTION_CANCEL);
                keyboard.keyReleased();
                return super.onTouchEvent(me);
            }
        }

        // If we don't have an extension keyboard, don't go any further.
        if (keyboard.getExtension() == null) {
            return super.onTouchEvent(me);
        }
        // If the motion event is above the keyboard and it's not an UP event coming
        // even before the first MOVE event into the extension area
        if (me.getY() < 0 && (mExtensionVisible || me.getAction() != MotionEvent.ACTION_UP)) {
            if (mExtensionVisible) {
                int action = me.getAction();
                if (mFirstEvent) action = MotionEvent.ACTION_DOWN;
                mFirstEvent = false;
                MotionEvent translated = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                        action,
                        me.getX(), me.getY() + mExtension.getHeight(), me.getMetaState());
                if (me.getActionIndex() > 0)
                    return true;  // ignore second touches to avoid "pointerIndex out of range"
                boolean result = mExtension.onTouchEvent(translated);
                translated.recycle();
                if (me.getAction() == MotionEvent.ACTION_UP
                        || me.getAction() == MotionEvent.ACTION_CANCEL) {
                    closeExtension();
                }
                return result;
            } else {
                if (swipeUp()) {
                    return true;
                } else if (openExtension()) {
                    MotionEvent cancel = MotionEvent.obtain(me.getDownTime(), me.getEventTime(),
                            MotionEvent.ACTION_CANCEL, me.getX() - 100, me.getY() - 100, 0);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    if (mExtension.getHeight() > 0) {
                        MotionEvent translated = MotionEvent.obtain(me.getEventTime(),
                                me.getEventTime(),
                                MotionEvent.ACTION_DOWN,
                                me.getX(), me.getY() + mExtension.getHeight(),
                                me.getMetaState());
                        mExtension.onTouchEvent(translated);
                        translated.recycle();
                    } else {
                        mFirstEvent = true;
                    }
                    // Stop processing multi-touch errors
                    mDisableDisambiguation  = true;
                }
                return true;
            }
        } else if (mExtensionVisible) {
            closeExtension();
            // Send a down event into the main keyboard first
            MotionEvent down = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                    MotionEvent.ACTION_DOWN,
                    me.getX(), me.getY(), me.getMetaState());
            super.onTouchEvent(down, true);
            down.recycle();
            // Send the actual event
            return super.onTouchEvent(me);
        } else {
            return super.onTouchEvent(me);
        }
    }

    private void setExtensionType(boolean isExtensionType) {
        mIsExtensionType = isExtensionType;
    }

    private boolean openExtension() {
        // If the current keyboard is not visible, or if the mini keyboard is active, don't show the popup
        if (!isShown() || popupKeyboardIsShowing()) {
            return false;
        }
        PointerTracker.clearSlideKeys();
        if (((LatinKeyboard) getKeyboard()).getExtension() == null) return false;
        makePopupWindow();
        mExtensionVisible = true;
        return true;
    }

    private void makePopupWindow() {
        dismissPopupKeyboard();
        if (mExtensionPopup == null) {
            int[] windowLocation = new int[2];
            mExtensionPopup = new PopupWindow(getContext());
            mExtensionPopup.setBackgroundDrawable(null);
            LayoutInflater li = (LayoutInflater) getContext().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mExtension = (LatinKeyboardView) li.inflate(mExtensionLayoutResId == 0 ?
                    R.layout.input_trans : mExtensionLayoutResId, null);
            Keyboard keyboard = mExtensionKeyboard;
            mExtension.setKeyboard(keyboard);
            mExtension.setExtensionType(true);
            // In one-handed mode the extension row sits over the keys, not the empty side
            mExtension.setPadding(mOneHandKeysRight ? mOneHandGap : 0, 0,
                    mOneHandKeysRight ? 0 : mOneHandGap, 0);
            mExtension.setOnKeyboardActionListener(
                    new ExtensionKeyboardListener(getOnKeyboardActionListener()));
            mExtension.setPopupParent(this);
            mExtension.setPopupOffset(0, -windowLocation[1]);
            mExtensionPopup.setContentView(mExtension);
            mExtensionPopup.setWidth(getWidth());
            mExtensionPopup.setHeight(keyboard.getHeight());
            mExtensionPopup.setAnimationStyle(-1);
            getLocationInWindow(windowLocation);
            // TODO: Fix the "- 30". 
            mExtension.setPopupOffset(0, -windowLocation[1] - 30);
            mExtensionPopup.showAtLocation(this, 0, 0, -keyboard.getHeight()
                    + windowLocation[1] + this.getPaddingTop());
        } else {
            mExtension.setVisibility(VISIBLE);
        }
        mExtension.setShiftState(getShiftState()); // propagate shift state
    }

    @Override
    public void closing() {
        super.closing();
        if (mExtensionPopup != null && mExtensionPopup.isShowing()) {
            mExtensionPopup.dismiss();
            mExtensionPopup = null;
        }
    }

    private void closeExtension() {
        mExtension.closing();
        mExtension.setVisibility(INVISIBLE);
        mExtensionVisible = false;
    }

    private static class ExtensionKeyboardListener implements OnKeyboardActionListener {
        private OnKeyboardActionListener mTarget;
        ExtensionKeyboardListener(OnKeyboardActionListener target) {
            mTarget = target;
        }
        public void onKey(int primaryCode, int[] keyCodes, int x, int y) {
            mTarget.onKey(primaryCode, keyCodes, x, y);
        }
        public void onPress(int primaryCode) {
            mTarget.onPress(primaryCode);
        }
        public void onRelease(int primaryCode) {
            mTarget.onRelease(primaryCode);
        }
        public void onText(CharSequence text) {
            mTarget.onText(text);
        }
        public void onCancel() {
            mTarget.onCancel();
        }
        public boolean swipeDown() {
            // Don't pass through
            return true;
        }
        public boolean swipeLeft() {
            // Don't pass through
            return true;
        }
        public boolean swipeRight() {
            // Don't pass through
            return true;
        }
        public boolean swipeUp() {
            // Don't pass through
            return true;
        }
    }

    /****************************  INSTRUMENTATION  *******************************/

    static final boolean DEBUG_AUTO_PLAY = false;
    static final boolean DEBUG_LINE = false;
    private static final int MSG_TOUCH_DOWN = 1;
    private static final int MSG_TOUCH_UP = 2;

    Handler mHandler2;

    private String mStringToPlay;
    private int mStringIndex;
    private boolean mDownDelivered;
    private Key[] mAsciiKeys = new Key[256];
    private boolean mPlaying;
    private int mLastX;
    private int mLastY;
    private Paint mPaint;

    private void setKeyboardLocal(Keyboard k) {
        if (DEBUG_AUTO_PLAY) {
            findKeys();
            if (mHandler2 == null) {
                mHandler2 = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        removeMessages(MSG_TOUCH_DOWN);
                        removeMessages(MSG_TOUCH_UP);
                        if (mPlaying == false) return;

                        switch (msg.what) {
                            case MSG_TOUCH_DOWN:
                                if (mStringIndex >= mStringToPlay.length()) {
                                    mPlaying = false;
                                    return;
                                }
                                char c = mStringToPlay.charAt(mStringIndex);
                                while (c > 255 || mAsciiKeys[c] == null) {
                                    mStringIndex++;
                                    if (mStringIndex >= mStringToPlay.length()) {
                                        mPlaying = false;
                                        return;
                                    }
                                    c = mStringToPlay.charAt(mStringIndex);
                                }
                                int x = mAsciiKeys[c].x + 10;
                                int y = mAsciiKeys[c].y + 26;
                                MotionEvent me = MotionEvent.obtain(SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN, x, y, 0);
                                LatinKeyboardView.this.dispatchTouchEvent(me);
                                me.recycle();
                                sendEmptyMessageDelayed(MSG_TOUCH_UP, 500); // Deliver up in 500ms if nothing else
                                // happens
                                mDownDelivered = true;
                                break;
                            case MSG_TOUCH_UP:
                                char cUp = mStringToPlay.charAt(mStringIndex);
                                int x2 = mAsciiKeys[cUp].x + 10;
                                int y2 = mAsciiKeys[cUp].y + 26;
                                mStringIndex++;

                                MotionEvent me2 = MotionEvent.obtain(SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP, x2, y2, 0);
                                LatinKeyboardView.this.dispatchTouchEvent(me2);
                                me2.recycle();
                                sendEmptyMessageDelayed(MSG_TOUCH_DOWN, 500); // Deliver up in 500ms if nothing else
                                // happens
                                mDownDelivered = false;
                                break;
                        }
                    }
                };

            }
        }
    }

    private void findKeys() {
        List<Key> keys = getKeyboard().getKeys();
        // Get the keys on this keyboard
        for (int i = 0; i < keys.size(); i++) {
            int code = keys.get(i).codes[0];
            if (code >= 0 && code <= 255) {
                mAsciiKeys[code] = keys.get(i);
            }
        }
    }

    public void startPlaying(String s) {
        if (DEBUG_AUTO_PLAY) {
            if (s == null) return;
            mStringToPlay = s.toLowerCase();
            mPlaying = true;
            mDownDelivered = false;
            mStringIndex = 0;
            mHandler2.sendEmptyMessageDelayed(MSG_TOUCH_DOWN, 10);
        }
    }

    @Override
    public void draw(Canvas c) {
        LatinIMEUtil.GCUtils.getInstance().reset();
        boolean tryGC = true;
        for (int i = 0; i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
            try {
                super.draw(c);
                tryGC = false;
            } catch (OutOfMemoryError e) {
                tryGC = LatinIMEUtil.GCUtils.getInstance().tryGCOrWait("LatinKeyboardView", e);
            }
        }
        if (DEBUG_AUTO_PLAY) {
            if (mPlaying) {
                mHandler2.removeMessages(MSG_TOUCH_DOWN);
                mHandler2.removeMessages(MSG_TOUCH_UP);
                if (mDownDelivered) {
                    mHandler2.sendEmptyMessageDelayed(MSG_TOUCH_UP, 20);
                } else {
                    mHandler2.sendEmptyMessageDelayed(MSG_TOUCH_DOWN, 20);
                }
            }
        }
        drawOneHandButtons(c);
        if (LatinIME.sKeyboardSettings.showTouchPos || DEBUG_LINE) {
            if (mPaint == null) {
                mPaint = new Paint();
                mPaint.setColor(0x80FFFFFF);
                mPaint.setAntiAlias(false);
            }
            c.drawLine(mLastX, 0, mLastX, getHeight(), mPaint);
            c.drawLine(0, mLastY, getWidth(), mLastY, mPaint);
        }
    }
}
