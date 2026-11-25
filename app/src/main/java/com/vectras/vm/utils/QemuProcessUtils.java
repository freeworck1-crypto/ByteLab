package com.vectras.vm.utils;

import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds and stops QEMU processes by reading /proc directly.
 *
 * The old code started a whole PRoot shell just to run "pkill" / "ps". That is slow (hundreds of
 * milliseconds up to several seconds on phones) and could hang forever, which froze the app behind
 * a non-cancelable progress dialog. QEMU runs under the same Android user as the app, so it can be
 * found and signalled here without any shell at all.
 */
public final class QemuProcessUtils {
    private static final String QEMU_MARK = "qemu-system-";

    private QemuProcessUtils() {
    }

    /** Reads /proc/PID/cmdline with NUL separators turned into spaces. Returns "" when unreadable. */
    private static String readCmdline(int pid) {
        try (FileInputStream in = new FileInputStream(new File("/proc/" + pid + "/cmdline"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < 16384 && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            byte[] raw = out.toByteArray();
            for (int i = 0; i < raw.length; i++) if (raw[i] == 0) raw[i] = 32;
            return new String(raw);
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private static List<Integer> listPids() {
        List<Integer> pids = new ArrayList<>();
        String[] names = new File("/proc").list();
        if (names == null) return pids;
        for (String name : names) {
            if (name.isEmpty() || name.charAt(0) < 48 || name.charAt(0) > 57) continue;
            try {
                pids.add(Integer.parseInt(name));
            } catch (NumberFormatException ignored) {
            }
        }
        return pids;
    }

    /** PIDs of running qemu-system-* processes that belong to this app. */
    public static List<Integer> findQemuPids() {
        List<Integer> result = new ArrayList<>();
        int me = Process.myPid();
        for (int pid : listPids()) {
            if (pid == me) continue;
            String cmd = readCmdline(pid);
            if (cmd.isEmpty()) continue;
            // Only the program name (first word) counts, so shells that merely mention qemu are ignored.
            int space = cmd.indexOf(32);
            String program = space < 0 ? cmd : cmd.substring(0, space);
            if (program.contains(QEMU_MARK)) result.add(pid);
        }
        return result;
    }

    public static boolean isQemuRunning() {
        return !findQemuPids().isEmpty();
    }

    /** True when a QEMU process is running with the given text on its command line. */
    public static boolean isRunningWith(String commandLinePart) {
        for (int pid : findQemuPids()) {
            if (readCmdline(pid).contains(commandLinePart)) return true;
        }
        return false;
    }

    /**
     * Stops every QEMU process: polite SIGTERM first, SIGKILL for anything still alive after a
     * short wait. Always returns within about 3 seconds. Call it from a background thread.
     */
    public static void killAll() {
        List<Integer> pids = findQemuPids();
        if (pids.isEmpty()) return;

        for (int pid : pids) Process.sendSignal(pid, 15);
        waitUntilGone(pids, 1500);

        List<Integer> alive = new ArrayList<>();
        for (int pid : pids) if (isAlive(pid)) alive.add(pid);
        for (int pid : alive) Process.sendSignal(pid, 9);
        waitUntilGone(alive, 1000);
    }

    private static boolean isAlive(int pid) {
        return new File("/proc/" + pid).exists() && readCmdline(pid).contains(QEMU_MARK);
    }

    private static void waitUntilGone(List<Integer> pids, long maxMillis) {
        long end = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < end) {
            boolean any = false;
            for (int pid : pids) {
                if (isAlive(pid)) {
                    any = true;
                    break;
                }
            }
            if (!any) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
