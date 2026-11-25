package com.vectras.vm.engine;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.vectras.vm.container.ContainerEntry;

import java.io.File;
import java.util.Locale;

/**
 * Selects an execution path by workload. Linux rootfs workloads never enter the QEMU
 * launcher: they use the app's existing PRoot runtime on matching CPU architectures.
 *
 * Namespace containers are intentionally reported as unavailable until ByteLab ships
 * and verifies a privileged/native namespace launcher. A stock Android app cannot
 * assume CAP_SYS_ADMIN, user namespaces, mount namespaces or cgroup delegation.
 */
public final class EngineRouter {
    public enum Backend { PROOT_LINUX, NATIVE_NAMESPACE_NOT_AVAILABLE, QEMU_FULL_SYSTEM, UNSUPPORTED }

    public static final class Decision {
        @NonNull public final Backend backend;
        @NonNull public final String message;
        public final boolean usesQemu;

        private Decision(Backend backend, String message, boolean usesQemu) {
            this.backend = backend;
            this.message = message;
            this.usesQemu = usesQemu;
        }
    }

    private EngineRouter() { }

    @NonNull
    public static Decision forLinuxContainer(@NonNull Context context, @NonNull ContainerEntry entry) {
        String guestArch = deviceArch(entry);
        if (guestArch == null || !isHostCompatible(guestArch)) {
            return new Decision(Backend.UNSUPPORTED,
                    "This rootfs architecture does not match the phone CPU. Cross-architecture execution requires an emulator.", false);
        }

        File proot = new File(context.getFilesDir(), "usr/bin/proot");
        if (!proot.isFile() || !proot.canExecute()) {
            return new Decision(Backend.UNSUPPORTED,
                    "ByteLab's PRoot runtime is missing. Complete the runtime setup before opening this container.", false);
        }

        return new Decision(Backend.PROOT_LINUX,
                "Native-architecture Linux rootfs: PRoot userspace execution. QEMU is bypassed. Kernel namespace acceleration is not enabled on this device/runtime.", false);
    }

    /** Android images are complete guest operating systems and stay on the full VM path. */
    @NonNull
    public static Decision forAndroidGuest() {
        return new Decision(Backend.QEMU_FULL_SYSTEM,
                "Android guest selected: retain the full-system backend for kernel, virtual devices and guest display compatibility.", true);
    }

    /** Other complete operating systems continue to use the existing full-system backend. */
    @NonNull
    public static Decision forFullSystemVm() {
        return new Decision(Backend.QEMU_FULL_SYSTEM,
                "Full-system guest selected: existing QEMU compatibility path retained.", true);
    }

    private static String deviceArch(ContainerEntry entry) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return entry.archArm64;
            if ("x86_64".equals(abi)) return entry.archX8664;
        }
        return null;
    }

    private static boolean isHostCompatible(String value) {
        if (value == null) return false;
        String arch = value.toLowerCase(Locale.ROOT);
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) && ("arm64".equals(arch) || "aarch64".equals(arch))) return true;
            if ("x86_64".equals(abi) && ("x86_64".equals(arch) || "amd64".equals(arch))) return true;
        }
        return false;
    }
}
