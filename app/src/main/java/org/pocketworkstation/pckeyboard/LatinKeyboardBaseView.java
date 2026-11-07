/*
 * Copyright (C) 2010 The Android Open Source Project
 * Modifications Copyright (C) 2026 Yaroslav Bezzubets
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Paint.Align;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;

import org.pocketworkstation.pckeyboard.Keyboard.Key;

import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup.LayoutParams;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * A view that renders a virtual {@link LatinKeyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 *
 * TODO: References to LatinKeyboard in this class should be replaced with ones to its base class.
 *
 * @attr ref R.styleable#LatinKeyboardBaseView_keyBackground
 * @attr ref R.styleable#LatinKeyboardBaseView_keyPreviewLayout
 * @attr ref R.styleable#LatinKeyboardBaseView_keyPreviewOffset
 * @attr ref R.styleable#LatinKeyboardBaseView_labelTextSize
 * @attr ref R.styleable#LatinKeyboardBaseView_keyTextSize
 * @attr ref R.styleable#LatinKeyboardBaseView_keyTextColor
 * @attr ref R.styleable#LatinKeyboardBaseView_verticalCorrection
 * @attr ref R.styleable#LatinKeyboardBaseView_popupLayout
 */
public class LatinKeyboardBaseView extends View implements PointerTracker.UIProxy {
    private static final String TAG = "HK/LatinKbdBaseView";
    private static final boolean DEBUG = false;

    public static final int NOT_A_TOUCH_COORDINATE = -1;

    public interface OnKeyboardActionListener {

        /**
         * Called when the user presses a key. This is sent before the
         * {@link #onKey} is called. For keys that repeat, this is only
         * called once.
         *
         * @param primaryCode
         *            the unicode of the key being pressed. If the touch is
         *            not on a valid key, the value will be zero.
         */
        void onPress(int primaryCode);

        /**
         * Called when the user releases a key. This is sent after the
         * {@link #onKey} is called. For keys that repeat, this is only
         * called once.
         *
         * @param primaryCode
         *            the code of the key that was released
         */
        void onRelease(int primaryCode);

        /**
         * Send a key press to the listener.
         *
         * @param primaryCode
         *            this is the key that was pressed
         * @param keyCodes
         *            the codes for all the possible alternative keys with
         *            the primary code being the first. If the primary key
         *            code is a single character such as an alphabet or
         *            number or symbol, the alternatives will include other
         *            characters that may be on the same key or adjacent
         *            keys. These codes are useful to correct for
         *            accidental presses of a key adjacent to the intended
         *            key.
         * @param x
         *            x-coordinate pixel of touched event. If onKey is not called by onTouchEvent,
         *            the value should be NOT_A_TOUCH_COORDINATE.
         * @param y
         *            y-coordinate pixel of touched event. If onKey is not called by onTouchEvent,
         *            the value should be NOT_A_TOUCH_COORDINATE.
         */
        void onKey(int primaryCode, int[] keyCodes, int x, int y);

        /**
         * Sends a sequence of characters to the listener.
         *
         * @param text
         *            the sequence of characters to be displayed.
         */
        void onText(CharSequence text);

        /**
         * Called when user released a finger outside any key.
         */
        void onCancel();

        /**
         * Called when the user quickly moves the finger from right to
         * left.
         */
        boolean swipeLeft();

        /**
         * Called when the user quickly moves the finger from left to
         * right.
         */
        boolean swipeRight();

        /**
         * Called when the user quickly moves the finger from up to down.
         */
        boolean swipeDown();

        /**
         * Called when the user quickly moves the finger from down to up.
         */
        boolean swipeUp();
    }

    // Timing constants
    private final int mKeyRepeatInterval;

    // Miscellaneous constants
    /* package */ static final int NOT_A_KEY = -1;
    private static final int NUMBER_HINT_VERTICAL_ADJUSTMENT_PIXEL = -1;

    // XML attribute
    private float mKeyTextSize;
    private float mLabelScale = 1.0f;
    private int mKeyTextColor;
    private int mKeyHintColor;
    private int mKeyHintInset;
    private int mKeyCursorColor;
    // Sticky modifiers that are on get a solid fill so Ctrl/Alt/Shift state is obvious
    private int mKeyActiveColor;
    private float mKeyActiveRadius = -1;  // not set: the Google themes' 6dp
    private int mKeyActiveTextColor = 0xFFFFFFFF;
    private final Paint mActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF mActiveRect = new android.graphics.RectF();
    private boolean mInvertSymbols;
    private boolean mRecolorSymbols;
    private Typeface mKeyTextStyle = Typeface.DEFAULT;
    // Word labels of function keys (Esc, Ctrl, Fn): semi-bold strokes match the icons' lines
    private static final Typeface FUNCTION_LABEL_TYPEFACE = Build.VERSION.SDK_INT >= 28
            ? Typeface.create(Typeface.DEFAULT, 600, false) : Typeface.DEFAULT_BOLD;
    private float mLabelTextSize;
    private int mSymbolColorScheme = 0;
    private int mShadowColor;
    private float mShadowRadius;
    private Drawable mKeyBackground;
    // Optional per-key keycaps and label colours, see attrs.xml
    private Drawable mKeyEscapeBackground;
    private Drawable mKeyEnterBackground;
    private Drawable mKeySpaceBackground;
    private int mKeyFunctionTextColor;
    private int mKeyAccentTextColor;
    private int mKeyEscapeTextColor;
    private int mBackgroundAlpha;
    private float mBackgroundDimAmount;
    private float mKeyHysteresisDistance;
    private float mVerticalCorrection;
    protected int mPreviewOffset;
    protected int mPreviewHeight;
    /** Theme's preview bubble and label colour, 0 when the theme keeps the classic one. */
    protected int mPreviewBackground;
    protected int mPreviewTextColor;
    protected int mPopupLayout;

    // Main keyboard
    private Keyboard mKeyboard;
    private Key[] mKeys;
    // TODO this attribute should be gotten from Keyboard.
    private int mKeyboardVerticalGap;

    // Key preview popup
    protected TextView mPreviewText;
    protected PopupWindow mPreviewPopup;
    protected int mPreviewTextSizeLarge;
    protected int[] mOffsetInWindow;
    protected int mOldPreviewKeyIndex = NOT_A_KEY;
    protected boolean mShowPreview = true;
    protected boolean mShowTouchPoints = true;
    protected int mPopupPreviewOffsetX;
    protected int mPopupPreviewOffsetY;
    protected int mWindowY;
    protected int mPopupPreviewDisplayedY;
    protected final int mDelayBeforePreview;
    protected final int mDelayBeforeSpacePreview;
    protected final int mDelayAfterPreview;

    // Popup mini keyboard
    protected PopupWindow mMiniKeyboardPopup;
    protected LatinKeyboardBaseView mMiniKeyboard;
    protected View mMiniKeyboardContainer;
    protected View mMiniKeyboardParent;
    protected boolean mMiniKeyboardVisible;
    protected final WeakHashMap<Key, Keyboard> mMiniKeyboardCacheMain = new WeakHashMap<Key, Keyboard>();
    protected final WeakHashMap<Key, Keyboard> mMiniKeyboardCacheShift = new WeakHashMap<Key, Keyboard>();
    protected final WeakHashMap<Key, Keyboard> mMiniKeyboardCacheCaps = new WeakHashMap<Key, Keyboard>();
    protected int mMiniKeyboardOriginX;
    protected int mMiniKeyboardOriginY;
    protected long mMiniKeyboardPopupTime;
    protected int[] mWindowOffset;
    protected final float mMiniKeyboardSlideAllowance;
    protected int mMiniKeyboardTrackerId;

    /** Listener for {@link OnKeyboardActionListener}. */
    private OnKeyboardActionListener mKeyboardActionListener;

    private final ArrayList<PointerTracker> mPointerTrackers = new ArrayList<PointerTracker>();
    private boolean mIgnoreMove = false;

    // TODO: Let the PointerTracker class manage this pointer queue
    private final PointerQueue mPointerQueue = new PointerQueue();

    private final boolean mHasDistinctMultitouch;
    private int mOldPointerCount = 1;

    protected KeyDetector mKeyDetector = new ProximityKeyDetector();

    // Swipe gesture detector
    private GestureDetector mGestureDetector;
    private final SwipeTracker mSwipeTracker = new SwipeTracker();
    private final int mSwipeThreshold;
    private final boolean mDisambiguateSwipe;

    // Drawing
    /** Whether the keyboard bitmap needs to be redrawn before it's blitted. **/
    private boolean mDrawPending;
    /** The dirty region in the keyboard bitmap */
    private final Rect mDirtyRect = new Rect();
    /** The keyboard bitmap for faster updates */
    private Bitmap mBuffer;

    // Bottom screen corners in view coordinates {centerX, centerY, radius}; radius 0 = none.
    // When the keyboard reaches the screen bottom (no navigation bar space) the corner keys
    // are cut along a curve concentric with the screen corner instead of the square key edge.
    private final float[] mCornerBL = new float[3];
    private final float[] mCornerBR = new float[3];
    // Same for the keyboard's own rounded top corners (setTopCornerRadius), when the keys
    // reach the top of the rounded panel (no suggestion strip above them).
    private final float[] mCornerTL = new float[3];
    private final float[] mCornerTR = new float[3];
    private float mTopCornerRadius;
    private final int[] mPanelLocation = new int[2];
    private final int[] mWindowLocation = new int[2];
    private final Paint mCornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mCornerPath = new Path();
    private final Path mCornerCircle = new Path();
    /** Notes if the keyboard just changed, so that we could possibly reallocate the mBuffer. */
    private boolean mKeyboardChanged;
    private Key mInvalidatedKey;
    /** The canvas for the above mutable keyboard bitmap */
    private Canvas mCanvas;
    private final Paint mPaint;
    private final Paint mPaintHint;
    private final Rect mPadding;
    private final Rect mClipRegion = new Rect(0, 0, 0, 0);
    private int mViewWidth;
    // This map caches key label text height in pixel as value and key label text size as map key.
    private final HashMap<Integer, Integer> mTextHeightCache = new HashMap<Integer, Integer>();
    // Distance from horizontal center of the key, proportional to key label text height.
    private final float KEY_LABEL_VERTICAL_ADJUSTMENT_FACTOR = 0.55f;
    private final String KEY_LABEL_HEIGHT_REFERENCE_CHAR = "H";
    /* package */ static Method sSetRenderMode;
    // Per view: a recreated keyboard view (theme change) must get its layer type too
    private int mPrevRenderMode = -1;

