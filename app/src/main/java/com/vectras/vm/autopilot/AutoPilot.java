package com.vectras.vm.autopilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.vectras.vm.main.vms.DataMainRoms;
import com.vectras.vm.manager.QmpSender;
import com.vectras.vm.manager.VmFileManager;

import java.io.File;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Presses the keys for you while a VM boots/installs, through QEMU's monitor (QMP "sendkey").
 *
 * - Windows: spams a key during "Press any key to boot from CD/DVD" (the answer file does the rest).
 * - Android-x86: picks the first boot-menu entry.
 * - Linux ISOs: picks the first boot-menu entry; Alpine also logs in as root.
 *
 * Timings are in seconds from the moment the VM is un-paused. A speed factor (Settings → slow phones)
 * stretches every wait, because emulated boots are much slower on weak devices.
 */
public final class AutoPilot {
    private static final String TAG = "AutoPilot";
    public static final String PREFS = "bytelab_autopilot";

    public static final String PROFILE_WINDOWS = "windows";
    public static final String PROFILE_ANDROID = "android";
    /** Android that is already installed on its disk (a "ready image"): no ISO, GRUB menu only. */
    public static final String PROFILE_ANDROID_DISK = "android-disk";
    public static final String PROFILE_LINUX = "linux";
    public static final String PROFILE_ALPINE = "alpine";

    private static final int MAX_ASSISTED_BOOTS = 3;
    private static volatile Thread running;

    private AutoPilot() { }

