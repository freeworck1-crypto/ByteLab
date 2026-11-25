package com.vectras.vm.manager;

import android.content.Context;

import com.vectras.vm.utils.QemuProcessUtils;

public class ProcessManager {
    /** Fast check (reads /proc, no shell). The context argument is kept for existing callers. */
    public static boolean isQemuRunning(Context context) {
        return QemuProcessUtils.isQemuRunning();
    }
}
