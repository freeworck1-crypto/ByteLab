package com.vectras.vm.x11;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.vectras.qemu.Config;
import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.manager.QmpSender;

import java.util.Locale;

/**
 * "Android phone mode" for the built-in X11 screen.
 *
 * - Detects that the running VM is an Android system (or the user forced it).
 * - Keeps a per-mode set of "show / hide" switches for every on-screen control.
 * - Routes edge swipes to the virtual phone (recents / notifications).
 * - Presses Enter on the boot menu of an Android ISO automatically.
 */
public final class AndroidMode {
    private AndroidMode() {}

    private static final String PREFS = "android_mode_prefs";

    // Settings keys (stored separately for Android mode "a_" and desktop mode "d_").
    public static final String FORCE = "force_android_mode";
    public static final String ARROWS = "arrows";
    public static final String FUNC_KEYS = "func_keys";   // Esc, Del, Enter, Ctrl, Alt
    public static final String TAB = "tab";
    public static final String WIN = "win";
    public static final String MOUSE = "mouse";
    public static final String TOOLBAR = "toolbar";       // top toolbar always visible
    public static final String SWIPE = "swipe";           // edge swipe goes to the VM
    public static final String AUTO_ENTER = "auto_enter"; // press Enter on boot menu

    // Keys sent to the guest (QEMU "sendkey" syntax). Change here if your image needs something else.
    public static final String KEY_RECENTS = "alt-tab";
    public static final String KEY_NOTIFICATIONS = "meta_l-n";
    public static final String KEY_BACK = "esc";
    public static final String KEY_ENTER = "ret";

    private static final long FORWARD_COOLDOWN_MS = 900;
    private static final long STARTUP_GRACE_MS = 2500;

    private static long lastForward = 0;
    private static long attachedAt = 0;

    // ---------------------------------------------------------------- detection

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The VM name the user sees, e.g. "Android 13", "LineageOS", "BlissOS". */
    private static boolean nameLooksAndroid(String name) {
        if (name == null) return false;
        String t = name.toLowerCase(Locale.ROOT);
        return t.contains("android") || t.contains("lineage") || t.contains("bliss")
                || t.contains("primeos") || t.contains("phoenix os");
    }

    /**
     * File names inside the QEMU command. The app folder itself contains "Android/data",
     * so only specific image names are matched here, never the plain word "android".
     */
    private static boolean envLooksAndroid(String env) {
        if (env == null) return false;
        String t = env.toLowerCase(Locale.ROOT);
        return t.contains("android-images") || t.contains("android-x86") || t.contains("android_x86")
                || t.contains("androidx86") || t.contains("lineage") || t.contains("blissos");
    }

    private static boolean detectAndroidVm() {
        // lastVM* always belongs to the VM that was started most recently.
        return nameLooksAndroid(MainStartVM.lastVMName) || envLooksAndroid(MainStartVM.lastEnv);
    }

