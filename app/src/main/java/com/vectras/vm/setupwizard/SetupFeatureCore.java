
package com.vectras.vm.setupwizard;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.termux.app.TermuxService;
import com.vectras.vm.R;
import com.vectras.vm.VMManager;
import com.vectras.vm.utils.DeviceUtils;
import com.vectras.vm.utils.DialogUtils;
import com.vectras.vm.utils.FileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Objects;

public class SetupFeatureCore {

    public static String TAG = "SetupFeatureCore";
    public static String lastErrorLog = "";

    public static boolean isInstalledSystemFiles(Context context) {
        return isInstalledProot(context) && isInstalledDistro(context);
    }

    public static boolean isInstalledProot(Context context) {
        return FileUtils.isFileExists(
                context.getFilesDir().getAbsolutePath() + "/usr/bin/proot"
        );
    }

    public static boolean isInstalledDistro(Context context) {
        return FileUtils.isFileExists(
                context.getFilesDir().getAbsolutePath() + "/distro/bin/busybox"
        );
    }

    public static boolean isInstalledQemu(Context context) {
        return FileUtils.isFileExists(
                context.getFilesDir().getAbsolutePath()
                        + "/distro/usr/local/bin/qemu-system-x86_64"
        ) || FileUtils.isFileExists(
                context.getFilesDir().getAbsolutePath()
                        + "/distro/usr/bin/qemu-system-x86_64"
        );
    }

    public static boolean startExtractSystemFiles(Context context) {

        String filesDir = context.getFilesDir().getAbsolutePath();

        lastErrorLog = "";

        if (isInstalledSystemFiles(context)) {
            return finishSystemFilesSetup(context);
        }

        FileUtils.delete(filesDir + "/data");
        FileUtils.delete(filesDir + "/distro");
        FileUtils.delete(filesDir + "/usr");

        File distroDir = new File(filesDir, "distro");

        if (!distroDir.exists() && !distroDir.mkdirs()) {
            lastErrorLog = "Failed to create directory: " + distroDir;
            return false;
        }

        // Extract bootstrap.
        if (!extractSystemFiles(context, "bootstrap", "", false)) {
            if (!extractSystemFiles(context, "bootstrap", "", true)) {
                return false;
            }
        }

        // Verify PRoot.
        File proot = new File(filesDir, "usr/bin/proot");

        if (!proot.isFile()) {
            lastErrorLog = "Bootstrap is missing usr/bin/proot.";
            return false;
        }

        // Verify libtalloc.
        File talloc = new File(
                filesDir,
                "usr/lib/libtalloc.so.2.4.2"
        );

        if (!talloc.isFile()) {
            lastErrorLog = "Bootstrap is missing libtalloc.so.2.4.2.";
            return false;
        }

        // Create libtalloc links.
        if (!mkSymlinks(filesDir + "/usr/lib/")) {
            lastErrorLog = "Failed to create libtalloc symbolic links.";
            return false;
        }

        // Create temporary directory.
        File tmpDir = new File(filesDir, "usr/tmp");

        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            lastErrorLog = "Failed to create PRoot temporary directory.";
            return false;
        }

        FileUtils.chmod(tmpDir, 0771);

        // Extract Alpine.
        if (!extractSystemFiles(context, "alpine19", "distro", false)) {
            if (!extractSystemFiles(context, "alpine19", "distro", true)) {
                return false;
            }
        }

