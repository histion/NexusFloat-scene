package com.jj.nexusfloat.frame;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

/**
 * 帧记录的悬浮控制球。
 *
 * 为什么走 SYSTEM_ALERT_WINDOW 而不是画进 SystemUI：记录采样跑在本模块自己的
 * 进程里（取数器、数据库都在这儿），控制球跟着采样走才是同一个生命周——点球
 * 直接切换记录状态，不经过任何跨进程通道。代价是要「显示在其他应用上层」权限，
 * 本模块有 root，没有授权时先试着用 appops 静默授一次，不行再把用户领到设置页。
 *
 * 交互（全部由自己处理，不依赖系统手势）：
 * - 点按：未记录 → 开始记录；记录中 → 停止并保存
 * - 拖动：挪位置，松手后位置存 prefs，下次出现还在原地
 * - 长按：收掉悬浮球并停服务（记录中的会先保存）
 *
 * 外观：一个圆角长方形（胶囊，44dp 宽 / 22dp 高）。未记录时棕黄
 * （Constants.Frame.BUBBLE_IDLE_COLOR）并写小字 fps，记录中变红、显示实时
 * 帧率整数并带一圈沿矩形描边的闪烁提示环——用户在游戏里瞄一眼就知道还在不在记。
 */
public final class FrameBubble {

    private static final String TAG = "FrameBubble";
    private static final String KEY_X = "frame_bubble_x";
    private static final String KEY_Y = "frame_bubble_y";

    public interface Callback {
        /** 点按：切换记录状态 */
        void onTap();
        /** 长按：收掉悬浮球（记录中的先保存） */
        void onDismiss();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BallView view;
    private Callback callback;
    private boolean showing;

