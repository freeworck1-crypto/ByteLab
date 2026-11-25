package com.vectras.vm.container;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Portable stopped-container snapshots. Stores regular files, directories, symlinks and
 * Unix permission bits in a tar.gz archive. It is NOT a live VM/RAM snapshot and must
 * only be called while the container process is stopped.
 */
public final class ContainerSnapshotManager {
    private static final int BUFFER = 64 * 1024;
    private ContainerSnapshotManager() { }

    @NonNull
    public static File snapshotPath(@NonNull Context context, @NonNull String id) {
        return new File(new File(context.getFilesDir(), "container-snapshots"), id + ".tar.gz");
    }

    public static File create(@NonNull Context context, @NonNull String id, @NonNull File rootfs) throws Exception {
        if (!rootfs.isDirectory()) throw new IOException("Container rootfs is not installed");
        File dir = snapshotPath(context, id).getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create snapshot directory");
        File target = snapshotPath(context, id);
        File temp = new File(target.getPath() + ".tmp");
        File checksumTemp = new File(target.getPath() + ".sha256.tmp");
        File checksum = new File(target.getPath() + ".sha256");
        delete(temp);

        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(temp))))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<File> files = new ArrayList<>();
            collect(rootfs, rootfs, files);
            for (File file : files) addEntry(tar, rootfs, file);
            tar.finish();
        } catch (Exception e) {
            delete(temp);
            throw e;
        }

        String digest = sha256(temp);
        try (FileOutputStream out = new FileOutputStream(checksumTemp)) {
            out.write((digest + "  " + target.getName() + "\n").getBytes("UTF-8"));
            out.getFD().sync();
        }
        replace(temp, target);
        replace(checksumTemp, checksum);
        return target;
    }

    /** Downloads a publisher snapshot, verifies its pinned SHA-256, then restores atomically. */
    public static void downloadAndRestore(@NonNull Context context, @NonNull String id,
                                          @NonNull File targetRootfs, @NonNull String url,
                                          @NonNull String expectedSha256,
                                          @NonNull ContainerManager.Progress progress) throws Exception {
        if (!expectedSha256.trim().matches("(?i)[0-9a-f]{64}"))
            throw new IOException("Publisher snapshot needs a valid 64-character SHA-256 pin");
        if (!"https".equalsIgnoreCase(new URL(url).getProtocol()))
            throw new IOException("Preconfigured snapshots must be downloaded over HTTPS");
        File archive = snapshotPath(context, id);
        File part = new File(archive.getPath() + ".download");
        File parent = archive.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create snapshot cache");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buffer = new byte[BUFFER];
            long total = connection.getContentLengthLong();
            long done = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                progress.onStatus(total > 0 ? "Downloading snapshot… " + (done * 100 / total) + "%" : "Downloading snapshot… " + (done / 1048576) + " MB");
            }
        } finally {
            connection.disconnect();
        }
        String actual = sha256(part);
        if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
            delete(part);
            throw new IOException("Downloaded snapshot SHA-256 does not match the catalogue pin");
        }
        replace(part, archive);
        File sidecar = new File(archive.getPath() + ".sha256");
        try (FileOutputStream out = new FileOutputStream(sidecar)) {
            out.write((actual + "  " + archive.getName() + "\n").getBytes("UTF-8"));
        }
        progress.onStatus("Verifying and restoring snapshot…");
        restore(archive, targetRootfs);
    }

    public static void restore(@NonNull File archive, @NonNull File targetRootfs) throws Exception {
        File checksum = new File(archive.getPath() + ".sha256");
        if (!archive.isFile() || !checksum.isFile()) throw new IOException("Snapshot or checksum file is missing");
        String expected = readFirstToken(checksum);
        if (!expected.equals(sha256(archive))) throw new IOException("Snapshot checksum mismatch; restore cancelled");

        File parent = targetRootfs.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Cannot create restore parent");
        File staging = new File(parent, targetRootfs.getName() + ".restore-tmp");
        File backup = new File(parent, targetRootfs.getName() + ".restore-backup");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create staging rootfs");

        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (!isSafeRelativePath(name)) throw new IOException("Unsafe snapshot path: " + name);
                File out = new File(staging, name);
                String root = staging.getCanonicalPath() + File.separator;
                if (!out.getCanonicalPath().startsWith(root) && !out.getCanonicalPath().equals(staging.getCanonicalPath()))
                    throw new IOException("Snapshot entry escapes rootfs");
                File p = out.getParentFile();
                if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("Cannot create " + p);

                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create directory " + out);
                    chmod(out, entry.getMode());
                } else if (entry.isSymbolicLink()) {
                    delete(out);
                    Os.symlink(entry.getLinkName(), out.getAbsolutePath());
                } else if (entry.isFile()) {
                    try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = tar.read(buffer)) != -1) stream.write(buffer, 0, n);
                    }
                    chmod(out, entry.getMode());
                } else {
                    // Device nodes, FIFOs and sockets are not needed in app-private PRoot rootfs snapshots.
                    // /dev, /proc and /sys are bind-mounted at launch.
                }
            }
        } catch (Exception e) {
            deleteTree(staging);
            throw e;
        }

        deleteTree(backup);
        if (targetRootfs.exists() && !targetRootfs.renameTo(backup)) {
            deleteTree(staging);
            throw new IOException("Cannot move current rootfs aside");
        }
        if (!staging.renameTo(targetRootfs)) {
            if (backup.exists()) //noinspection ResultOfMethodCallIgnored
                backup.renameTo(targetRootfs);
            deleteTree(staging);
            throw new IOException("Cannot activate restored rootfs");
        }
        deleteTree(backup);
    }

    private static void collect(File root, File current, List<File> out) throws IOException {
        File[] children = current.listFiles();
        if (children == null) throw new IOException("Cannot read " + current);
        for (File child : children) {
            int mode = lstat(child).st_mode;
            if (OsConstants.S_ISLNK(mode)) {
                out.add(child);
            } else if (OsConstants.S_ISDIR(mode)) {
                out.add(child);
                collect(root, child, out);
            } else if (OsConstants.S_ISREG(mode)) {
                out.add(child);
            }
        }
    }

    private static void addEntry(TarArchiveOutputStream tar, File root, File file) throws Exception {
        String relative = root.toURI().relativize(file.toURI()).getPath();
        android.system.StructStat stat = lstat(file);
        TarArchiveEntry entry;
        if (OsConstants.S_ISLNK(stat.st_mode)) {
            entry = new TarArchiveEntry(relative, (byte) '2');
            entry.setLinkName(Os.readlink(file.getAbsolutePath()));
        } else {
            entry = new TarArchiveEntry(file, relative);
        }
        entry.setMode(stat.st_mode & 07777);
        entry.setUserId((int) stat.st_uid);
        entry.setGroupId((int) stat.st_gid);
        entry.setModTime(stat.st_mtime * 1000L);
        tar.putArchiveEntry(entry);
        if (entry.isFile()) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) != -1) tar.write(buffer, 0, n);
            }
        }
        tar.closeArchiveEntry();
    }

    private static android.system.StructStat lstat(File file) throws IOException {
        try { return Os.lstat(file.getAbsolutePath()); }
        catch (ErrnoException e) { throw new IOException("Cannot inspect " + file, e); }
    }

    private static void chmod(File file, int mode) {
        try { Os.chmod(file.getAbsolutePath(), mode & 07777); } catch (Exception ignored) { }
    }

    private static boolean isSafeRelativePath(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) return false;
        String[] parts = name.split("/");
        for (String part : parts) if (part.equals("..")) return false;
        return true;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private static String readFirstToken(File file) throws IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Empty checksum");
            return line.trim().split("\\s+")[0];
        }
    }

    private static void replace(File source, File target) throws IOException {
        File old = new File(target.getPath() + ".old");
        delete(old);
        if (target.exists() && !target.renameTo(old)) throw new IOException("Cannot back up " + target);
        if (!source.renameTo(target)) {
            if (old.exists()) //noinspection ResultOfMethodCallIgnored
                old.renameTo(target);
            throw new IOException("Cannot commit " + target);
        }
        delete(old);
    }

    private static void delete(File file) { if (file != null && file.exists()) //noinspection ResultOfMethodCallIgnored
        file.delete(); }

    private static void deleteTree(File file) throws IOException {
        if (file == null || !file.exists()) return;
        int mode;
        try { mode = Os.lstat(file.getAbsolutePath()).st_mode; }
        catch (Exception e) { mode = OsConstants.S_IFREG; }
        if (OsConstants.S_ISDIR(mode) && !OsConstants.S_ISLNK(mode)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot delete " + file);
    }
}