    private static final float[] INVERTING_MATRIX = {
            -1.f, 0, 0, 0, 255, // Red
            0, -1.f, 0, 0, 255, // Green
            0, 0, -1.f, 0, 255, // Blue
            0, 0, 0, 1.f, 0, // Alpha
    };
    private final ColorMatrixColorFilter mInvertingColorFilter = new ColorMatrixColorFilter(INVERTING_MATRIX);

    private final UIHandler mHandler = new UIHandler();

    class UIHandler extends Handler {
        private static final int MSG_POPUP_PREVIEW = 1;
        private static final int MSG_DISMISS_PREVIEW = 2;
        private static final int MSG_REPEAT_KEY = 3;
        private static final int MSG_LONGPRESS_KEY = 4;

        private boolean mInKeyRepeat;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POPUP_PREVIEW:
                    showKey(msg.arg1, (PointerTracker)msg.obj);
                    break;
                case MSG_DISMISS_PREVIEW:
                    mPreviewPopup.dismiss();
                    break;
                case MSG_REPEAT_KEY: {
                    final PointerTracker tracker = (PointerTracker)msg.obj;
                    tracker.repeatKey(msg.arg1);
                    startKeyRepeatTimer(mKeyRepeatInterval, msg.arg1, tracker);
                    break;
                }
                case MSG_LONGPRESS_KEY: {
                    final PointerTracker tracker = (PointerTracker)msg.obj;
                    openPopupIfRequired(msg.arg1, tracker);
                    break;
                }
            }
        }

        public void popupPreview(long delay, int keyIndex, PointerTracker tracker) {
            removeMessages(MSG_POPUP_PREVIEW);
            if (mPreviewPopup.isShowing() && mPreviewText.getVisibility() == VISIBLE) {
                // Show right away, if it's already visible and finger is moving around
                showKey(keyIndex, tracker);
            } else {
                sendMessageDelayed(obtainMessage(MSG_POPUP_PREVIEW, keyIndex, 0, tracker),
                        delay);
            }
        }

        public void cancelPopupPreview() {
            removeMessages(MSG_POPUP_PREVIEW);
        }

        public void dismissPreview(long delay) {
            if (mPreviewPopup.isShowing()) {
                sendMessageDelayed(obtainMessage(MSG_DISMISS_PREVIEW), delay);
            }
        }

        public void cancelDismissPreview() {
            removeMessages(MSG_DISMISS_PREVIEW);
        }

        public void startKeyRepeatTimer(long delay, int keyIndex, PointerTracker tracker) {
            mInKeyRepeat = true;
            sendMessageDelayed(obtainMessage(MSG_REPEAT_KEY, keyIndex, 0, tracker), delay);
        }

        public void cancelKeyRepeatTimer() {
            mInKeyRepeat = false;
            removeMessages(MSG_REPEAT_KEY);
        }

        public boolean isInKeyRepeat() {
            return mInKeyRepeat;
        }

        public void startLongPressTimer(long delay, int keyIndex, PointerTracker tracker) {
            removeMessages(MSG_LONGPRESS_KEY);
            sendMessageDelayed(obtainMessage(MSG_LONGPRESS_KEY, keyIndex, 0, tracker), delay);
        }

        public void cancelLongPressTimer() {
            removeMessages(MSG_LONGPRESS_KEY);
        }

        public void cancelKeyTimers() {
            cancelKeyRepeatTimer();
            cancelLongPressTimer();
        }

        public void cancelAllMessages() {
            cancelKeyTimers();
            cancelPopupPreview();
            cancelDismissPreview();
        }
    }

    static class PointerQueue {
        private LinkedList<PointerTracker> mQueue = new LinkedList<PointerTracker>();

        public void add(PointerTracker tracker) {
            mQueue.add(tracker);
        }

        public int lastIndexOf(PointerTracker tracker) {
            LinkedList<PointerTracker> queue = mQueue;
            for (int index = queue.size() - 1; index >= 0; index--) {
                PointerTracker t = queue.get(index);
                if (t == tracker)
                    return index;
            }
            return -1;
        }

        public void releaseAllPointersOlderThan(PointerTracker tracker, long eventTime) {
            LinkedList<PointerTracker> queue = mQueue;
            int oldestPos = 0;
            for (PointerTracker t = queue.get(oldestPos); t != tracker; t = queue.get(oldestPos)) {
                if (t.isModifier()) {
                    oldestPos++;
                } else {
                    t.onUpEvent(t.getLastX(), t.getLastY(), eventTime);
                    t.setAlreadyProcessed();
                    queue.remove(oldestPos);
                }
                if (queue.isEmpty()) return;
            }
        }

        public void releaseAllPointersExcept(PointerTracker tracker, long eventTime) {
            for (PointerTracker t : mQueue) {
                if (t == tracker)
                    continue;
                t.onUpEvent(t.getLastX(), t.getLastY(), eventTime);
                t.setAlreadyProcessed();
            }
            mQueue.clear();
            if (tracker != null)
                mQueue.add(tracker);
        }

        public void remove(PointerTracker tracker) {
            mQueue.remove(tracker);
        }

        public boolean isInSlidingKeyInput() {
            for (final PointerTracker tracker : mQueue) {
                if (tracker.isInSlidingKeyInput())
                    return true;
            }
            return false;
        }
    }

    static {
        initCompatibility();
    }
    
    static void initCompatibility() {
        try {
            sSetRenderMode = View.class.getMethod("setLayerType", int.class, Paint.class);
            Log.i(TAG, "setRenderMode is supported");
        } catch (SecurityException e) {
            Log.w(TAG, "unexpected SecurityException", e);
        } catch (NoSuchMethodException e) {
            // ignore, not supported by API level pre-Honeycomb
            Log.i(TAG, "ignoring render mode, not supported");
        }
    }
    
    private void setRenderModeIfPossible(int mode) {
        if (sSetRenderMode != null && mode != mPrevRenderMode) {
            try {
                sSetRenderMode.invoke(this, mode, null);
                mPrevRenderMode = mode;
                Log.i(TAG, "render mode set to " + LatinIME.sKeyboardSettings.renderMode);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }
    
    public LatinKeyboardBaseView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.keyboardViewStyle);
    }

    public LatinKeyboardBaseView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (!isInEditMode())
            Log.i(TAG, "Creating new LatinKeyboardBaseView " + this);
        setRenderModeIfPossible(LatinIME.sKeyboardSettings.renderMode);

        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.LatinKeyboardBaseView, defStyle, R.style.LatinKeyboardBaseView);

        int n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);

            
            if(attr== R.styleable.LatinKeyboardBaseView_keyBackground)
                mKeyBackground = a.getDrawable(attr);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyEscapeBackground)
                mKeyEscapeBackground = a.getDrawable(attr);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyEnterBackground)
                mKeyEnterBackground = a.getDrawable(attr);
            else if(attr== R.styleable.LatinKeyboardBaseView_keySpaceBackground)
                mKeySpaceBackground = a.getDrawable(attr);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyFunctionTextColor)
                mKeyFunctionTextColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyAccentTextColor)
                mKeyAccentTextColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyEscapeTextColor)
                mKeyEscapeTextColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyHysteresisDistance)
                mKeyHysteresisDistance = a.getDimensionPixelOffset(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_verticalCorrection)
                mVerticalCorrection = a.getDimensionPixelOffset(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyTextSize)
                mKeyTextSize = a.getDimensionPixelSize(attr, 18);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyTextColor)
                mKeyTextColor = a.getColor(attr, 0xFF000000);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyHintColor)
                mKeyHintColor = a.getColor(attr, 0xFFBBBBBB);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyHintInset)
                mKeyHintInset = a.getDimensionPixelSize(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyCursorColor)
                mKeyCursorColor = a.getColor(attr, 0xFF000000);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyActiveColor)
                mKeyActiveColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyActiveRadius)
                mKeyActiveRadius = a.getDimension(attr, -1);
            else if(attr== R.styleable.LatinKeyboardBaseView_keyActiveTextColor)
                mKeyActiveTextColor = a.getColor(attr, 0xFFFFFFFF);
            else if(attr== R.styleable.LatinKeyboardBaseView_invertSymbols)
                mInvertSymbols = a.getBoolean(attr, false);
            else if(attr== R.styleable.LatinKeyboardBaseView_recolorSymbols)
                mRecolorSymbols = a.getBoolean(attr, false);
            else if(attr== R.styleable.LatinKeyboardBaseView_labelTextSize)
                mLabelTextSize = a.getDimensionPixelSize(attr, 14);
            else if(attr== R.styleable.LatinKeyboardBaseView_shadowColor)
                mShadowColor = a.getColor(attr, 0);
            else if(attr== R.styleable.LatinKeyboardBaseView_shadowRadius)
                mShadowRadius = a.getFloat(attr, 0f);
            // TODO) Use Theme (android.R.styleable.Theme_backgroundDimAmount)
            else if(attr== R.styleable.LatinKeyboardBaseView_backgroundDimAmount)
                mBackgroundDimAmount = a.getFloat(attr, 0.5f);
            else if(attr== R.styleable.LatinKeyboardBaseView_backgroundAlpha)
                mBackgroundAlpha = a.getInteger(attr, 255);
            //else if(attr== android.R.styleable.
            else if(attr== R.styleable.LatinKeyboardBaseView_keyTextStyle){
                int textStyle = a.getInt(attr, 0);
                switch (textStyle) {
                    case 0:
                        mKeyTextStyle = Typeface.DEFAULT;
                        break;
                    case 1:
                        mKeyTextStyle = Typeface.DEFAULT_BOLD;
                        break;
                    default:
                        mKeyTextStyle = Typeface.defaultFromStyle(textStyle);
                        break;
                }}
            else if(attr==R.styleable.LatinKeyboardBaseView_symbolColorScheme)
                mSymbolColorScheme = a.getInt(attr, 0);
            
        }

        final Resources res = getResources();

        mShowPreview = false;
        mDelayBeforePreview = res.getInteger(R.integer.config_delay_before_preview);
        mDelayBeforeSpacePreview = res.getInteger(R.integer.config_delay_before_space_preview);
        mDelayAfterPreview = res.getInteger(R.integer.config_delay_after_preview);

        mPopupLayout = 0;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setTextAlign(Align.CENTER);
        mPaint.setAlpha(255);

        mPaintHint = new Paint();
        mPaintHint.setAntiAlias(true);
        mPaintHint.setTextAlign(Align.RIGHT);
        mPaintHint.setAlpha(255);
        mPaintHint.setTypeface(Typeface.DEFAULT_BOLD);

        mPadding = new Rect(0, 0, 0, 0);
        mKeyBackground.getPadding(mPadding);
        mCornerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

        mSwipeThreshold = (int) (300 * res.getDisplayMetrics().density);
        // TODO: Refer frameworks/base/core/res/res/values/config.xml
        // TODO(klausw): turn off mDisambiguateSwipe if no swipe actions are set?
        mDisambiguateSwipe = res.getBoolean(R.bool.config_swipeDisambiguation);
        mMiniKeyboardSlideAllowance = res.getDimension(R.dimen.mini_keyboard_slide_allowance);

        GestureDetector.SimpleOnGestureListener listener =
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent me1, MotionEvent me2, float velocityX,
                    float velocityY) {
                final float absX = Math.abs(velocityX);
                final float absY = Math.abs(velocityY);
                float deltaX = me2.getX() - me1.getX();
                float deltaY = me2.getY() - me1.getY();
                mSwipeTracker.computeCurrentVelocity(1000);
                final float endingVelocityX = mSwipeTracker.getXVelocity();
                final float endingVelocityY = mSwipeTracker.getYVelocity();
                // Calculate swipe distance threshold based on screen width & height,
                // taking the smaller distance.
                int travelX = getWidth() / 3;
                int travelY = getHeight() / 3;
                int travelMin = Math.min(travelX, travelY);
//                Log.i(TAG, "onFling vX=" + velocityX + " vY=" + velocityY + " threshold=" + mSwipeThreshold
//                        + " dX=" + deltaX + " dy=" + deltaY + " min=" + travelMin);
                if (velocityX > mSwipeThreshold && absY < absX && deltaX > travelMin) {
                    if (mDisambiguateSwipe && endingVelocityX >= velocityX / 4) {
                        if (swipeRight()) return true;
                    }
                } else if (velocityX < -mSwipeThreshold && absY < absX && deltaX < -travelMin) {
                    if (mDisambiguateSwipe && endingVelocityX <= velocityX / 4) {
                        if (swipeLeft()) return true;
                    }
                } else if (velocityY < -mSwipeThreshold && absX < absY && deltaY < -travelMin) {
                    if (mDisambiguateSwipe && endingVelocityY <= velocityY / 4) {
                        if (swipeUp()) return true;
                    }
                } else if (velocityY > mSwipeThreshold && absX < absY / 2 && deltaY > travelMin) {
                    if (mDisambiguateSwipe && endingVelocityY >= velocityY / 4) {
                        if (swipeDown()) return true;
                    }
                }
                return false;
            }
        };

        final boolean ignoreMultitouch = true;
        mGestureDetector = new GestureDetector(getContext(), listener, null, ignoreMultitouch);
        mGestureDetector.setIsLongpressEnabled(false);

        mHasDistinctMultitouch = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT);
        mKeyRepeatInterval = res.getInteger(R.integer.config_key_repeat_interval);
    }

    private boolean showHints7Bit() {
        return LatinIME.sKeyboardSettings.hintMode >= 1;
    }

    private boolean showHintsAll() {
        return LatinIME.sKeyboardSettings.hintMode >= 2;
    }

    public void setOnKeyboardActionListener(OnKeyboardActionListener listener) {
        mKeyboardActionListener = listener;
        for (PointerTracker tracker : mPointerTrackers) {
            tracker.setOnKeyboardActionListener(listener);
        }
    }

    /**
     * Returns the {@link OnKeyboardActionListener} object.
     * @return the listener attached to this keyboard
     */
    protected OnKeyboardActionListener getOnKeyboardActionListener() {
        return mKeyboardActionListener;
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    public void setKeyboard(Keyboard keyboard) {
        if (mKeyboard != null) {
            dismissKeyPreview();
        }
        //Log.i(TAG, "setKeyboard(" + keyboard + ") for " + this);
        // Remove any pending messages, except dismissing preview
        mHandler.cancelKeyTimers();
        mHandler.cancelPopupPreview();
        mKeyboard = keyboard;
        // Disable correctionX and correctionY, it doesn't seem to work as intended.
        // mKeys = mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(),-getPaddingTop() + mVerticalCorrection);
        mKeys = mKeyDetector.setKeyboard(keyboard, 0, 0);
        mKeyboardVerticalGap = (int)getResources().getDimension(R.dimen.key_bottom_gap);
        for (PointerTracker tracker : mPointerTrackers) {
            tracker.setKeyboard(mKeys, mKeyHysteresisDistance);
        }
        mLabelScale = LatinIME.sKeyboardSettings.labelScalePref;
        //if (keyboard.mLayoutRows >= 4) mLabelScale *= 5.0f / keyboard.mLayoutRows;
        requestLayout();
        // Hint to reallocate the buffer if the size changed
        mKeyboardChanged = true;
        invalidateAllKeys();
        computeProximityThreshold(keyboard);
        mMiniKeyboardCacheMain.clear();
        mMiniKeyboardCacheShift.clear();
        mMiniKeyboardCacheCaps.clear();
        setRenderModeIfPossible(LatinIME.sKeyboardSettings.renderMode);
        mIgnoreMove = true;
    }
    
    /**
     * Returns the current keyboard being displayed by this view.
     * @return the currently attached keyboard
     * @see #setKeyboard(Keyboard)
     */
    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    /**
     * Return whether the device has distinct multi-touch panel.
     * @return true if the device has distinct multi-touch panel.
     */
    public boolean hasDistinctMultitouch() {
        return mHasDistinctMultitouch;
    }

    /**
     * Sets the state of the shift key of the keyboard, if any.
     * @param shiftState whether or not to enable the state of the shift key
     * @return true if the shift key state changed, false if there was no change
     */
    public boolean setShiftState(int shiftState) {
        //Log.i(TAG, "setShifted " + shiftState);
        if (mKeyboard != null) {
            if (mKeyboard.setShiftState(shiftState)) {
                // The whole keyboard probably needs to be redrawn
                invalidateAllKeys();
                return true;
            }
        }
        return false;
    }

    public void setCtrlIndicator(boolean active) {
        if (mKeyboard != null) {
            invalidateKey(mKeyboard.setCtrlIndicator(active));
        }
    }
    
    public void setAltIndicator(boolean active) {
        if (mKeyboard != null) {
            invalidateKey(mKeyboard.setAltIndicator(active));
        }
    }

    public void setMetaIndicator(boolean active) {
        if (mKeyboard != null) {
            invalidateKey(mKeyboard.setMetaIndicator(active));
        }
    }

    /**
     * Returns the state of the shift key of the keyboard, if any.
     * @return true if the shift is in a pressed state, false otherwise. If there is
     * no shift key on the keyboard or there is no keyboard attached, it returns false.
     */
    public int getShiftState() {
        if (mKeyboard != null) {
            return mKeyboard.getShiftState();
        }
        return Keyboard.SHIFT_OFF;
    }
    
    public boolean isShiftCaps() {
        return getShiftState() != Keyboard.SHIFT_OFF;
    }

    public boolean isShiftAll() {
        int state = getShiftState();
        if (LatinIME.sKeyboardSettings.shiftLockModifiers) {
            return state == Keyboard.SHIFT_ON || state == Keyboard.SHIFT_LOCKED;
        } else {
            return state == Keyboard.SHIFT_ON;            
        }
    }

    /**
     * Enables or disables the key feedback popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled.
     * @param previewEnabled whether or not to enable the key feedback popup
     * @see #isPreviewEnabled()
     */
    public void setPreviewEnabled(boolean previewEnabled) {
        mShowPreview = previewEnabled;
    }

    /**
     * Returns the enabled state of the key feedback popup.
     * @return whether or not the key feedback popup is enabled
     * @see #setPreviewEnabled(boolean)
     */
    public boolean isPreviewEnabled() {
        return mShowPreview;
    }

    /** Label colour of this theme (the suggestion strip uses it too). */
    int getKeyTextColor() {
        return mKeyTextColor;
    }

    /**
     * Key icons follow the label size like text does: about 1.2x the key text size, which is
     * Material's 24dp on the compact portrait layout, smaller on the narrow full layout keys.
     */
    static float iconScale(float keyTextSizePx, float labelScale, float density) {
        float s = keyTextSizePx * labelScale * 1.2f / (24 * density);
        return Math.max(0.5f, Math.min(1.5f, s));
    }

    private boolean isBlackSym() {
        return mSymbolColorScheme == 1;
    }

    public void setPopupParent(View v) {
        mMiniKeyboardParent = v;
    }

    public void setPopupOffset(int x, int y) {
        mPopupPreviewOffsetX = x;
        mPopupPreviewOffsetY = y;
        if (mPreviewPopup != null) mPreviewPopup.dismiss();
    }

    /**
     * When enabled, calls to {@link OnKeyboardActionListener#onKey} will include key
     * codes for adjacent keys.  When disabled, only the primary key code will be
     * reported.
     * @param enabled whether or not the proximity correction is enabled
     */
    public void setProximityCorrectionEnabled(boolean enabled) {
        mKeyDetector.setProximityCorrectionEnabled(enabled);
    }

    /**
     * Returns true if proximity correction is enabled.
     */
    public boolean isProximityCorrectionEnabled() {
        return mKeyDetector.isProximityCorrectionEnabled();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Round up a little
        if (mKeyboard == null) {
            setMeasuredDimension(
                    getPaddingLeft() + getPaddingRight(), getPaddingTop() + getPaddingBottom());
        } else {
            int width = mKeyboard.getMinWidth() + getPaddingLeft() + getPaddingRight();
            if (MeasureSpec.getSize(widthMeasureSpec) < width + 10) {
                int badWidth = MeasureSpec.getSize(widthMeasureSpec);
                if (badWidth != width) Log.i(TAG, "ignoring unexpected width=" + badWidth);
            }
            Log.i(TAG, "onMeasure width=" + width);
            setMeasuredDimension(
                    width, mKeyboard.getHeight() + getPaddingTop() + getPaddingBottom());
        }
    }

    /**
     * Compute the average distance between adjacent keys (horizontally and vertically)
     * and square it to get the proximity threshold. We use a square here and in computing
     * the touch distance from a key's center to avoid taking a square root.
     * @param keyboard
     */
    private void computeProximityThreshold(Keyboard keyboard) {
        if (keyboard == null) return;
        final Key[] keys = mKeys;
        if (keys == null) return;
        int length = keys.length;
        int dimensionSum = 0;
        for (int i = 0; i < length; i++) {
            Key key = keys[i];
            dimensionSum += Math.min(key.width, key.height + mKeyboardVerticalGap) + key.gap;
        }
        if (dimensionSum < 0 || length == 0) return;
        mKeyDetector.setProximityThreshold((int) (dimensionSum * 1.4f / length));
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Log.i(TAG, "onSizeChanged, w=" + w + ", h=" + h);
        mViewWidth = w;
        // Release the buffer, if any and it will be reallocated on the next draw
        mBuffer = null;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        //Log.i(TAG, "onDraw called " + canvas.getClipBounds());
        mCanvas = canvas;
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw(canvas);
        }
        if (mBuffer != null) canvas.drawBitmap(mBuffer, 0, 0, null);
    }
    
    /** "2 ABC": the label and a smaller sub label after it, centred together on x. */
    private void drawLabelWithSubLabel(Canvas canvas, String label, String subLabel, int x,
            float baseline, int maxWidth, Paint paint) {
        final float keySize = paint.getTextSize();
        float labelSize = keySize;
        paint.setTextSize(labelSize);
        float labelWidth = paint.measureText(label);
        paint.setTextSize(labelSize * 0.5f);
        float subWidth = paint.measureText(subLabel);
        float gap = labelSize * 0.2f;
        // Never wider than the key
        final float fit = maxWidth * 0.9f / (labelWidth + gap + subWidth);
        if (fit < 1) {
            labelSize *= fit;
            labelWidth *= fit;
            subWidth *= fit;
            gap *= fit;
            paint.setTextSize(labelSize * 0.5f);
        }
        final float left = x - (labelWidth + gap + subWidth) / 2;
        final Align align = paint.getTextAlign();
        paint.setTextAlign(Align.LEFT);
        canvas.drawText(subLabel, left + labelWidth + gap, baseline, paint);
        paint.setTextSize(labelSize);
        canvas.drawText(label, left, baseline, paint);
        paint.setTextSize(keySize);
        paint.setTextAlign(align);
    }

    private void drawDeadKeyLabel(Canvas canvas, String hint, int x, float baseline, Paint paint) {
        char c = hint.charAt(0);
        String accent = DeadAccentSequence.getSpacing(c);
        canvas.drawText(Keyboard.DEAD_KEY_PLACEHOLDER_STRING, x, baseline, paint);
        canvas.drawText(accent, x, baseline, paint);
    }

    private int getLabelHeight(Paint paint, int labelSize) {
        Integer labelHeightValue = mTextHeightCache.get(labelSize);
        if (labelHeightValue != null) {
            return labelHeightValue;
        } else {
            Rect textBounds = new Rect();
            paint.getTextBounds(KEY_LABEL_HEIGHT_REFERENCE_CHAR, 0, 1, textBounds);
            int labelHeight = textBounds.height();
            mTextHeightCache.put(labelSize, labelHeight);
            return labelHeight;
        }
    }

    /**
     * Solid fill over an active sticky modifier (canvas translated to the key); a locked
     * key (Caps Lock, double-tapped modifier) also gets a bar near its bottom edge.
     */
    private void drawActiveHighlight(Canvas canvas, Drawable bg, Key key) {
        float d = getResources().getDisplayMetrics().density;
        float radius;
        if (bg.getCurrent() instanceof InsetDrawable) {
            // tile themes: fill exactly the visible tile
            mActiveRect.set(mPadding.left, mPadding.top, key.width - mPadding.right, key.height - mPadding.bottom);
            radius = mKeyActiveRadius >= 0 ? mKeyActiveRadius : 6 * d;
        } else {
            mActiveRect.set(2 * d, 2 * d, key.width - 2 * d, key.height - 2 * d);
            radius = 5 * d;
        }
        mActivePaint.setColor(mKeyActiveColor);
        canvas.drawRoundRect(mActiveRect, radius, radius, mActivePaint);
        if (key.locked) {
            float barW = (mActiveRect.width()) * 0.4f, barH = 3 * d;
            float cx = mActiveRect.centerX(), bottom = mActiveRect.bottom - 4 * d;
            mActivePaint.setColor(mKeyActiveTextColor);
            canvas.drawRoundRect(cx - barW / 2, bottom - barH, cx + barW / 2, bottom, barH / 2, barH / 2, mActivePaint);
        }
    }

    /**
     * Radius in px of the rounded top corners of the panel holding this view (the suggestion
     * strip and keyboard container); 0 = square.
     */
    public void setTopCornerRadius(float radius) {
        if (mTopCornerRadius == radius) return;
        mTopCornerRadius = radius;
        invalidateAllKeys();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Moved under or out from under the suggestion strip: the corner keys change shape,
        // and a plain move doesn't redraw the view
        if (changed && mTopCornerRadius > 0) invalidateAllKeys();
    }

    private void updateTopCorners() {
        mCornerTL[2] = 0;
        mCornerTR[2] = 0;
        float r = mTopCornerRadius;
        if (r <= 0 || !(getParent() instanceof View)) return;
        View panel = (View) getParent();
        panel.getLocationInWindow(mPanelLocation);
        getLocationInWindow(mWindowLocation);
        float cy = mPanelLocation[1] + r - mWindowLocation[1];
        if (cy <= 0) return; // the suggestion strip takes the curve, the keys are below it
        mCornerTL[0] = mPanelLocation[0] + r - mWindowLocation[0];
        mCornerTR[0] = mPanelLocation[0] + panel.getWidth() - r - mWindowLocation[0];
        mCornerTL[1] = mCornerTR[1] = cy;
        mCornerTL[2] = mCornerTR[2] = r;
    }

    private void updateScreenCorners() {
        updateTopCorners();
        mCornerBL[2] = 0;
        mCornerBR[2] = 0;
        if (Build.VERSION.SDK_INT < 31) return;
        WindowInsets insets = getRootWindowInsets();
        if (insets == null) return;
        getLocationInWindow(mWindowLocation);
        setCorner(mCornerBL, insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT));
        setCorner(mCornerBR, insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT));
    }

    private void setCorner(float[] out, RoundedCorner corner) {
        if (Build.VERSION.SDK_INT < 31 || corner == null || corner.getRadius() <= 0) return;
        out[0] = corner.getCenter().x - mWindowLocation[0];
        out[1] = corner.getCenter().y - mWindowLocation[1];
        out[2] = corner.getRadius();
    }

    /**
     * Draws a key background (canvas already translated to the key) and, for a key in a
     * bottom screen corner, rounds its outer corner concentrically with the screen corner.
     */
    private void drawKeyBackground(Canvas canvas, Drawable bg, int left, int top, int w, int h) {
        boolean bl = inCorner(mCornerBL, true, left, top, w, h);
        boolean br = inCorner(mCornerBR, false, left, top, w, h);
        boolean tl = inTopCorner(mCornerTL, true, left, top, w);
        boolean tr = inTopCorner(mCornerTR, false, left, top, w);
        if (!bl && !br && !tl && !tr) {
            bg.draw(canvas);
            return;
        }
        // Keys drawn with a gap (inset tiles) keep the same gap along the curve
        boolean tile = bg.getCurrent() instanceof InsetDrawable;
        float gap = tile ? Math.min(mPadding.left, mPadding.bottom) : 0;
        float topGap = tile ? Math.min(mPadding.left, mPadding.top) : 0;
        int save = canvas.saveLayer(0, 0, w, h, null);
        bg.draw(canvas);
        if (bl) cutCorner(canvas, mCornerBL, true, false, gap, left, top);
        if (br) cutCorner(canvas, mCornerBR, false, false, gap, left, top);
        if (tl) cutCorner(canvas, mCornerTL, true, true, topGap, left, top);
        if (tr) cutCorner(canvas, mCornerTR, false, true, topGap, left, top);
        canvas.restoreToCount(save);
    }

    /** The key's keycap: its own for Esc, Enter or space when the theme has one. */
    private Drawable keyBackgroundFor(Key key) {
        if (key.codes != null && key.codes.length > 0) {
            switch (key.codes[0]) {
            case LatinKeyboardView.KEYCODE_ESCAPE:
                if (mKeyEscapeBackground != null) return mKeyEscapeBackground;
                break;
            case LatinIME.ASCII_ENTER:
                if (mKeyEnterBackground != null) return mKeyEnterBackground;
                break;
            case LatinIME.ASCII_SPACE:
                if (mKeySpaceBackground != null) return mKeySpaceBackground;
                break;
            }
        }
        return mKeyBackground;
    }

    private int keyTextColorFor(Key key, int[] drawableState) {
        if (mKeyEscapeTextColor != 0 && mKeyEscapeBackground != null && key.codes != null
                && key.codes.length > 0 && key.codes[0] == LatinKeyboardView.KEYCODE_ESCAPE) {
            return mKeyEscapeTextColor;
        }
        if (mKeyAccentTextColor != 0 && key.codes != null && key.codes.length > 0
                && ((key.codes[0] == LatinKeyboardView.KEYCODE_ESCAPE && mKeyEscapeBackground != null)
                || (key.codes[0] == LatinIME.ASCII_ENTER && mKeyEnterBackground != null))) {
            return mKeyAccentTextColor;
        }
        if (mKeyFunctionTextColor != 0 && key.codes != null && key.codes.length > 0
                && key.codes[0] != LatinIME.ASCII_SPACE) {
            for (int s : drawableState) {
                if (s == android.R.attr.state_single || s == android.R.attr.state_checkable) {
                    return mKeyFunctionTextColor;
                }
            }
        }
        return mKeyTextColor;
    }

    private boolean inTopCorner(float[] c, boolean left, int x, int y, int w) {
        if (c[2] <= 0) return false;
        if (y >= c[1]) return false; // key starts below the curved part
        return left ? x < c[0] : x + w > c[0];
    }

    private boolean inCorner(float[] c, boolean left, int x, int y, int w, int h) {
        if (c[2] <= 0) return false;
        if (y + h <= c[1]) return false; // key ends above the curved part
        return left ? x < c[0] : x + w > c[0];
    }

    /** Clears the part of the key outside the corner circle (in key-local coordinates). */
    private void cutCorner(Canvas canvas, float[] c, boolean left, boolean top, float gap,
            int keyX, int keyY) {
        float cx = c[0] - keyX, cy = c[1] - keyY, r = c[2];
        float big = r + getHeight() + getWidth();
        float y0 = top ? cy - big : cy, y1 = top ? cy : cy + big;
        mCornerPath.reset();
        if (left) {
            mCornerPath.addRect(cx - big, y0, cx, y1, Path.Direction.CW);
        } else {
            mCornerPath.addRect(cx, y0, cx + big, y1, Path.Direction.CW);
        }
        mCornerCircle.reset();
        mCornerCircle.addCircle(cx, cy, Math.max(0, r - gap), Path.Direction.CW);
        mCornerPath.op(mCornerCircle, Path.Op.DIFFERENCE);
        canvas.drawPath(mCornerPath, mCornerPaint);
    }

    private void onBufferDraw(Canvas canvas) {
        //Log.i(TAG, "onBufferDraw called");
        if (/*mBuffer == null ||*/ mKeyboardChanged) {
            mKeyboard.setKeyboardWidth(mViewWidth);
//            if (mBuffer == null || mKeyboardChanged &&
//                    (mBuffer.getWidth() != getWidth() || mBuffer.getHeight() != getHeight())) {
//                // Make sure our bitmap is at least 1x1
//                final int width = Math.max(1, getWidth());
//                final int height = Math.max(1, getHeight());
//                mBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
//                mCanvas = new Canvas(mBuffer);
//            }
            invalidateAllKeys();
            mKeyboardChanged = false;
        }
        //final Canvas canvas = mCanvas;
        //canvas.clipRect(mDirtyRect, Op.REPLACE);
        canvas.getClipBounds(mDirtyRect);
        //canvas.drawColor(Color.BLACK);

        if (mKeyboard == null) return;

        final Paint paint = mPaint;
        final Paint paintHint = mPaintHint;
        paintHint.setColor(mKeyHintColor);
        final Rect clipRegion = mClipRegion;
        final Rect padding = mPadding;
        final int kbdPaddingLeft = getPaddingLeft();
        final int kbdPaddingTop = getPaddingTop();
        final Key[] keys = mKeys;
        final Key invalidKey = mInvalidatedKey;

        ColorFilter iconColorFilter = null;
        ColorFilter shadowColorFilter = null;
        if (mInvertSymbols) {
            iconColorFilter = mInvertingColorFilter;
        } else if (mRecolorSymbols) {
            // TODO: cache these?
            iconColorFilter = new PorterDuffColorFilter(
                    mKeyTextColor, PorterDuff.Mode.SRC_ATOP);
            shadowColorFilter = new PorterDuffColorFilter(
                    mShadowColor, PorterDuff.Mode.SRC_ATOP);
        }

        boolean drawSingleKey = false;
        if (invalidKey != null && canvas.getClipBounds(clipRegion)) {
            // TODO we should use Rect.inset and Rect.contains here.
            // Is clipRegion completely contained within the invalidated key?
            if (invalidKey.x + kbdPaddingLeft - 1 <= clipRegion.left &&
                    invalidKey.y + kbdPaddingTop - 1 <= clipRegion.top &&
                    invalidKey.x + invalidKey.width + kbdPaddingLeft + 1 >= clipRegion.right &&
                    invalidKey.y + invalidKey.height + kbdPaddingTop + 1 >= clipRegion.bottom) {
                drawSingleKey = true;
            }
        }
        //canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
        final int keyCount = keys.length;

        // Scale the key labels based on the median key size.
        List<Integer> keyWidths = new ArrayList<Integer>();
        List<Integer> keyHeights = new ArrayList<Integer>();
        for (int i = 0; i < keyCount; i++) {
            final Key key = keys[i];
            keyWidths.add(key.width);
            keyHeights.add(key.height);
        }
        Collections.sort(keyWidths);
        Collections.sort(keyHeights);
        int medianKeyWidth = keyWidths.get(keyCount / 2);
        int medianKeyHeight = keyHeights.get(keyCount / 2);
        // Use 60% of the smaller of width or height. This is kind of arbitrary.
        // Big keys (the phone keypad, tablets) stop at about the size of a phone's letter keys
        final int maxKeyTextSize = (int) (26 * getResources().getDisplayMetrics().density);
        mKeyTextSize = Math.min(maxKeyTextSize,
                Math.min(medianKeyHeight * 6 / 10, medianKeyWidth * 6 / 10));
        mLabelTextSize = mKeyTextSize * 3 / 4;

        updateScreenCorners();

        int keysDrawn = 0;
        for (int i = 0; i < keyCount; i++) {
            final Key key = keys[i];
            if (drawSingleKey && invalidKey != key) {
                continue;
            }
            if (!mDirtyRect.intersects(
                    key.x + kbdPaddingLeft,
                    key.y + kbdPaddingTop,
                    key.x + key.width + kbdPaddingLeft,
                    key.y + key.height + kbdPaddingTop)) {
                continue;
            }
            keysDrawn++;
            int[] drawableState = key.getCurrentDrawableState();
            final Drawable keyBackground = keyBackgroundFor(key);
            keyBackground.setState(drawableState);
            final int keyTextColor = keyTextColorFor(key, drawableState);

            final boolean active = mKeyActiveColor != 0 && key.sticky && (key.on || key.locked);
            paint.setColor(active ? mKeyActiveTextColor : key.isCursor ? mKeyCursorColor : keyTextColor);

            // Switch the character to uppercase if shift is pressed
            String label = key.getCaseLabel();

            float yscale = 1.0f;
            final Rect bounds = keyBackground.getBounds();
            if (key.width != bounds.right || key.height != bounds.bottom) {
                int minHeight = keyBackground.getMinimumHeight();
                if (minHeight > key.height) {
                    yscale = (float) key.height / minHeight;
                    keyBackground.setBounds(0, 0, key.width, minHeight);
                } else {
                    keyBackground.setBounds(0, 0, key.width, key.height);
                }
            }
            canvas.translate(key.x + kbdPaddingLeft, key.y + kbdPaddingTop);
            if (yscale != 1.0f) {
                canvas.save();
                canvas.scale(1.0f, yscale);
            }
            if (mBackgroundAlpha != 255) {
                keyBackground.setAlpha(mBackgroundAlpha);
            }
            if (yscale == 1.0f) {
                drawKeyBackground(canvas, keyBackground,
                        key.x + kbdPaddingLeft, key.y + kbdPaddingTop, key.width, key.height);
            } else {
                keyBackground.draw(canvas);
            }
            if (active) drawActiveHighlight(canvas, keyBackground, key);
            if (yscale != 1.0f)  canvas.restore();

            boolean shouldDrawIcon = true;
            if (label != null) {
                // For characters, use large font. For labels like "Done", use small font.
                final int labelSize;
                if (label.length() > 1 && key.codes.length < 2) {
                    //Log.i(TAG, "mLabelTextSize=" + mLabelTextSize + " LatinIME.sKeyboardSettings.labelScale=" + LatinIME.sKeyboardSettings.labelScale);
                    labelSize = (int)(mLabelTextSize * mLabelScale);
                    paint.setTypeface(FUNCTION_LABEL_TYPEFACE);
                } else {
                    // arrow glyphs (cursor keys) are small in the font, bring them up to the icons
                    labelSize = (int)(mKeyTextSize * mLabelScale * (key.isCursor ? 1.15f : 1f));
                    paint.setTypeface(mKeyTextStyle);
                }
                paint.setFakeBoldText(key.isCursor);
                paint.setTextSize(labelSize);

                final int labelHeight = getLabelHeight(paint, labelSize);

                // Draw a drop shadow for the text
                paint.setShadowLayer(mShadowRadius, 0, 0, mShadowColor);

                // Hint labels stack in the top right corner: the shifted character, then the
                // first popup character under it
                String hint = key.getHintLabel(showHints7Bit(), showHintsAll());
                if (key.isShifted() && key.shiftLabel != null && !hint.equals("")
                        && hint.charAt(0) == key.shiftLabel.charAt(0)) {
                    hint = "";
                }
                String altHint = key.getAltHintLabel(showHints7Bit(), showHintsAll());
                final int hintTextSize = (int)(mKeyTextSize * 0.6 * mLabelScale);
                final int hintX = key.width - padding.right - mKeyHintInset;
                float hintWidth = 0;
                if (!hint.equals("") || !altHint.equals("")) {
                    paintHint.setTextSize(hintTextSize);
                    hintWidth = Math.max(paintHint.measureText(hint), paintHint.measureText(altHint));
                }
                if (!hint.equals("")) {
                    final int hintLabelHeight = getLabelHeight(paintHint, hintTextSize);
                    int baseline = padding.top + hintLabelHeight * 16/10;
                    if (Character.getType(hint.charAt(0)) == Character.NON_SPACING_MARK) {
                        drawDeadKeyLabel(canvas, hint, hintX, baseline, paintHint);
                    } else {
                        canvas.drawText(hint, hintX, baseline, paintHint);
                    }
                }
                if (!altHint.equals("")) {
                    final int hintLabelHeight = getLabelHeight(paintHint, hintTextSize);
                    int baseline = padding.top + hintLabelHeight * (hint.equals("") ? 16 : 30)/10;
                    if (Character.getType(altHint.charAt(0)) == Character.NON_SPACING_MARK) {
                        drawDeadKeyLabel(canvas, altHint, hintX, baseline, paintHint);
                    } else {
                        canvas.drawText(altHint, hintX, baseline, paintHint);
                    }
                }

                // Draw main key label, moved left on a narrow key so it doesn't touch the hints
                int centerX = (key.width + padding.left - padding.right) / 2;
                if (hintWidth > 0) {
                    final float halfLabel = paint.measureText(label) / 2;
                    final float rightLimit = hintX - hintWidth - hintTextSize * 0.15f;
                    if (centerX + halfLabel > rightLimit) {
                        centerX = (int) Math.max(padding.left + halfLabel, rightLimit - halfLabel);
                    }
                }
                final int centerY = (key.height + padding.top - padding.bottom) / 2;
                final float baseline = centerY
                        + labelHeight * KEY_LABEL_VERTICAL_ADJUSTMENT_FACTOR;
                if (key.subLabel != null) {
                    drawLabelWithSubLabel(canvas, label, key.subLabel.toString(), centerX, baseline,
                            key.width - padding.left - padding.right, paint);
                } else if (key.isDeadKey()) {
                    drawDeadKeyLabel(canvas, label, centerX, baseline, paint);
                } else {
                    canvas.drawText(label, centerX, baseline, paint);
                }
                if (key.isCursor) {
                    // poor man's bold - FIXME
                    // Turn off drop shadow
                    paint.setShadowLayer(0, 0, 0, 0);

                    canvas.drawText(label, centerX+0.5f, baseline, paint);
                    canvas.drawText(label, centerX-0.5f, baseline, paint);
                    canvas.drawText(label, centerX, baseline+0.5f, paint);
                    canvas.drawText(label, centerX, baseline-0.5f, paint);
                }

                // Turn off drop shadow
                paint.setShadowLayer(0, 0, 0, 0);

                // Usually don't draw icon if label is not null, but we draw icon for the number
                // hint and popup hint.
                shouldDrawIcon = shouldDrawLabelAndIcon(key);
            }
            Drawable icon = key.icon;
            if (icon != null && shouldDrawIcon) {
                // Special handing for the upper-right number hint icons
                final int drawableWidth;
                final int drawableHeight;
                final int drawableX;
                final int drawableY;
                if (shouldDrawIconFully(key)) {
                    drawableWidth = key.width;
                    drawableHeight = key.height;
                    drawableX = 0;
                    drawableY = NUMBER_HINT_VERTICAL_ADJUSTMENT_PIXEL;
                } else {
                    float scale = icon instanceof android.graphics.drawable.VectorDrawable
                            ? iconScale(mKeyTextSize, mLabelScale, getResources().getDisplayMetrics().density)
                            : 1f;
                    drawableWidth = Math.round(icon.getIntrinsicWidth() * scale);
                    drawableHeight = Math.round(icon.getIntrinsicHeight() * scale);
                    drawableX = (key.width + padding.left - padding.right - drawableWidth) / 2;
                    drawableY = (key.height + padding.top - padding.bottom - drawableHeight) / 2;
                }
                canvas.translate(drawableX, drawableY);
                icon.setBounds(0, 0, drawableWidth, drawableHeight);

                if (active) {
                    icon.setColorFilter(new PorterDuffColorFilter(mKeyActiveTextColor, PorterDuff.Mode.SRC_IN));
                    icon.draw(canvas);
                    icon.setColorFilter(null);
                } else if (iconColorFilter != null && mRecolorSymbols && keyTextColor != mKeyTextColor) {
                    // Function and accent keys of themes that colour their labels apart
                    icon.setColorFilter(new PorterDuffColorFilter(keyTextColor, PorterDuff.Mode.SRC_ATOP));
                    icon.draw(canvas);
                    icon.setColorFilter(null);
                } else if (iconColorFilter != null) {
                    // Re-color the icon to match the theme, and draw a shadow for it manually.
                    //
                    // This doesn't seem to look quite right, possibly a problem with using
                    // premultiplied icon images?

                    // Try EmbossMaskFilter, and/or offset? Configurable?
                    if (shadowColorFilter != null && mShadowRadius > 0) {
                        BlurMaskFilter shadowBlur = new BlurMaskFilter(mShadowRadius, BlurMaskFilter.Blur.OUTER);
                        Paint blurPaint = new Paint();
                        blurPaint.setMaskFilter(shadowBlur);
                        Bitmap tmpIcon = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888);
                        Canvas tmpCanvas = new Canvas(tmpIcon);
                        icon.draw(tmpCanvas);
                        int[] offsets = new int[2];
                        Bitmap shadowBitmap = tmpIcon.extractAlpha(blurPaint, offsets);
                        Paint shadowPaint = new Paint();
                        shadowPaint.setColorFilter(shadowColorFilter);
                        canvas.drawBitmap(shadowBitmap, offsets[0], offsets[1], shadowPaint);
                    }
                    icon.setColorFilter(iconColorFilter);
                    icon.draw(canvas);
                    icon.setColorFilter(null);
                } else {
                    icon.draw(canvas);                    
                }
                canvas.translate(-drawableX, -drawableY);
            }
            canvas.translate(-key.x - kbdPaddingLeft, -key.y - kbdPaddingTop);
        }
        //Log.i(TAG, "keysDrawn=" + keysDrawn);
        mInvalidatedKey = null;
        // Overlay a dark rectangle to dim the keyboard
        if (mMiniKeyboardVisible) {
            paint.setColor((int) (mBackgroundDimAmount * 0xFF) << 24);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }

        if (LatinIME.sKeyboardSettings.showTouchPos || DEBUG) {
            if (LatinIME.sKeyboardSettings.showTouchPos || mShowTouchPoints) {
                canvas.save();
                canvas.translate(kbdPaddingLeft, kbdPaddingTop); // trackers use key coordinates
                for (PointerTracker tracker : mPointerTrackers) {
                    int startX = tracker.getStartX();
                    int startY = tracker.getStartY();
                    int lastX = tracker.getLastX();
                    int lastY = tracker.getLastY();
                    paint.setAlpha(128);
                    paint.setColor(0xFFFF0000);
                    canvas.drawCircle(startX, startY, 3, paint);
                    canvas.drawLine(startX, startY, lastX, lastY, paint);
                    paint.setColor(0xFF0000FF);
                    canvas.drawCircle(lastX, lastY, 3, paint);
                    paint.setColor(0xFF00FF00);
                    canvas.drawCircle((startX + lastX) / 2, (startY + lastY) / 2, 2, paint);
                }
                canvas.restore();
            }
        }

        mDrawPending = false;
        mDirtyRect.setEmpty();
    }

    // TODO: clean up this method.
    private void dismissKeyPreview() {
        for (PointerTracker tracker : mPointerTrackers)
            tracker.updateKey(NOT_A_KEY);
        //Log.i(TAG, "dismissKeyPreview() for " + this);
        showPreview(NOT_A_KEY, null);
    }

    public void showPreview(int keyIndex, PointerTracker tracker) {
        int oldKeyIndex = mOldPreviewKeyIndex;
        mOldPreviewKeyIndex = keyIndex;
        final boolean isLanguageSwitchEnabled = (mKeyboard instanceof LatinKeyboard)
                && ((LatinKeyboard)mKeyboard).isLanguageSwitchEnabled();
        // We should re-draw popup preview when 1) we need to hide the preview, 2) we will show
        // the space key preview and 3) pointer moves off the space key to other letter key, we
        // should hide the preview of the previous key.
        final boolean hidePreviewOrShowSpaceKeyPreview = (tracker == null)
                || tracker.isSpaceKey(keyIndex) || tracker.isSpaceKey(oldKeyIndex);
        // If key changed and preview is on or the key is space (language switch is enabled)
        if (oldKeyIndex != keyIndex
                && (mShowPreview
                        || (hidePreviewOrShowSpaceKeyPreview && isLanguageSwitchEnabled))) {
            if (keyIndex == NOT_A_KEY) {
                mHandler.cancelPopupPreview();
                mHandler.dismissPreview(mDelayAfterPreview);
            } else if (tracker != null) {
                int delay = mShowPreview ? mDelayBeforePreview : mDelayBeforeSpacePreview;
                mHandler.popupPreview(delay, keyIndex, tracker);
            }
        }
    }

    private void showKey(final int keyIndex, PointerTracker tracker) {
        Key key = tracker.getKey(keyIndex);
        if (key == null)
            return;
        //Log.i(TAG, "showKey() for " + this);
        // Should not draw hint icon in key preview
        Drawable icon = key.icon;
        if (icon != null && !shouldDrawLabelAndIcon(key)) {
            Drawable preview = key.iconPreview != null ? key.iconPreview : icon;
            if (preview instanceof LatinKeyboard.SlidingLocaleDrawable) {
                // Drawn only in this bubble: recolour it in place (null keeps it as drawn)
                preview.setColorFilter(mPreviewTextColor == 0 ? null
                        : new PorterDuffColorFilter(mPreviewTextColor, PorterDuff.Mode.SRC_IN));
            } else if (mPreviewTextColor != 0 && preview.getConstantState() != null) {
                // The preview icons are white: a copy in the theme's label colour, the
                // key keeps drawing its own
                Drawable tinted = preview.getConstantState().newDrawable(getResources()).mutate();
                tinted.setBounds(preview.getBounds());
                tinted.setColorFilter(new PorterDuffColorFilter(mPreviewTextColor,
                        PorterDuff.Mode.SRC_IN));
                preview = tinted;
            }
            mPreviewText.setCompoundDrawables(null, null, null, preview);
            mPreviewText.setText(null);
        } else {
            mPreviewText.setCompoundDrawables(null, null, null, null);
            mPreviewText.setText(key.getCaseLabel());
            if (key.label.length() > 1 && key.codes.length < 2) {
                mPreviewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mKeyTextSize);
                mPreviewText.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                mPreviewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mPreviewTextSizeLarge);
                mPreviewText.setTypeface(mKeyTextStyle);
            }
        }
        mPreviewText.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int popupWidth = Math.max(mPreviewText.getMeasuredWidth(), key.width
                + mPreviewText.getPaddingLeft() + mPreviewText.getPaddingRight());
        final int popupHeight = mPreviewHeight;
        LayoutParams lp = mPreviewText.getLayoutParams();
        if (lp != null) {
            lp.width = popupWidth;
            lp.height = popupHeight;
        }

        // Keys are drawn shifted by the view's padding (the camera cutout in landscape)
        int popupPreviewX = getPaddingLeft() + key.x - (popupWidth - key.width) / 2;
        int popupPreviewY = getPaddingTop() + key.y - popupHeight + mPreviewOffset;

        mHandler.cancelDismissPreview();
        // Measured every time: the suggestion strip above the keyboard shows and hides,
        // moving the keyboard inside its window
        if (mOffsetInWindow == null) mOffsetInWindow = new int[2];
        getLocationInWindow(mOffsetInWindow);
        mOffsetInWindow[0] += mPopupPreviewOffsetX; // Offset may be zero
        mOffsetInWindow[1] += mPopupPreviewOffsetY; // Offset may be zero
        int[] windowLocation = new int[2];
        getLocationOnScreen(windowLocation);
        mWindowY = windowLocation[1];
        // Set the preview background state.
        // Retrieve and cache the popup keyboard if any.
        boolean hasPopup = (getLongPressKeyboard(key) != null);
        // Set background manually, the StateListDrawable doesn't work.
        int previewBackground = mPreviewBackground != 0 ? mPreviewBackground
                : hasPopup ? R.drawable.keyboard_key_feedback_more_background
                : R.drawable.keyboard_key_feedback_background;
        mPreviewText.setBackgroundDrawable(getResources().getDrawable(previewBackground));
        popupPreviewX += mOffsetInWindow[0];
        popupPreviewY += mOffsetInWindow[1];

        // If the popup cannot be shown above the key, put it on the side
        if (popupPreviewY + mWindowY < 0) {
            // If the key you're pressing is on the left side of the keyboard, show the popup on
            // the right, offset by enough to see at least one key to the left/right.
            if (getPaddingLeft() + key.x + key.width <= getWidth() / 2) {
                popupPreviewX += (int) (key.width * 2.5);
            } else {
                popupPreviewX -= (int) (key.width * 2.5);
            }
            popupPreviewY += popupHeight;
        }

        if (mPreviewPopup.isShowing()) {
            mPreviewPopup.update(popupPreviewX, popupPreviewY, popupWidth, popupHeight);
        } else {
            mPreviewPopup.setWidth(popupWidth);
            mPreviewPopup.setHeight(popupHeight);
            mPreviewPopup.showAtLocation(mMiniKeyboardParent, Gravity.NO_GRAVITY,
                    popupPreviewX, popupPreviewY);
        }
        // Record popup preview position to display mini-keyboard later at the same positon
        mPopupPreviewDisplayedY = popupPreviewY;
        mPreviewText.setVisibility(VISIBLE);
    }

    /**
     * Requests a redraw of the entire keyboard. Calling {@link #invalidate} is not sufficient
     * because the keyboard renders the keys to an off-screen buffer and an invalidate() only
     * draws the cached buffer.
     * @see #invalidateKey(Key)
     */
    public void invalidateAllKeys() {
        mDirtyRect.union(0, 0, getWidth(), getHeight());
        mDrawPending = true;
        invalidate();
    }

    /**
     * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only
     * one key is changing it's content. Any changes that affect the position or size of the key
     * may not be honored.
     * @param key key in the attached {@link Keyboard}.
     * @see #invalidateAllKeys
     */
    public void invalidateKey(Key key) {
        if (key == null)
            return;
        mInvalidatedKey = key;
        // TODO we should clean up this and record key's region to use in onBufferDraw.
        mDirtyRect.union(key.x + getPaddingLeft(), key.y + getPaddingTop(),
                key.x + key.width + getPaddingLeft(), key.y + key.height + getPaddingTop());
        //onBufferDraw();
        invalidate(key.x + getPaddingLeft(), key.y + getPaddingTop(),
                key.x + key.width + getPaddingLeft(), key.y + key.height + getPaddingTop());
    }

    private boolean openPopupIfRequired(int keyIndex, PointerTracker tracker) {
        // Check if we have a popup layout specified first.
        if (mPopupLayout == 0) {
            return false;
        }

        Key popupKey = tracker.getKey(keyIndex);
        if (popupKey == null)
            return false;
        if (tracker.isInSlidingKeyInput())
            return false;
        boolean result = onLongPress(popupKey);
        if (result) {
            dismissKeyPreview();
            mMiniKeyboardTrackerId = tracker.mPointerId;
            // Mark this tracker "already processed" and remove it from the pointer queue
            tracker.setAlreadyProcessed();
            mPointerQueue.remove(tracker);
        }
        return result;
    }

    private void inflateMiniKeyboardContainer() {
        //Log.i(TAG, "inflateMiniKeyboardContainer(), mPopupLayout=" + mPopupLayout + " from " + this);
        LayoutInflater inflater = (LayoutInflater)getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View container = inflater.inflate(mPopupLayout, null);

        mMiniKeyboard =
                (LatinKeyboardBaseView)container.findViewById(R.id.LatinKeyboardBaseView);
        mMiniKeyboard.setOnKeyboardActionListener(new OnKeyboardActionListener() {
            public void onKey(int primaryCode, int[] keyCodes, int x, int y) {
                mKeyboardActionListener.onKey(primaryCode, keyCodes, x, y);
                dismissPopupKeyboard();
            }

            public void onText(CharSequence text) {
                mKeyboardActionListener.onText(text);
                dismissPopupKeyboard();
            }

            public void onCancel() {
                mKeyboardActionListener.onCancel();
                dismissPopupKeyboard();
            }

            public boolean swipeLeft() {
                return false;
            }
            public boolean swipeRight() {
                return false;
            }
            public boolean swipeUp() {
                return false;
            }
            public boolean swipeDown() {
                return false;
            }
            public void onPress(int primaryCode) {
                mKeyboardActionListener.onPress(primaryCode);
            }
            public void onRelease(int primaryCode) {
                mKeyboardActionListener.onRelease(primaryCode);
            }
        });
        // Override default ProximityKeyDetector.
        mMiniKeyboard.mKeyDetector = new MiniKeyboardKeyDetector(mMiniKeyboardSlideAllowance);
        // Remove gesture detector on mini-keyboard
        mMiniKeyboard.mGestureDetector = null;

        mMiniKeyboard.setPopupParent(this);

        mMiniKeyboardContainer = container;
    }

    private static boolean isOneRowKeys(List<Key> keys) {
        if (keys.size() == 0) return false;
        final int edgeFlags = keys.get(0).edgeFlags;
        // HACK: The first key of mini keyboard which was inflated from xml and has multiple rows,
        // does not have both top and bottom edge flags on at the same time.  On the other hand,
        // the first key of mini keyboard that was created with popupCharacters must have both top
        // and bottom edge flags on.
        // When you want to use one row mini-keyboard from xml file, make sure that the row has
        // both top and bottom edge flags set.
        return (edgeFlags & Keyboard.EDGE_TOP) != 0 && (edgeFlags & Keyboard.EDGE_BOTTOM) != 0;
    }

    private Keyboard getLongPressKeyboard(Key popupKey) {
        final WeakHashMap<Key, Keyboard> cache;
        if (popupKey.isDistinctCaps()) {
            cache = mMiniKeyboardCacheCaps;
        } else if (popupKey.isShifted()) {
            cache = mMiniKeyboardCacheShift;
        } else {
            cache = mMiniKeyboardCacheMain;
        }
        Keyboard kbd = cache.get(popupKey);
        if (kbd == null) {
            kbd = popupKey.getPopupKeyboard(getContext(), getPaddingLeft() + getPaddingRight());
            if (kbd != null) cache.put(popupKey, kbd);
        }
        //Log.i(TAG, "getLongPressKeyboard returns " + kbd + " for " + popupKey);
        return kbd;
    }

    /**
     * Called when a key is long pressed. By default this will open any popup keyboard associated
     * with this key through the attributes popupLayout and popupCharacters.
     * @param popupKey the key that was long pressed
     * @return true if the long press is handled, false otherwise. Subclasses should call the
     * method on the base class if the subclass doesn't wish to handle the call.
     */
    protected boolean onLongPress(Key popupKey) {
        // TODO if popupKey.popupCharacters has only one letter, send it as key without opening
        // mini keyboard.

        if (mPopupLayout == 0) return false; // No popups wanted

        Keyboard kbd = getLongPressKeyboard(popupKey);
        //Log.i(TAG, "onLongPress, kbd=" + kbd);
        if (kbd == null) return false;

        if (mMiniKeyboardContainer == null) {
            inflateMiniKeyboardContainer();
        }
        if (mMiniKeyboard == null) return false;
        mMiniKeyboard.setKeyboard(kbd);
        mMiniKeyboardContainer.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));

        // Measured every time, the suggestion strip moves the keyboard (see showKey)
        if (mWindowOffset == null) mWindowOffset = new int[2];
        getLocationInWindow(mWindowOffset);

        // Get width of a key in the mini popup keyboard = "miniKeyWidth".
        // On the other hand, "popupKey.width" is width of the pressed key on the main keyboard.
        // We adjust the position of mini popup keyboard with the edge key in it:
        //  a) When we have the leftmost key in popup keyboard directly above the pressed key
        //     Right edges of both keys should be aligned for consistent default selection
        //  b) When we have the rightmost key in popup keyboard directly above the pressed key
        //     Left edges of both keys should be aligned for consistent default selection

        final List<Key> miniKeys = mMiniKeyboard.getKeyboard().getKeys();
        final int miniKeyWidth = miniKeys.size() > 0 ? miniKeys.get(0).width : 0;

        int popupX = popupKey.x + mWindowOffset[0];
        popupX += getPaddingLeft();
        if (shouldAlignLeftmost(popupKey)) {
            popupX += popupKey.width - miniKeyWidth;  // adjustment for a) described above
            popupX -= mMiniKeyboardContainer.getPaddingLeft();
        } else {
            popupX += miniKeyWidth;  // adjustment for b) described above
            popupX -= mMiniKeyboardContainer.getMeasuredWidth();
            popupX += mMiniKeyboardContainer.getPaddingRight();
        }
        int popupY = popupKey.y + mWindowOffset[1];
        popupY += getPaddingTop();
        popupY -= mMiniKeyboardContainer.getMeasuredHeight();
        popupY += mMiniKeyboardContainer.getPaddingBottom();
        final int x = popupX;
        // Right above the pressed key; the key preview's height used to lift one-row popups
        // far above the finger
        final int y = popupY;

        int adjustedX = x;
        if (x < 0) {
            adjustedX = 0;
        } else if (x > (getMeasuredWidth() - mMiniKeyboardContainer.getMeasuredWidth())) {
            adjustedX = getMeasuredWidth() - mMiniKeyboardContainer.getMeasuredWidth();
        }
        // Log.i(TAG, "x=" + x + " y=" + y + " adjustedX=" + adjustedX + " getMeasuredWidth()=" + getMeasuredWidth());
        mMiniKeyboardOriginX = adjustedX + mMiniKeyboardContainer.getPaddingLeft() - mWindowOffset[0];
        mMiniKeyboardOriginY = y + mMiniKeyboardContainer.getPaddingTop() - mWindowOffset[1];
        mMiniKeyboard.setPopupOffset(adjustedX, y);
        mMiniKeyboard.setShiftState(getShiftState());
        // Mini keyboard needs no pop-up key preview displayed.
        mMiniKeyboard.setPreviewEnabled(false);
        mMiniKeyboardPopup.setContentView(mMiniKeyboardContainer);
        mMiniKeyboardPopup.setWidth(mMiniKeyboardContainer.getMeasuredWidth());
        mMiniKeyboardPopup.setHeight(mMiniKeyboardContainer.getMeasuredHeight());
        //Log.i(TAG, "About to show popup " + mMiniKeyboardPopup + " from " + this);
        mMiniKeyboardPopup.showAtLocation(this, Gravity.NO_GRAVITY, adjustedX, y);
        mMiniKeyboardVisible = true;

        // Inject down event on the key to mini keyboard.
        long eventTime = SystemClock.uptimeMillis();
        mMiniKeyboardPopupTime = eventTime;
        MotionEvent downEvent = generateMiniKeyboardMotionEvent(MotionEvent.ACTION_DOWN,
                getPaddingLeft() + popupKey.x + popupKey.width / 2,
                getPaddingTop() + popupKey.y + popupKey.height / 2, eventTime);
        mMiniKeyboard.onTouchEvent(downEvent);
        downEvent.recycle();

        invalidateAllKeys();
        return true;
    }

    private boolean shouldDrawIconFully(Key key) {
        return isNumberAtEdgeOfPopupChars(key) || isLatinF1Key(key)
                || LatinKeyboard.hasPuncOrSmileysPopup(key);
    }

    private boolean shouldDrawLabelAndIcon(Key key) {
        // isNumberAtEdgeOfPopupChars(key) ||
        return isNonMicLatinF1Key(key)
                || LatinKeyboard.hasPuncOrSmileysPopup(key);
    }

    private boolean shouldAlignLeftmost(Key key) {
        return !key.popupReversed;
    }

    private boolean isLatinF1Key(Key key) {
        return (mKeyboard instanceof LatinKeyboard) && ((LatinKeyboard)mKeyboard).isF1Key(key);
    }

    private boolean isNonMicLatinF1Key(Key key) {
        return isLatinF1Key(key) && key.label != null;
    }

    private static boolean isNumberAtEdgeOfPopupChars(Key key) {
        return isNumberAtLeftmostPopupChar(key) || isNumberAtRightmostPopupChar(key);
    }

    /* package */ static boolean isNumberAtLeftmostPopupChar(Key key) {
        if (key.popupCharacters != null && key.popupCharacters.length() > 0
                && isAsciiDigit(key.popupCharacters.charAt(0))) {
            return true;
        }
        return false;
    }

    /* package */ static boolean isNumberAtRightmostPopupChar(Key key) {
        if (key.popupCharacters != null && key.popupCharacters.length() > 0
                && isAsciiDigit(key.popupCharacters.charAt(key.popupCharacters.length() - 1))) {
            return true;
        }
        return false;
    }

    private static boolean isAsciiDigit(char c) {
        return (c < 0x80) && Character.isDigit(c);
    }

    private MotionEvent generateMiniKeyboardMotionEvent(int action, int x, int y, long eventTime) {
        return MotionEvent.obtain(mMiniKeyboardPopupTime, eventTime, action,
                    x - mMiniKeyboardOriginX, y - mMiniKeyboardOriginY, 0);
    }

    /*package*/ boolean enableSlideKeyHack() {
        return false;
    }
    
    private PointerTracker getPointerTracker(final int id) {
        final ArrayList<PointerTracker> pointers = mPointerTrackers;
        final Key[] keys = mKeys;
        final OnKeyboardActionListener listener = mKeyboardActionListener;

        // Create pointer trackers until we can get 'id+1'-th tracker, if needed.
        for (int i = pointers.size(); i <= id; i++) {
            final PointerTracker tracker =
                new PointerTracker(i, mHandler, mKeyDetector, this, getResources(), enableSlideKeyHack());
            if (keys != null)
                tracker.setKeyboard(keys, mKeyHysteresisDistance);
            if (listener != null)
                tracker.setOnKeyboardActionListener(listener);
            pointers.add(tracker);
        }

        return pointers.get(id);
    }

    public boolean isInSlidingKeyInput() {
        if (mMiniKeyboardVisible) {
            return mMiniKeyboard.isInSlidingKeyInput();
        } else {
            return mPointerQueue.isInSlidingKeyInput();
        }
    }

    public int getPointerCount() {
        return mOldPointerCount;
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        return onTouchEvent(me, false);
    }

    public boolean onTouchEvent(MotionEvent me, boolean continuing) {
        final int action = me.getActionMasked();
        final int pointerCount = me.getPointerCount();
        final int oldPointerCount = mOldPointerCount;
        mOldPointerCount = pointerCount;
        
        // TODO: cleanup this code into a multi-touch to single-touch event converter class?
        // If the device does not have distinct multi-touch support panel, ignore all multi-touch
        // events except a transition from/to single-touch.
        if (!mHasDistinctMultitouch && pointerCount > 1 && oldPointerCount > 1) {
            return true;
        }

        // Track the last few movements to look for spurious swipes.
        mSwipeTracker.addMovement(me);

        // Gesture detector must be enabled only when mini-keyboard is not on the screen.
        if (!mMiniKeyboardVisible
                && mGestureDetector != null && mGestureDetector.onTouchEvent(me)) {
            dismissKeyPreview();
            mHandler.cancelKeyTimers();
            return true;
        }

        final long eventTime = me.getEventTime();
        final int index = me.getActionIndex();
        final int id = me.getPointerId(index);
        // Keys are drawn shifted by the view's padding (the camera cutout in landscape),
        // while key coordinates start at 0: take the padding off before finding the key
        final int padLeft = getPaddingLeft();
        final int padTop = getPaddingTop();
        final int x = (int)me.getX(index) - padLeft;
        final int y = (int)me.getY(index) - padTop;

        // Needs to be called after the gesture detector gets a turn, as it may have
        // displayed the mini keyboard
        if (mMiniKeyboardVisible) {
            final int miniKeyboardPointerIndex = me.findPointerIndex(mMiniKeyboardTrackerId);
            if (miniKeyboardPointerIndex >= 0 && miniKeyboardPointerIndex < pointerCount) {
                final int miniKeyboardX = (int)me.getX(miniKeyboardPointerIndex);
                final int miniKeyboardY = (int)me.getY(miniKeyboardPointerIndex);
                MotionEvent translated = generateMiniKeyboardMotionEvent(action,
                        miniKeyboardX, miniKeyboardY, eventTime);
                mMiniKeyboard.onTouchEvent(translated);
                translated.recycle();
            }
            return true;
        }

        if (mHandler.isInKeyRepeat()) {
            // It will keep being in the key repeating mode while the key is being pressed.
            if (action == MotionEvent.ACTION_MOVE) {
                return true;
            }
            final PointerTracker tracker = getPointerTracker(id);
            // Key repeating timer will be canceled if 2 or more keys are in action, and current
            // event (UP or DOWN) is non-modifier key.
            if (pointerCount > 1 && !tracker.isModifier()) {
                mHandler.cancelKeyRepeatTimer();
            }
            // Up event will pass through.
        }

        // TODO: cleanup this code into a multi-touch to single-touch event converter class?
        // Translate mutli-touch event to single-touch events on the device that has no distinct
        // multi-touch panel.
        if (!mHasDistinctMultitouch) {
            // Use only main (id=0) pointer tracker.
            PointerTracker tracker = getPointerTracker(0);
            if (pointerCount == 1 && oldPointerCount == 2) {
                // Multi-touch to single touch transition.
                // Send a down event for the latest pointer.
                tracker.onDownEvent(x, y, eventTime);
            } else if (pointerCount == 2 && oldPointerCount == 1) {
                // Single-touch to multi-touch transition.
                // Send an up event for the last pointer.
                tracker.onUpEvent(tracker.getLastX(), tracker.getLastY(), eventTime);
            } else if (pointerCount == 1 && oldPointerCount == 1) {
                tracker.onTouchEvent(action, x, y, eventTime);
            } else {
                Log.w(TAG, "Unknown touch panel behavior: pointer count is " + pointerCount
                        + " (old " + oldPointerCount + ")");
            }
            if (continuing)
                tracker.setSlidingKeyInputState(true);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!mIgnoreMove) {
                for (int i = 0; i < pointerCount; i++) {
                    PointerTracker tracker = getPointerTracker(me.getPointerId(i));
                    tracker.onMoveEvent((int)me.getX(i) - padLeft, (int)me.getY(i) - padTop, eventTime);
                }
            }
        } else {
            PointerTracker tracker = getPointerTracker(id);
            switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                mIgnoreMove = false;
                onDownEvent(tracker, x, y, eventTime);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mIgnoreMove = false;
                onUpEvent(tracker, x, y, eventTime);
                break;
            case MotionEvent.ACTION_CANCEL:
                onCancelEvent(tracker, x, y, eventTime);
                break;
            }
            if (continuing)
                tracker.setSlidingKeyInputState(true);
        }

        return true;
    }

    private void onDownEvent(PointerTracker tracker, int x, int y, long eventTime) {
        if (tracker.isOnModifierKey(x, y)) {
            // Before processing a down event of modifier key, all pointers already being tracked
            // should be released.
            mPointerQueue.releaseAllPointersExcept(null, eventTime);
        }
        tracker.onDownEvent(x, y, eventTime);
        mPointerQueue.add(tracker);
    }

    private void onUpEvent(PointerTracker tracker, int x, int y, long eventTime) {
        if (tracker.isModifier()) {
            // Before processing an up event of modifier key, all pointers already being tracked
            // should be released.
            mPointerQueue.releaseAllPointersExcept(tracker, eventTime);
        } else {
            int index = mPointerQueue.lastIndexOf(tracker);
            if (index >= 0) {
                mPointerQueue.releaseAllPointersOlderThan(tracker, eventTime);
            } else {
                Log.w(TAG, "onUpEvent: corresponding down event not found for pointer "
                        + tracker.mPointerId);
            }
        }
        tracker.onUpEvent(x, y, eventTime);
        mPointerQueue.remove(tracker);
    }

    private void onCancelEvent(PointerTracker tracker, int x, int y, long eventTime) {
        tracker.onCancelEvent(x, y, eventTime);
        mPointerQueue.remove(tracker);
    }

    protected boolean swipeRight() {
        return mKeyboardActionListener.swipeRight();
    }

    protected boolean swipeLeft() {
        return mKeyboardActionListener.swipeLeft();
    }

    /*package*/ boolean swipeUp() {
        return mKeyboardActionListener.swipeUp();
    }

    protected boolean swipeDown() {
        return mKeyboardActionListener.swipeDown();
    }

    public void closing() {
        Log.i(TAG, "closing " + this);
        if (mPreviewPopup != null) mPreviewPopup.dismiss();
        mHandler.cancelAllMessages();

        dismissPopupKeyboard();
        //mMiniKeyboardContainer = null; // TODO: destroy/recycle the views?
        //mMiniKeyboard = null;

        // TODO(klausw): use a global bitmap repository, keeping two bitmaps permanently -
        // one for main and one for popup.
        //
        // Allow having the backup bitmap be bigger than the canvas needed, only shrinking in rare cases -
        // for example if reducing the size of the main keyboard.
        //mBuffer = null;
        //mCanvas = null;
        mMiniKeyboardCacheMain.clear();
        mMiniKeyboardCacheShift.clear();
        mMiniKeyboardCacheCaps.clear();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        //Log.i(TAG, "onDetachedFromWindow() for " + this);
        closing();
    }

    protected boolean popupKeyboardIsShowing() {
        return mMiniKeyboardPopup != null && mMiniKeyboardPopup.isShowing();
    }

    protected void dismissPopupKeyboard() {
        if (mMiniKeyboardPopup != null) {
            //Log.i(TAG, "dismissPopupKeyboard() " + mMiniKeyboardPopup + " showing=" + mMiniKeyboardPopup.isShowing());
            if (mMiniKeyboardPopup.isShowing()) {
                mMiniKeyboardPopup.dismiss();
            }
            mMiniKeyboardVisible = false;
            mPointerQueue.releaseAllPointersExcept(null, 0); // https://github.com/klausw/hackerskeyboard/issues/477
            invalidateAllKeys();
        }
    }

    public boolean handleBack() {
        if (mMiniKeyboardPopup != null && mMiniKeyboardPopup.isShowing()) {
            dismissPopupKeyboard();
            return true;
        }
        return false;
    }
}
