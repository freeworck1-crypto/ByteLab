package com.vectras.vm.utils;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-connection, resumable HTTP downloader used by the ROM store.
 *
 * Why: Android's DownloadManager opens ONE connection, and mirrors (SourceForge in
 * particular) throttle each connection. Splitting the file into several byte ranges
 * and fetching them in parallel is typically several times faster. Partial data is
 * kept in "<name>.part" with a ".meta" file so an interrupted download resumes
 * instead of restarting. Nothing is stored twice: the final file is the .part
 * renamed in place.
 */
public final class FastDownloader {
    private static final String USER_AGENT = "curl/8.5.0";
    private static final int BUFFER = 128 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_ATTEMPTS = 6;

    /** Snapshot of a download, always delivered on the main thread. */
    public static final class State {
        public volatile long done;
        public volatile long total = -1;
        public volatile long bytesPerSecond;
        public volatile boolean finished;
        public volatile boolean failed;
        public volatile boolean cancelled;
        public volatile String message = "";
        public volatile File file;

        State copy() {
            State s = new State();
            s.done = done; s.total = total; s.bytesPerSecond = bytesPerSecond;
            s.finished = finished; s.failed = failed; s.cancelled = cancelled;
            s.message = message; s.file = file;
            return s;
        }

        public boolean isActive() { return !finished && !failed && !cancelled; }
        public int percent() { return total > 0 ? (int) Math.min(100, done * 100L / total) : 0; }
    }

    public interface Listener {
        void onUpdate(State state);
    }

    private static final FastDownloader INSTANCE = new FastDownloader();

    public static FastDownloader get() { return INSTANCE; }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FastDownloader() { }

    public boolean isActive(String key) {
        Job job = jobs.get(key);
        return job != null && job.state.isActive();
    }

    /** Start (or resume) a download. If one is already running for this key it is reused. */
    public void start(String key, String[] urls, File dest, Listener listener) {
        Job existing = jobs.get(key);
        if (existing != null && existing.state.isActive()) {
            existing.listener = listener;
            existing.publish();
            return;
        }
        Job job = new Job(key, urls, dest, listener);
        jobs.put(key, job);
        Thread t = new Thread(job, "bytelab-download");
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
    }

    /** Re-attach a screen to a running download (after rotation / reopening the page). */
    public boolean attach(String key, Listener listener) {
        Job job = jobs.get(key);
        if (job == null) return false;
        if (!job.state.isActive()) { jobs.remove(key, job); return false; }
        job.listener = listener;
        job.publish();
        return true;
    }

    public void detach(String key, Listener listener) {
        Job job = jobs.get(key);
        if (job != null && job.listener == listener) job.listener = null;
    }

    /** Stops the download but keeps the partial file so it can be resumed later. */
    public void cancel(String key) {
        Job job = jobs.get(key);
        if (job != null) job.cancel = true;
    }

    // ------------------------------------------------------------------------------------

    private static final class Probe {
        String url;
        long total = -1;
        boolean ranges;
    }

    private final class Job implements Runnable {
        final String key;
        final String[] urls;
        final File dest;
        final File part;
        final File meta;
        final State state = new State();
        final AtomicLong done = new AtomicLong();
        volatile Listener listener;
        volatile boolean cancel;
        volatile String activeUrl;
        long[] segPos;
        long[] segEnd;

        Job(String key, String[] urls, File dest, Listener listener) {
            this.key = key;
            this.urls = urls;
            this.dest = dest;
            this.part = new File(dest.getParentFile(), dest.getName() + ".part");
            this.meta = new File(dest.getParentFile(), dest.getName() + ".meta");
            this.listener = listener;
        }

        void publish() {
            final Listener l = listener;
            if (l == null) return;
            final State snap = state.copy();
            main.post(() -> l.onUpdate(snap));
        }

        void fail(String message) {
            state.failed = true;
            state.message = message;
            publish();
        }