        return finishSystemFilesSetup(context);
    }

    private static boolean finishSystemFilesSetup(Context context) {

        String filesDir = context.getFilesDir().getAbsolutePath();

        if (!isInstalledSystemFiles(context)) {
            lastErrorLog = "The bootstrap or Alpine system is incomplete.";
            return false;
        }

        // Verify PRoot and libtalloc.
        File proot = new File(filesDir, "usr/bin/proot");
        File talloc = new File(
                filesDir,
                "usr/lib/libtalloc.so.2.4.2"
        );

        if (!proot.isFile() || !talloc.isFile()) {
            lastErrorLog = "PRoot or libtalloc library is missing.";
            return false;
        }

        if (!mkSymlinks(filesDir + "/usr/lib/")) {
            lastErrorLog = "Could not restore libtalloc symbolic links.";
            return false;
        }

        // Repair Alpine shell links.
        if (!repairAlpineShell(filesDir)) {
            return false;
        }

        // Create PRoot temporary directory.
        File tmpDir = new File(filesDir, "usr/tmp");

        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            lastErrorLog = "Failed to create PRoot temporary directory.";
            return false;
        }

        FileUtils.chmod(tmpDir, 0771);

        if (!extractX11LoaderApk(context)) {
            return false;
        }

        lastErrorLog = "";
        return true;
    }

    /**
     * Repairs Alpine shell links.
     * The original archive uses absolute links such as /bin/busybox.
     * Relative links work correctly inside the PRoot root filesystem.
     */
    private static boolean repairAlpineShell(String filesDir) {

        File root = new File(filesDir, "distro");

        File binDir = new File(root, "bin");
        File busybox = new File(binDir, "busybox");
        File sh = new File(binDir, "sh");
        File ash = new File(binDir, "ash");

        File muslLoader = new File(
                root,
                "lib/ld-musl-aarch64.so.1"
        );

        if (!busybox.isFile()) {
            lastErrorLog = "Alpine /bin/busybox is missing.";
            return false;
        }

        if (!muslLoader.isFile()) {
            lastErrorLog = "Alpine musl loader is missing.";
            return false;
        }

        if (!binDir.isDirectory()) {
            lastErrorLog = "Alpine /bin directory is missing.";
            return false;
        }

        // Remove the original absolute shell links.
        try {
            try {
                Os.unlink(sh.getAbsolutePath());
            } catch (ErrnoException ignored) {
                sh.delete();
            }

            try {
                Os.unlink(ash.getAbsolutePath());
            } catch (ErrnoException ignored) {
                ash.delete();
            }

            // Create relative links to busybox.
            Os.symlink("busybox", sh.getAbsolutePath());
            Os.symlink("busybox", ash.getAbsolutePath());

        } catch (ErrnoException e) {
            lastErrorLog = "Failed to repair Alpine shell links: "
                    + e.getMessage();

            Log.e(TAG, lastErrorLog, e);
            return false;
        }

        if (!sh.exists() || !ash.exists()) {
            lastErrorLog = "Alpine shell links could not be verified.";
            return false;
        }

        return true;
    }

    public static boolean extractSystemFiles(
            Context context,
            String fromAsset,
            String extractTo,
            boolean tryNoSameOwner
    ) {

        String randomFileName = VMManager.startRamdomVMID();
        String filesDir = context.getFilesDir().getAbsolutePath();
        String abi = Build.SUPPORTED_ABIS[0];

        String assetPath = fromAsset + "/" + abi + ".tar";
        String extractedFilePath = filesDir + "/" + randomFileName + ".tar";

        File destDir = new File(filesDir + "/" + extractTo);

        if (!destDir.exists() && !destDir.mkdirs()) {
            lastErrorLog = "Unable to create folder: " + destDir;
            return false;
        }

        if (!copyAssetToFile(
                context,
                assetPath,
                extractedFilePath
        )) {
            return false;
        }

        String[] normalCommand = {
                "tar",
                "xf",
                extractedFilePath,
                "-C",
                destDir.getAbsolutePath()
        };

        String[] noOwnerCommand = {
                "tar",
                "xf",
                extractedFilePath,
                "-C",
                destDir.getAbsolutePath(),
                "--no-same-owner"
        };

        Process process = null;

        try {

            process = Runtime.getRuntime().exec(
                    tryNoSameOwner ? noOwnerCommand : normalCommand
            );

            BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream())
            );

            StringBuilder errorOutput = new StringBuilder();

            String line;

            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }

            errorReader.close();

            int exitCode = process.waitFor();

            if (fromAsset.contains("alpine")) {
                setDNS(context);
            }

            if (exitCode == 0) {
                return true;
            }

            lastErrorLog = errorOutput.toString();

            if (lastErrorLog.isEmpty()) {
                lastErrorLog = "Archive extraction failed. Exit code: "
                        + exitCode;
            }

            return false;

        } catch (IOException | InterruptedException e) {

            lastErrorLog = e.toString();

            Log.e(TAG, "extractSystemFiles", e);

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            return false;

        } finally {

            FileUtils.delete(extractedFilePath);

            if (process != null) {
                process.destroy();
            }
        }
    }

    public static boolean mkSymlinks(String libPath) {

        Log.d(TAG, "Creating libtalloc symbolic links.");

        File library = new File(
                libPath + "libtalloc.so.2.4.2"
        );

        if (!library.isFile()) {
            Log.e(TAG, "libtalloc.so.2.4.2 is missing.");
            return false;
        }

        File versionLink = new File(
                libPath + "libtalloc.so.2"
        );

        File normalLink = new File(
                libPath + "libtalloc.so"
        );

        if (!versionLink.exists()) {

            if (!FileUtils.symlink(
                    libPath + "libtalloc.so.2.4.2",
                    libPath + "libtalloc.so.2"
            )) {
                return false;
            }
        }

        if (!normalLink.exists()) {

            if (!FileUtils.symlink(
                    libPath + "libtalloc.so.2",
                    libPath + "libtalloc.so"
            )) {
                return false;
            }
        }

        return versionLink.exists() && normalLink.exists();
    }

    public static void fixPermissions(String distroPath) {

        File binDir = new File(distroPath + "/bin");
        File usrBinDir = new File(distroPath + "/usr/bin");
        File sbinDir = new File(distroPath + "/sbin");
        File usrSbinDir = new File(distroPath + "/usr/sbin");

        for (File dir : new File[]{
                binDir,
                usrBinDir,
                sbinDir,
                usrSbinDir
        }) {

            if (dir.exists() && dir.listFiles() != null) {

                for (File file : dir.listFiles()) {
                    file.setExecutable(true, false);
                }
            }
        }
    }

    public static boolean copyAssetToFile(
            Context context,
            String assetPath,
            String outputPath
    ) {

        try (
                InputStream in = context.getAssets().open(assetPath);
                OutputStream out = new FileOutputStream(outputPath)
        ) {

            byte[] buffer = new byte[8192];

            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            out.flush();

            return true;

        } catch (IOException e) {

            lastErrorLog = e.toString();

            Log.e(TAG, "copyAssetToFile", e);

            return false;
        }
    }

    public static String readTextFromAssets(
            Context context,
            String fileName
    ) throws IOException {

        StringBuilder sb = new StringBuilder();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        context.getAssets().open(fileName)
                )
        );

        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }

        reader.close();

        return sb.toString();
    }

    public static void setDNS(Context context) {

        String filesDir = context.getFilesDir().getAbsolutePath();

        File rootDir = new File(filesDir + "/distro/root");

        if (!rootDir.exists() && !rootDir.mkdir()) {
            Log.e(TAG, "Could not create distro/root.");
        }

        File resolv = new File(
                filesDir + "/distro/etc/resolv.conf"
        );

        File parent = resolv.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Could not create DNS directory.");
            return;
        }

        try (
                BufferedWriter writer = new BufferedWriter(
                        new FileWriter(resolv)
                )
        ) {

            writer.write("nameserver 1.1.1.1\n");
            writer.write("nameserver 1.0.0.1\n");
            writer.write("nameserver 8.8.8.8\n");
            writer.write("nameserver 8.8.4.4\n");

        } catch (IOException e) {
            Log.e(TAG, "setDNS", e);
        }
    }

    public static void checkabi(Context context) {

        if (!DeviceUtils.is64bit()) {

            DialogUtils.oneDialog(
                    context,
                    context.getResources().getString(R.string.warning),
                    context.getResources().getString(R.string.cpu_not_support_64),
                    context.getString(R.string.ok),
                    true,
                    R.drawable.error_96px,
                    true,
                    null,
                    null
            );
        }
    }

    public static boolean extractX11LoaderApk(Context context) {

        File loaderFile = new File(
                TermuxService.PREFIX_PATH
                        + "/libexec/termux-x11/loader.apk"
        );

        if (!loaderFile.exists()
                || (SDK_INT >= 34 && loaderFile.canWrite())) {

            if (loaderFile.exists() && !loaderFile.delete()) {
                lastErrorLog = "Deleting loader.apk failed.";
                return false;
            }

            if (!FileUtils.createDirectory(loaderFile.getParent())) {
                lastErrorLog = "Creating directory for loader.apk failed.";
                return false;
            }

            if (!copyAssetToFile(
                    context,
                    "bootstrap/loader.apk",
                    loaderFile.getAbsolutePath()
            )) {

                lastErrorLog = "Copying loader.apk failed. "
                        + lastErrorLog;

                return false;
            }

            if (SDK_INT >= 34) {

                if (!loaderFile.setWritable(false, false)) {
                    lastErrorLog = "Changing loader.apk permissions failed.";
                    return false;
                }
            }

            if (loaderFile.exists()
                    && (SDK_INT < 34 || !loaderFile.canWrite())) {
                return true;
            }

            lastErrorLog = "loader.apk is unavailable or permissions don't match.";
            return false;
        }

        return true;
    }
}