    /** True when the VM that is being shown is an Android system, or the user forced the mode. */
    public static boolean isActive(Context c) {
        if (sp(c).getBoolean(FORCE, false)) return true;

        String[] ids = {MainStartVM.lastVMID, MainStartVM.pendingVMID, Config.vmID};
        try {
            SharedPreferences auto = c.getApplicationContext()
                    .getSharedPreferences("bytelab_autopilot", Context.MODE_PRIVATE);
            for (String id : ids) {
                if (id == null || id.isEmpty()) continue;
                // Remembered from an earlier run, or recognised by the auto-setup (name + disc file).
                if (sp(c).getBoolean("android_vm_" + id, false)
                        || "android".equals(auto.getString("profile_" + id, ""))) {
                    remember(c, id);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        if (detectAndroidVm()) {
            remember(c, MainStartVM.lastVMID);
            return true;
        }
        return false;
    }

    private static void remember(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        sp(c).edit().putBoolean("android_vm_" + id, true).apply();
    }

    private static boolean defaultFor(boolean android, String key) {
        if (android) return key.equals(SWIPE);
        return !key.equals(SWIPE);
    }

    public static boolean get(Context c, String key) {
        boolean android = isActive(c);
        return sp(c).getBoolean((android ? "a_" : "d_") + key, defaultFor(android, key));
    }

    private static void put(Context c, boolean android, String key, boolean v) {
        sp(c).edit().putBoolean((android ? "a_" : "d_") + key, v).apply();
    }

    /**
     * Called just before the X11 screen opens. Android phones need the full screen so that
     * edge swipes reach the app, and direct touch (finger = tap) instead of a trackpad.
     */
    public static void prepare(Context c) {
        if (!isActive(c)) return;
        SharedPreferences x11 = PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences.Editor e = x11.edit();
        e.putBoolean("fullscreen", true);
        // "2" = simulated touchscreen: the finger taps exactly where it touches (no mouse pointer moving around).
        e.putString("touchMode", "2");
        e.apply();
    }

    // ---------------------------------------------------------------- guest keys

    public static void sendGuestKey(String combo) {
        new Thread(() -> {
            try {
                QmpSender.send("sendkey " + combo);
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** The boot menu is handled by AutoPilot; nothing to do here (kept so existing calls still compile). */
    public static void onVmStarted(Context c) {
    }

    // ---------------------------------------------------------------- swipe bridge

    /**
     * In full screen the system only shows its bars when the user swipes from an edge.
     * We treat that first swipe as a swipe inside the virtual phone and forward it.
     * A second swipe while the system bar is still visible belongs to the real phone.
     */
    public static void attachSwipeBridge(final Activity activity) {
        attachedAt = SystemClock.uptimeMillis();
        final boolean[] last = {false, false};
        View decor = activity.getWindow().getDecorView();

        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
            boolean nav = insets.isVisible(WindowInsetsCompat.Type.navigationBars());
            boolean status = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            boolean ime = insets.isVisible(WindowInsetsCompat.Type.ime());

            long now = SystemClock.uptimeMillis();
            boolean ready = !ime
                    && activity.hasWindowFocus()
                    && now - attachedAt > STARTUP_GRACE_MS
                    && now - lastForward > FORWARD_COOLDOWN_MS
                    && isActive(activity)
                    && get(activity, SWIPE);

            if (ready) {
                if (nav && !last[0]) {
                    lastForward = now;
                    sendGuestKey(KEY_RECENTS);
                } else if (status && !last[1]) {
                    lastForward = now;
                    sendGuestKey(KEY_NOTIFICATIONS);
                }
            }

            last[0] = nav;
            last[1] = status;
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
    }

    // ---------------------------------------------------------------- customize dialog

    private static final String[] LABELS = {
            "Android phone mode (force on)",
            "Arrow keys",
            "Esc / Del / Enter / Ctrl / Alt",
            "Tab key",
            "Windows key",
            "Mouse buttons (left / middle / right)",
            "Top toolbar always visible (off = small floating button)",
            "Swipe from screen edge goes to the virtual phone"
    };

    private static final String[] KEYS = {
            FORCE, ARROWS, FUNC_KEYS, TAB, WIN, MOUSE, TOOLBAR, SWIPE
    };

    public static void showCustomizeDialog(Activity activity, Runnable onApplied) {
        final boolean android = isActive(activity);
        final boolean[] checked = new boolean[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            checked[i] = KEYS[i].equals(FORCE)
                    ? sp(activity).getBoolean(FORCE, false)
                    : get(activity, KEYS[i]);
        }

        new AlertDialog.Builder(activity)
                .setTitle(android ? "Controls - Android phone" : "Controls - Desktop")
                .setMultiChoiceItems(LABELS, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Apply", (d, w) -> {
                    boolean forceNow = checked[0];
                    sp(activity).edit().putBoolean(FORCE, forceNow).apply();
                    // Settings are saved for the mode that will be active after applying.
                    boolean modeAfter = forceNow || detectAndroidVm();
                    for (int i = 1; i < KEYS.length; i++) put(activity, modeAfter, KEYS[i], checked[i]);
                    if (onApplied != null) onApplied.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