        @Override
        public void run() {
            try {
                state.message = "Connecting…";
                publish();

                Probe probe = null;
                String lastError = "No download source configured.";
                for (String candidate : urls) {
                    if (candidate == null || candidate.trim().isEmpty()) continue;
                    try {
                        probe = probe(candidate.trim());
                        break;
                    } catch (IOException e) {
                        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                if (probe == null) { fail("Could not reach the download server: " + lastError); return; }
                if (cancel) { finishCancelled(); return; }

                File dir = dest.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) { fail("Could not create the download folder."); return; }

                state.total = probe.total;
                activeUrl = probe.url;

                if (probe.ranges && probe.total >= 2L * 1024 * 1024) {
                    runParallel(probe, urls[0]);
                } else {
                    runSingle(probe);
                }
            } catch (Throwable t) {
                fail(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }

        private void finishCancelled() {
            state.cancelled = true;
            state.message = "Paused";
            publish();
        }

        // ---------------------------------------------------------------- parallel mode

        private void runParallel(Probe probe, String originalUrl) throws Exception {
            final long total = probe.total;
            final int segments = total >= 64L * 1024 * 1024 ? 8 : 4;
            segPos = new long[segments];
            segEnd = new long[segments];
            long chunk = total / segments;
            for (int i = 0; i < segments; i++) {
                segPos[i] = i * chunk;
                segEnd[i] = (i == segments - 1) ? total - 1 : (i + 1) * chunk - 1;
            }

            boolean resumed = loadMeta(originalUrl, total, segments) && part.isFile() && part.length() == total;
            if (!resumed) {
                for (int i = 0; i < segments; i++) segPos[i] = i * chunk;
                if (dir(part) != null && dir(part).getUsableSpace() < total + 64L * 1024 * 1024) {
                    fail("Not enough free storage for this download.");
                    return;
                }
                try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                    raf.setLength(total);
                }
            }
            long already = 0;
            for (int i = 0; i < segments; i++) already += segPos[i] - (i * chunk);
            done.set(already);

            ExecutorService pool = Executors.newFixedThreadPool(segments);
            Future<?>[] futures = new Future<?>[segments];
            for (int i = 0; i < segments; i++) {
                final int index = i;
                futures[i] = pool.submit(() -> { downloadSegment(index); return null; });
            }
            pool.shutdown();

            long lastBytes = done.get();
            long lastTime = System.currentTimeMillis();
            long lastMeta = lastTime;
            double smoothed = 0;
            while (true) {
                boolean allDone = true;
                for (Future<?> f : futures) if (!f.isDone()) { allDone = false; break; }
                long now = System.currentTimeMillis();
                long bytes = done.get();
                long dt = now - lastTime;
                if (dt >= 500) {
                    double inst = (bytes - lastBytes) * 1000.0 / dt;
                    smoothed = smoothed == 0 ? inst : smoothed * 0.6 + inst * 0.4;
                    state.bytesPerSecond = (long) smoothed;
                    lastBytes = bytes;
                    lastTime = now;
                    state.done = bytes;
                    state.message = "Downloading";
                    publish();
                }
                if (now - lastMeta > 3000) { saveMeta(originalUrl, total, segments); lastMeta = now; }
                if (allDone) break;
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            }
            saveMeta(originalUrl, total, segments);

            String error = null;
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    error = c.getMessage() == null ? c.toString() : c.getMessage();
                }
            }
            if (cancel) { finishCancelled(); return; }
            if (error != null) { fail("Download interrupted: " + error + " — tap Resume to continue."); return; }
            if (done.get() < total) { fail("Download incomplete — tap Resume to continue."); return; }
            complete();
        }

        private File dir(File f) { return f.getParentFile(); }

