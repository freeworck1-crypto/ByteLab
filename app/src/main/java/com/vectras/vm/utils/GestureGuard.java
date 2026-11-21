package com.vectras.vm.utils;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps system gestures inside the virtual machine.
 *
 * While the VM screen is open (LOCKED):
 *  - the status bar / navigation bar stay hidden and an edge swipe only peeks them, it does not leave the app;
 *  - the back-gesture edges are excluded so a swipe from the side goes to the guest;
 *  - swipes inside the VM go to the guest, not to Android (no recents, no notification shade by accident).
 *
 * To reach the phone again: press and HOLD one finger on any screen edge for 5 seconds (no sliding).
 * The phone's gestures/bars are released for 10 seconds, then locked again.
 *
 * Note: Android reserves the very bottom "home" swipe of some phones; the immersive mode above makes the
 * first swipe only reveal the bar. Nothing here roots or changes the phone — it is ordinary window flags.
 */
public final class GestureGuard {
    public static final long HOLD_MS = 5000;
    public static final long RELEASE_MS = 10000;
    private static final int EDGE_DP = 28;
    private static final int MOVE_SLOP_DP = 12;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private boolean released = false;
    private boolean hideBars = true;
    private boolean holding = false;
    private float downX, downY;
    private int holdPointerId = -1;

    private final Runnable releaseRunnable = this::release;
    private final Runnable relockRunnable = this::lock;

    public GestureGuard(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /** false = leave the bars alone (the screen manages them itself) and only guard edge gestures. */
    public void setHideBars(boolean hide) { hideBars = hide; }

    /** Call from onCreate/onResume/onWindowFocusChanged(true). */
    public void lock() {
        released = false;
        handler.removeCallbacks(relockRunnable);
        Window w = activity.getWindow();
        if (w == null) return;
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (hideBars) c.hide(WindowInsetsCompat.Type.systemBars());
        } catch (Throwable ignored) { }
        applyExclusion(true);
    }

    private void release() {
        holding = false;
        released = true;
        Window w = activity.getWindow();
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            c.show(WindowInsetsCompat.Type.systemBars());
        } catch (Throwable ignored) { }
        applyExclusion(false);
        Toast.makeText(activity, "Phone gestures unlocked for 10 s", Toast.LENGTH_SHORT).show();
        handler.removeCallbacks(relockRunnable);
        handler.postDelayed(relockRunnable, RELEASE_MS);
    }

    /** Feed every touch event (from Activity.dispatchTouchEvent) before passing it on. */
    public void onTouch(MotionEvent e) {
        if (released) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isEdge(e.getX(), e.getY())) {
                    holding = true;
                    holdPointerId = e.getPointerId(0);
                    downX = e.getX();
                    downY = e.getY();
                    handler.removeCallbacks(releaseRunnable);
                    handler.postDelayed(releaseRunnable, HOLD_MS);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (holding) {
                    int idx = e.findPointerIndex(holdPointerId);
                    if (idx < 0) { cancelHold(); break; }
                    float slop = MOVE_SLOP_DP * density;
                    if (Math.abs(e.getX(idx) - downX) > slop || Math.abs(e.getY(idx) - downY) > slop) cancelHold();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                break;
            default:
                break;
        }
    }

    private void cancelHold() {
        holding = false;
        handler.removeCallbacks(releaseRunnable);
    }

    private boolean isEdge(float x, float y) {
        View d = activity.getWindow().getDecorView();
        float edge = EDGE_DP * density;
        return x <= edge || y <= edge || x >= d.getWidth() - edge || y >= d.getHeight() - edge;
    }

    private void applyExclusion(boolean exclude) {
        if (Build.VERSION.SDK_INT < 29) return;
        final View root = activity.getWindow().getDecorView();
        root.post(() -> {
            List<Rect> rects = new ArrayList<>();
            if (exclude) {
                int w = root.getWidth();
                int h = root.getHeight();
                int edge = (int) (EDGE_DP * density);
                int seg = (int) (200 * density); // Android allows at most 200dp of exclusion per edge
                int top = Math.max(0, h / 2 - seg / 2);
                rects.add(new Rect(0, top, edge, Math.min(h, top + seg)));
                rects.add(new Rect(w - edge, top, w, Math.min(h, top + seg)));
            }
            try { root.setSystemGestureExclusionRects(rects); } catch (Throwable ignored) { }
        });
    }

    public void dispose() {
        handler.removeCallbacksAndMessages(null);
        applyExclusion(false);
    }
}
