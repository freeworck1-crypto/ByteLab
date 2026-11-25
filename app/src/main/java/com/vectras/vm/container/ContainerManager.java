package com.vectras.vm.container;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.system.Os;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.termux.app.TermuxService;
import com.vectras.vm.engine.EngineRouter;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight containers: a root filesystem downloaded once (a few MB to ~30 MB) and run through the
 * app's bundled proot. No virtual machine and no full OS image is needed.
 *
 * "Root" here means root INSIDE the container (proot -0). The phone itself is never rooted.
 */
public class ContainerManager {
    public static final String PREFS = "bytelab_containers";

    public interface Progress {
        void onStatus(String message);
        void onDone(boolean ok, String message);
    }

    private ContainerManager() { }

    // ---------------------------------------------------------------- catalogue

    public static List<ContainerEntry> loadCatalog(Context context) {
        try (InputStream in = context.getAssets().open("containers.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            List<ContainerEntry> list = new Gson().fromJson(out.toString("UTF-8"),
                    new TypeToken<List<ContainerEntry>>() { }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static ContainerEntry find(Context context, String id) {
        for (ContainerEntry e : loadCatalog(context)) if (e.id != null && e.id.equals(id)) return e;
        return null;
    }

    // ---------------------------------------------------------------- paths

    public static File root(Context c) { return new File(c.getFilesDir(), "containers"); }

    public static File rootfs(Context c, String id) { return new File(root(c), id + "/rootfs"); }

    public static File runScript(Context c, String id) { return new File(root(c), id + "/run.sh"); }

    public static boolean isInstalled(Context c, String id) {
        return new File(root(c), id + "/.installed").isFile();
    }

    public static boolean isRootEnabled(Context c, String id) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("root_" + id, true);
    }

    public static void setRootEnabled(Context c, String id, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("root_" + id, on).apply();
    }

    public static boolean isProotReady(Context c) {
        return new File(c.getFilesDir(), "usr/bin/proot").isFile();
    }

    public static String deviceArch(ContainerEntry e) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.startsWith("arm64")) return e.archArm64;
            if (abi.equals("x86_64")) return e.archX8664;
        }
        return null; // 32-bit devices: no container (rootfs archives are 64-bit)
    }

    // ---------------------------------------------------------------- install

    /** Blocking: call from a background thread. */
    public static void install(Context context, ContainerEntry e, Progress p) {
        try {
            if (!isProotReady(context)) {
                p.onDone(false, "Finish the ByteLab setup first (the proot runtime is missing).");
                return;
            }
            String arch = deviceArch(e);
            if (arch == null) {
                p.onDone(false, "Containers need a 64-bit phone (arm64 or x86_64).");
                return;
            }

            File dir = new File(root(context), e.id);
            File fs = rootfs(context, e.id);

            // A publisher may provide a preconfigured, checksum-pinned rootfs snapshot.
            // This skips per-user package installation/configuration and avoids QEMU.
            if (e.snapshotUrl != null && !e.snapshotUrl.trim().isEmpty()) {
                p.onStatus("Downloading preconfigured environment…");
                ContainerSnapshotManager.downloadAndRestore(context, e.id, fs,
                        e.snapshotUrl, e.snapshotSha256, p);
                p.onStatus("Finalizing environment…");
                configure(context, e, fs);
                writeRunScript(context, e);
                new File(dir, ".installed").createNewFile();
                p.onDone(true, e.name + " restored from its verified preconfigured snapshot.");
                return;
            }

            p.onStatus("Finding the latest version…");
            String url = resolveUrl(e, arch);

            File archive = new File(dir, "rootfs.tar.gz");
            deleteRecursive(fs);
            if (!fs.mkdirs() && !fs.isDirectory()) { p.onDone(false, "Could not create the container folder."); return; }

            if (!archive.isFile() || archive.length() == 0) {
                download(url, archive, p);
            }

            p.onStatus("Unpacking…");
            extract(archive, fs, p);

            p.onStatus("Configuring…");
            configure(context, e, fs);
            writeRunScript(context, e);
            //noinspection ResultOfMethodCallIgnored
            archive.delete(); // keep storage small once unpacked
            new File(dir, ".installed").createNewFile();
            p.onDone(true, e.name + " is ready.");
        } catch (Exception ex) {
            p.onDone(false, "Install failed: " + ex.getMessage());
        }
    }

    private static String resolveUrl(ContainerEntry e, String arch) throws Exception {
        String file = "";
        if (e.listingUrl != null && !e.listingUrl.isEmpty()) {
            String body = httpGet(e.listingUrl.replace("{arch}", arch));
            Matcher m = Pattern.compile(e.fileRegex.replace("{arch}", Pattern.quote(arch))).matcher(body);
            String last = null;
            while (m.find()) last = m.group();
            if (last == null) throw new Exception("could not find a download for " + e.name);
            file = last;
        }
        return e.urlTemplate.replace("{arch}", arch).replace("{file}", file);
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest, Progress p) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("no storage");
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream out = new FileOutputStream(part)) {
            long total = c.getContentLengthLong();
            long done = 0;
            long lastUi = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 400) {
                    lastUi = now;
                    p.onStatus(total > 0
                            ? "Downloading… " + (done * 100 / total) + "%  (" + (done / 1024 / 1024) + " MB)"
                            : "Downloading… " + (done / 1024 / 1024) + " MB");
                }
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(dest)) throw new Exception("could not save the download");
    }

    private static void extract(File archive, File outDir, Progress p) throws Exception {
        String base = outDir.getCanonicalPath() + File.separator;
        int count = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(archive), 65536)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || name.equals(".")) continue;
                if (name.startsWith("dev/")) continue; // device nodes are provided by proot (-b /dev)
                File target = new File(outDir, name);
                if (!target.getCanonicalPath().startsWith(base) && !target.getCanonicalPath().equals(base.substring(0, base.length() - 1))) {
                    continue; // path traversal guard
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();

                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    try { Os.chmod(target.getPath(), (entry.getMode() & 0777) | 0700); } catch (Exception ignored) { }
                } else if (entry.isSymbolicLink()) {
                    try { new File(target.getPath()).delete(); Os.symlink(entry.getLinkName(), target.getPath()); } catch (Exception ignored) { }
                } else if (entry.isLink()) {
                    String linkName = entry.getLinkName();
                    while (linkName.startsWith("./")) linkName = linkName.substring(2);
                    File src = new File(outDir, linkName);
                    if (src.isFile()) copy(src, target);
                } else if (entry.isFile()) {
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target), 65536)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = tar.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    try { Os.chmod(target.getPath(), (entry.getMode() & 0777) | 0600); } catch (Exception ignored) { }
                }
                if (++count % 400 == 0) p.onStatus("Unpacking… " + count + " files");
            }
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        try { Os.chmod(to.getPath(), 0755); } catch (Exception ignored) { }
    }

    private static void configure(Context context, ContainerEntry e, File fs) throws Exception {
        write(new File(fs, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        File hosts = new File(fs, "etc/hosts");
        if (!hosts.exists()) write(hosts, "127.0.0.1 localhost\n::1 localhost\n");
        //noinspection ResultOfMethodCallIgnored
        new File(fs, "root").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(fs, "tmp").mkdirs();
        // Debian/Ubuntu: apt tries to drop to the "_apt" user, which does not work under proot.
        File aptConf = new File(fs, "etc/apt/apt.conf.d");
        if (aptConf.isDirectory()) write(new File(aptConf, "99bytelab-proot"), "APT::Sandbox::User \"root\";\n");
        if (e.firstRun != null && !e.firstRun.isEmpty()) write(new File(fs, "root/.bytelab_first_run.sh"), e.firstRun + "\n");
    }

    private static void write(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes("UTF-8")); }
    }

    // ---------------------------------------------------------------- run

    /** (Re)writes run.sh so the root toggle takes effect on the next launch. */
    public static void writeRunScript(Context context, ContainerEntry e) throws Exception {
        String files = context.getFilesDir().getAbsolutePath();
        boolean root = isRootEnabled(context, e.id);
        String shell = e.shell == null || e.shell.isEmpty() ? "/bin/sh" : e.shell;
        String home = root ? "/root" : "/home/user";

        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("unset LD_PRELOAD TMPDIR XDG_RUNTIME_DIR PREFIX BOOTCLASSPATH\n");
        s.append("mkdir -p '").append(files).append("/usr/tmp'\n");
        s.append("export PROOT_TMP_DIR='").append(files).append("/usr/tmp'\n");
        s.append("export PROOT_LOADER='").append(files).append("/usr/libexec/proot/loader'\n");
        s.append("export PROOT_LOADER_32='").append(files).append("/usr/libexec/proot/loader32'\n");
        s.append("export LD_LIBRARY_PATH='").append(files).append("/usr/lib'\n");
        if (!root) {
            // Non-root user inside the container (created on first use).
            s.append("mkdir -p '").append(rootfs(context, e.id)).append("/home/user'\n");
        }
        s.append("exec '").append(files).append("/usr/bin/proot' --kill-on-exit --link2symlink ");
        s.append(root ? "-0 " : "-i 1000:1000 ");
        s.append("-r '").append(rootfs(context, e.id)).append("' ");
        s.append("-b /dev -b /proc -b /sys -b /sdcard -b /storage ");
        s.append("-b '").append(files).append("/usr/tmp':/tmp ");
        s.append("-w ").append(home).append(" ");
        s.append("/usr/bin/env -i HOME=").append(home);
        s.append(" USER=").append(root ? "root" : "user");
        s.append(" TERM=xterm-256color LANG=C.UTF-8 ");
        s.append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ");
        s.append(shell).append(" -l\n");

        File script = runScript(context, e.id);
        write(script, s.toString());
        //noinspection ResultOfMethodCallIgnored
        script.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        script.setReadable(true, false);
    }

    /** Opens the container in a terminal session. */
    public static void open(Context context, ContainerEntry e) throws Exception {
        if (!isInstalled(context, e.id)) throw new Exception("Container is not installed.");
        EngineRouter.Decision route = EngineRouter.forLinuxContainer(context, e);
        if (route.backend != EngineRouter.Backend.PROOT_LINUX) throw new Exception(route.message);
        writeRunScript(context, e);
        Intent i = new Intent(TermuxService.ACTION_EXECUTE, Uri.fromFile(runScript(context, e.id)));
        i.setClass(context, TermuxService.class);
        i.putExtra(TermuxService.EXTRA_CURRENT_WORKING_DIRECTORY, context.getFilesDir().getAbsolutePath());
        context.startService(i); // the app is in the foreground here, so this is allowed
        if (context instanceof android.app.Activity) {
            // Show the terminal right away (the service would otherwise have to start it from the background).
            context.startActivity(new Intent(context, com.termux.app.TermuxActivity.class));
        }
    }

    public static void delete(Context context, String id) {
        deleteRecursive(new File(root(context), id));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() && !isSymlink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalFile().equals(new File(f.getParentFile().getCanonicalFile(), f.getName()));
        } catch (Exception e) {
            return true;
        }
    }
}