        private void downloadSegment(int i) throws IOException {
            int attempt = 0;
            while (segPos[i] <= segEnd[i]) {
                if (cancel) return;
                HttpURLConnection c = null;
                try {
                    c = open(activeUrl);
                    c.setRequestProperty("Range", "bytes=" + segPos[i] + "-" + segEnd[i]);
                    int code = c.getResponseCode();
                    if (code != 206) {
                        if (code == 200 || code == 403 || code == 404 || code == 410 || code >= 500) {
                            // Mirror link may have expired: resolve a fresh one.
                            Probe fresh = probe(urls[0]);
                            activeUrl = fresh.url;
                        }
                        throw new IOException("HTTP " + code);
                    }
                    try (InputStream in = c.getInputStream();
                         RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                        raf.seek(segPos[i]);
                        byte[] buf = new byte[BUFFER];
                        int n;
                        while (segPos[i] <= segEnd[i] && (n = in.read(buf)) != -1) {
                            if (cancel) return;
                            long remaining = segEnd[i] - segPos[i] + 1;
                            int len = (int) Math.min(n, remaining);
                            raf.write(buf, 0, len);
                            segPos[i] += len;
                            done.addAndGet(len);
                            attempt = 0;
                        }
                    }
                    if (segPos[i] <= segEnd[i]) throw new IOException("connection closed early");
                } catch (IOException e) {
                    if (++attempt >= MAX_ATTEMPTS) throw e;
                    try { Thread.sleep(800L * attempt); } catch (InterruptedException ignored) { }
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }

        // ---------------------------------------------------------------- single mode

        private void runSingle(Probe probe) throws Exception {
            if (probe.total > 0 && dir(part) != null && dir(part).getUsableSpace() < probe.total + 64L * 1024 * 1024) {
                fail("Not enough free storage for this download.");
                return;
            }
            HttpURLConnection c = open(probe.url);
            long lastTime = System.currentTimeMillis();
            long lastBytes = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, false)) {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel) { finishCancelled(); return; }
                    out.write(buf, 0, n);
                    long bytes = done.addAndGet(n);
                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 500) {
                        state.bytesPerSecond = (bytes - lastBytes) * 1000L / (now - lastTime);
                        state.done = bytes;
                        state.message = "Downloading";
                        lastTime = now;
                        lastBytes = bytes;
                        publish();
                    }
                }
            } finally {
                c.disconnect();
            }
            if (probe.total > 0 && done.get() < probe.total) { fail("Download incomplete. Tap Retry."); return; }
            complete();
        }

        // ---------------------------------------------------------------- shared

        private void complete() {
            if (dest.exists() && !dest.delete()) { fail("Could not replace the old file."); return; }
            if (!part.renameTo(dest)) { fail("Could not save the downloaded file."); return; }
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
            state.done = dest.length();
            state.total = dest.length();
            state.file = dest;
            state.finished = true;
            state.bytesPerSecond = 0;
            state.message = "Download complete";
            publish();
        }

        private boolean loadMeta(String url, long total, int segments) {
            try {
                if (!meta.isFile()) return false;
                String text = new String(java.nio.file.Files.readAllBytes(meta.toPath()), "UTF-8");
                String[] lines = text.split("\n");
                if (lines.length < 3 + segments) return false;
                if (!lines[0].equals(url) || Long.parseLong(lines[1].trim()) != total
                        || Integer.parseInt(lines[2].trim()) != segments) return false;
                long[] loaded = new long[segments];
                for (int i = 0; i < segments; i++) {
                    loaded[i] = Long.parseLong(lines[3 + i].trim());
                    if (loaded[i] < segPos[i] || loaded[i] > segEnd[i] + 1) return false;
                }
                System.arraycopy(loaded, 0, segPos, 0, segments);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void saveMeta(String url, long total, int segments) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(url).append('\n').append(total).append('\n').append(segments).append('\n');
                for (int i = 0; i < segments; i++) sb.append(segPos[i]).append('\n');
                try (FileOutputStream out = new FileOutputStream(meta, false)) {
                    out.write(sb.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    /** Follows redirects by hand (to learn the final mirror URL) and detects Range support. */
    private static Probe probe(String start) throws IOException {
        String url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=0-0");
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without a location");
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                String type = c.getContentType();
                if (type != null && type.toLowerCase(Locale.ROOT).contains("text/html")) {
                    throw new IOException("the server returned a web page instead of the OS image");
                }
                Probe p = new Probe();
                p.url = url;
                if (code == 206) {
                    String range = c.getHeaderField("Content-Range");
                    int slash = range == null ? -1 : range.lastIndexOf('/');
                    if (slash > 0) {
                        try { p.total = Long.parseLong(range.substring(slash + 1).trim()); } catch (NumberFormatException ignored) { }
                    }
                    p.ranges = p.total > 0;
                } else if (code == 200) {
                    p.total = c.getContentLengthLong();
                    p.ranges = false;
                } else {
                    throw new IOException("HTTP " + code);
                }
                return p;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** Helper for UI text, e.g. "12.4 MB/s". */
    public static String speedText(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        double mb = bytesPerSecond / (1024.0 * 1024.0);
        return mb >= 1 ? String.format(Locale.US, "%.1f MB/s", mb)
                : String.format(Locale.US, "%d KB/s", bytesPerSecond / 1024);
    }
}
