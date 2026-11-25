package com.vectras.vm.phone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import com.vectras.vm.x11.AndroidMode;

/**
 * Draws a phone bezel (rounded screen corners, camera dot) on top of the virtual phone's screen.
 * Shown only for Android VMs and only when "Phone frame" is chosen on the home screen.
 * It never takes touches: they go straight to the virtual phone underneath.
 */
public final class PhoneFrame {
    private PhoneFrame() { }

    public static void apply(Activity activity) {
        try {
            boolean frame = activity.getSharedPreferences(PhoneHomeActivity.PREFS, Context.MODE_PRIVATE)
                    .getBoolean("frame", true);
            if (!frame || !AndroidMode.isActive(activity)) return;
            activity.addContentView(new Bezel(activity), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {
            // The frame is cosmetic; never let it break the VM screen.
        }
    }

    private static final class Bezel extends View {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d;

        Bezel(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            body.setColor(Color.BLACK);
            dot.setColor(Color.parseColor("#2B3A38"));
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float side = 14 * d;
            float top = 26 * d;
            float bottom = 22 * d;
            float radius = 34 * d;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(new RectF(side, top, w - side, h - bottom), radius, radius, Path.Direction.CW);
            canvas.drawPath(path, body);
            canvas.drawCircle(w / 2f, top / 2f, 5 * d, dot);
        }
    }
}