    public FrameBubble(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    // ======================= 权限 =======================

    /** 悬浮窗权限有没有就位 */
    public static boolean canShow(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * 尽力把悬浮窗权限弄到位。
     *
     * 本模块的核心能力就是 root，所以先试 `appops set` 静默授权（大多数 ROM 通）；
     * 不通才让调用方把用户领到设置页手动开。返回 true 表示现在就能显示。
     */
    public static boolean ensurePermission(Context context) {
        if (Settings.canDrawOverlays(context)) {
            return true;
        }
        try {
            RootShell.get().exec("appops set " + context.getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow");
        } catch (Throwable t) {
            LogUtils.w(TAG + " appops grant failed", t);
        }
        return Settings.canDrawOverlays(context);
    }

    // ======================= 显隐 =======================

    /** 显示悬浮球。已经显示就只换回调 */
    public void show(Callback callback) {
        this.callback = callback;
        if (showing) {
            return;
        }
        try {
            view = new BallView(context);
            WindowManager.LayoutParams lp = buildParams();
            windowManager.addView(view, lp);
            showing = true;
        } catch (Throwable t) {
            // 权限被 ROM 收回、或者窗口 token 失效都会走到这儿；
            // 悬浮球失败不该拖垮记录本身，只把状态记下来
            LogUtils.w(TAG + " show failed", t);
            view = null;
            showing = false;
        }
    }

    public void hide() {
        main.removeCallbacksAndMessages(null);
        if (!showing || view == null) {
            showing = false;
            view = null;
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
        showing = false;
        view = null;
    }

    public boolean isShowing() {
        return showing;
    }

    /** 采样回调进来刷新球面：记录中的显示实时帧率 */
    public void update(final boolean recording, final float fps) {
        if (view == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (view != null) {
                    view.setState(recording, fps);
                }
            }
        });
    }

    private WindowManager.LayoutParams buildParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(Constants.Frame.BUBBLE_WIDTH_DP),
                dp(Constants.Frame.BUBBLE_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] pos = loadPosition();
        lp.x = pos[0];
        lp.y = pos[1];
        return lp;
    }

    /** 上次停留的位置；没存过就放右侧偏中的位置，别正好压在游戏血条上 */
    private int[] loadPosition() {
        SharedPreferences p = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        int[] out = new int[2];
        out[0] = p.getInt(KEY_X, Integer.MIN_VALUE);
        out[1] = p.getInt(KEY_Y, Integer.MIN_VALUE);
        if (out[0] == Integer.MIN_VALUE || out[1] == Integer.MIN_VALUE) {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            out[0] = dm.widthPixels - dp(Constants.Frame.BUBBLE_WIDTH_DP) - dp(12);
            out[1] = (int) (dm.heightPixels * 0.28f);
        }
        return out;
    }

    private void savePosition(int x, int y) {
        context.getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    // ======================= 球本体 =======================

    /**
     * 自己画的胶囊悬浮球：不用布局文件也不用图标，一个 Canvas 全搞定。
     * 四十几 dp 的小东西引一套 Compose/布局 inflater 纯属浪费。
     */
    private final class BallView extends View {

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean recording;
        private float fps;
        private boolean ringOn = true;

        /** 手势状态 */
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;
        private boolean longPressFired;

        private final Runnable pulse = new Runnable() {
            @Override
            public void run() {
                ringOn = !ringOn;
                invalidate();
                if (recording) {
                    main.postDelayed(this, Constants.Frame.BUBBLE_PULSE_MS);
                }
            }
        };

        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                if (callback != null) {
                    callback.onDismiss();
                }
            }
        };

        BallView(Context c) {
            super(c);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        void setState(boolean recording, float fps) {
            boolean started = recording && !this.recording;
            this.recording = recording;
            this.fps = fps;
            main.removeCallbacks(pulse);
            if (recording) {
                // 只有「开始」那一下才重置闪烁相位，中途的帧率刷新别把节奏打断
                if (started) {
                    ringOn = true;
                }
                main.postDelayed(pulse, Constants.Frame.BUBBLE_PULSE_MS);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            // 胶囊：圆角取半高，宽 44dp / 高 22dp 就是圆角 11dp 的长方形
            float corner = h / 2f;
            // 背景往里收一点，别让填充色贴到窗口边缘被裁
            float bgInset = dp(2);

            bg.setStyle(Paint.Style.FILL);
            bg.setColor(recording
                    ? Constants.Frame.BUBBLE_RECORDING_COLOR
                    : Constants.Frame.BUBBLE_IDLE_COLOR);
            canvas.drawRoundRect(
                    bgInset, bgInset, w - bgInset, h - bgInset,
                    corner - bgInset, corner - bgInset, bg);

            if (recording && ringOn) {
                ring.setColor(Constants.Frame.BUBBLE_RECORDING_COLOR);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(2));
                // 描边沿矩形外沿走一圈；半描边内收避免被窗口边缘裁掉
                float ringInset = dp(1);
                canvas.drawRoundRect(
                        ringInset, ringInset, w - ringInset, h - ringInset,
                        corner - ringInset, corner - ringInset, ring);
            }

            float fontSize;
            String label;
            if (recording) {
                fontSize = fps >= 100f ? dp(10) : dp(12);
                label = fps >= 0f ? String.valueOf(Math.round(fps)) : "--";
            } else {
                // 未记录：小字 fps（白色），说明这是个帧率球
                fontSize = dp(11);
                label = "fps";
            }
            text.setTextSize(fontSize);
            float cx = w / 2f;
            float cy = h / 2f;
            float textY = cy - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(label, cx, textY, text);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = getWindowParams().x;
                    startY = getWindowParams().y;
                    dragging = false;
                    longPressFired = false;
                    main.postDelayed(longPress, Constants.Frame.BUBBLE_LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy)
                            > Constants.Frame.BUBBLE_TOUCH_SLOP_PX) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        WindowManager.LayoutParams lp = getWindowParams();
                        if (lp != null) {
                            lp.x = clampX(startX + (int) dx);
                            lp.y = Math.max(0, startY + (int) dy);
                            try {
                                windowManager.updateViewLayout(BallView.this, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (!dragging && !longPressFired
                            && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (callback != null) {
                            callback.onTap();
                        }
                        return true;
                    }
                    if (dragging) {
                        WindowManager.LayoutParams lp = getWindowParams();
                        if (lp != null) {
                            savePosition(lp.x, lp.y);
                        }
                    }
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        private WindowManager.LayoutParams getWindowParams() {
            try {
                return (WindowManager.LayoutParams) getLayoutParams();
            } catch (Throwable t) {
                return null;
            }
        }

        /** 横向别拖出屏幕：左右各留半个球的余量 */
        private int clampX(int x) {
            int max = getResources().getDisplayMetrics().widthPixels - getWidth();
            return Math.max(0, Math.min(max, x));
        }
    }
}