    // ------------------------------------------------------------------ registration

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        if (!on) cancel();
    }

    /** Works out (once per VM) which automation profile fits, from the VM name and its boot disc. */
    private static String ensureProfile(Context c, DataMainRoms cfg) {
        SharedPreferences p = prefs(c);
        String key = "profile_" + cfg.vmID;
        if (p.contains(key)) {
            String v = p.getString(key, "");
            return v == null || v.isEmpty() ? null : v;
        }
        String name = (cfg.itemName == null ? "" : cfg.itemName) + " "
                + (cfg.imgCdrom == null ? "" : cfg.imgCdrom) + " "
                + (cfg.itemExtra == null ? "" : cfg.itemExtra);
        boolean bootsFromDisc = (cfg.imgCdrom != null && !cfg.imgCdrom.isEmpty())
                || name.toLowerCase(Locale.ROOT).contains(".iso");
        String profile = bootsFromDisc ? profileFor(name) : null;
        if (profile == null && !bootsFromDisc && !cfg.isUseUefi
                && (cfg.itemName == null ? "" : cfg.itemName).toLowerCase(Locale.ROOT).contains("android")) {
            profile = PROFILE_ANDROID_DISK;
        }
        p.edit().putString(key, profile == null ? "" : profile).putInt("runs_" + cfg.vmID, 0).apply();
        return profile;
    }

    static String profileFor(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (n.contains("windows") || n.contains("win10") || n.contains("win11") || n.contains("win_")) return PROFILE_WINDOWS;
        if (n.contains("android")) return PROFILE_ANDROID;
        if (n.contains("alpine")) return PROFILE_ALPINE;
        if (n.contains("ubuntu") || n.contains("debian") || n.contains("linux") || n.contains("mint")
                || n.contains("fedora") || n.contains("kali")) return PROFILE_LINUX;
        return null;
    }

    /**
     * Adds what the automation needs to the QEMU command. For Windows that is a virtual USB stick holding
     * autounattend.xml, so Setup runs with no clicks. Returns env unchanged when nothing applies.
     */
    public static String augmentEnv(Context c, DataMainRoms cfg, String env) {
        try {
            if (env == null || env.isEmpty() || cfg == null || !isEnabled(c)) return env;
            if (env.contains("-incoming") || env.contains("bytelabunattend")) return env;
            String profile = ensureProfile(c, cfg);
            if (!PROFILE_WINDOWS.equals(profile)) return env;
            if (prefs(c).getInt("runs_" + cfg.vmID, 0) >= MAX_ASSISTED_BOOTS) return env;

            String hint = ((cfg.itemName == null ? "" : cfg.itemName) + " " + (cfg.imgCdrom == null ? "" : cfg.imgCdrom)).toLowerCase(Locale.ROOT);
            boolean w11 = hint.contains("11");
            File dir = UnattendedWindows.prepare(new File(VmFileManager.getPath(cfg.vmID, "unattend")), cfg.isUseUefi, w11);
            return env + " " + UnattendedWindows.qemuArgs(dir);
        } catch (Throwable t) {
            Log.w(TAG, "could not prepare unattended install: " + t);
            return env;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ run

    /** Call right after the VM has been resumed (QMP is up). Safe to call for any VM. */
    public static void onVmStarted(Context c, String vmId) {
        if (!isEnabled(c)) return;
        SharedPreferences p = prefs(c);
        String profile = p.getString("profile_" + vmId, "");
        if (profile == null || profile.isEmpty()) return;

        int runs = p.getInt("runs_" + vmId, 0);
        // Only the first boots need help (install boot, then first boot after the install).
        // A ready Android image needs the same boot option on every start.
        if (runs >= MAX_ASSISTED_BOOTS && !PROFILE_ANDROID_DISK.equals(profile)) return;
        p.edit().putInt("runs_" + vmId, runs + 1).apply();

        // Emulated boots are slower on phones with few cores: wait proportionally longer.
        final float speed = Runtime.getRuntime().availableProcessors() <= 4 ? 1.5f : 1.0f;
        final List<Step> steps = script(profile, runs);
        if (steps.isEmpty()) return;

        if (running != null) running.interrupt();
        Thread t = new Thread(() -> play(steps, speed), "bytelab-autopilot");
        t.setDaemon(true);
        running = t;
        t.start();
    }

    public static void cancel() {
        Thread t = running;
        if (t != null) t.interrupt();
    }

    private static void play(List<Step> steps, float speed) {
        try {
            for (Step s : steps) {
                Thread.sleep((long) (s.waitSec * 1000L * speed));
                for (int i = 0; i < s.repeat; i++) {
                    if (s.text != null) typeText(s.text);
                    if (s.keys != null) for (String k : s.keys) send(k);
                    if (i + 1 < s.repeat) Thread.sleep((long) (s.everySec * 1000L * speed));
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "autopilot stopped: " + t);
        }
    }

    private static void send(String key) throws InterruptedException {
        String r = QmpSender.send("sendkey " + key);
        Log.d(TAG, "sendkey " + key + " -> " + r);
        Thread.sleep(120);
    }

    private static void typeText(String text) throws InterruptedException {
        for (char ch : text.toCharArray()) {
            String k = keyFor(ch);
            if (k != null) send(k);
        }
    }

    private static String keyFor(char c) {
        if (c >= 'a' && c <= 'z') return String.valueOf(c);
        if (c >= 'A' && c <= 'Z') return "shift-" + Character.toLowerCase(c);
        if (c >= '0' && c <= '9') return String.valueOf(c);
        switch (c) {
            case '\n': return "ret";
            case ' ': return "spc";
            case '-': return "minus";
            case '_': return "shift-minus";
            case '.': return "dot";
            case '=': return "equal";
            case '/': return "slash";
            case ':': return "shift-semicolon";
            default: return null;
        }
    }

    // ------------------------------------------------------------------ profiles

    static final class Step {
        final float waitSec;     // wait before this step
        final String[] keys;     // QEMU key names
        final String text;       // typed text
        final int repeat;
        final float everySec;

        Step(float waitSec, String[] keys, String text, int repeat, float everySec) {
            this.waitSec = waitSec; this.keys = keys; this.text = text; this.repeat = repeat; this.everySec = everySec;
        }
    }

    private static Step keys(float wait, int repeat, float every, String... k) { return new Step(wait, k, null, repeat, every); }

    private static Step type(float wait, String text) { return new Step(wait, null, text, 1, 0); }

    static List<Step> script(String profile, int run) {
        List<Step> s = new ArrayList<>();
        switch (profile) {
            case PROFILE_WINDOWS:
                if (run <= 1) {
                    // "Press any key to boot from CD or DVD" shows for a few seconds right after power-on.
                    // Enter is harmless once Setup runs (the answer file drives it).
                    s.add(keys(3, 20, 1.5f, "ret"));
                }
                break;
            case PROFILE_ANDROID:
                if (run == 0) {
                    // Android-x86 boot menu (isolinux). Android often freezes on the Android logo when the
                    // guest tries to use a GPU that QEMU does not have. Edit the boot line (Tab) and force
                    // the plain VESA framebuffer + software rendering, then boot.
                    s.add(keys(7, 1, 0, "tab"));
                    s.add(type(1.5f, " nomodeset vga=788 HWACCEL=0\n"));
                    // If the menu was not ready, a plain Enter still starts the first entry.
                    s.add(keys(25, 2, 6, "ret"));
                }
                break;
            case PROFILE_ANDROID_DISK:
                // GRUB menu of the installed Android: edit the kernel line of the first entry, add the
                // graphics options that stop the freeze on the Android logo, then boot it.
                s.add(keys(3, 1, 0, "e"));
                s.add(keys(1, 1, 0, "down"));
                s.add(keys(0.5f, 1, 0, "e"));
                s.add(type(1, " nomodeset vga=788 HWACCEL=0\n"));
                s.add(keys(1, 1, 0, "b"));
                // If the menu was still waiting, Enter boots the default entry.
                s.add(keys(10, 1, 0, "ret"));
                break;
            case PROFILE_LINUX:
                if (run == 0) {
                    // GRUB/isolinux boot menu: first entry is the normal start entry.
                    s.add(keys(6, 3, 4, "ret"));
                }
                break;
            case PROFILE_ALPINE:
                if (run == 0) {
                    // The virt ISO boots to a "login:" prompt; root has no password.
                    s.add(type(45, "root\n"));
                }
                break;
            default:
                break;
        }
        return s;
    }
}
