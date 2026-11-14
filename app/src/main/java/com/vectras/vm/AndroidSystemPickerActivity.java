package com.vectras.vm;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.vectras.vm.main.MainActivity;
import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.main.vms.DataMainRoms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * "Select Android System" screen: pick a version, download it once, tap Next and it boots.
 * Edit the CATALOG below and put YOUR direct download links in the url fields.
 */
public class AndroidSystemPickerActivity extends AppCompatActivity {

    private static class Entry {
        final String id, title, arch, size, url, qemuParams;
        TextView action;
        ProgressBar bar;

        Entry(String id, String title, String arch, String size, String url, String qemuParams) {
            this.id = id; this.title = title; this.arch = arch;
            this.size = size; this.url = url; this.qemuParams = qemuParams;
        }
    }

    // TODO: replace every url with your own direct link to the Android disk image (.img / .qcow2).
    private static final String BASE = "https://YOUR-HOST/";
    private final List<Entry> catalog = new ArrayList<Entry>() {{
        add(new Entry("a5_1_1", "Android 5.1.1", "arm64 / arm32", "221MB", BASE + "android-5.1.1.img", ""));
        add(new Entry("a7_1_2", "Android 7.1.2", "arm64 / arm32", "298MB", BASE + "android-7.1.2.img", ""));
        add(new Entry("a9_0_0", "Android 9.0.0", "arm64", "282MB", BASE + "android-9.0.0.img", ""));
        add(new Entry("a11_0_0", "Android 11.0.0", "arm64", "351MB", BASE + "android-11.0.0.img", ""));
        add(new Entry("a12_1_0", "Android 12.1.0", "arm64", "520MB", BASE + "android-12.1.0.img", ""));
    }};

    private Entry selected;
    private Button next;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#14121C"));
        root.setPadding(dp(16), dp(40), dp(16), dp(16));

        TextView title = text("Select Android System", 22, Color.WHITE, true);
        root.addView(title);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        next = new Button(this);
        next.setText("Next");
        next.setEnabled(false);
        next.setOnClickListener(v -> startSelected());
        root.addView(next, new LinearLayout.LayoutParams(-1, dp(52)));

        Button skip = new Button(this);
        skip.setText("Open VM list");
        skip.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        root.addView(skip, new LinearLayout.LayoutParams(-1, dp(48)));

        setContentView(root);
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        for (Entry e : catalog) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(14), dp(8), dp(14));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(e.title, 17, Color.WHITE, true));
            info.addView(text("Arch: " + e.arch + "  |  Size: " + e.size, 13, Color.parseColor("#B8B3C6"), false));
            e.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            e.bar.setMax(100);
            e.bar.setVisibility(View.GONE);
            info.addView(e.bar, new LinearLayout.LayoutParams(-1, dp(8)));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            e.action = text(isDownloaded(e) ? "Downloaded" : "Download", 15, Color.parseColor("#D0BCFF"), true);
            e.action.setPadding(dp(12), dp(8), dp(4), dp(8));
            e.action.setOnClickListener(v -> onAction(e));
            row.addView(e.action);

            row.setOnClickListener(v -> select(e, row));
            list.addView(row);
        }
    }

    private void select(Entry e, View row) {
        if (!isDownloaded(e)) {
            Toast.makeText(this, "Download it first", Toast.LENGTH_SHORT).show();
            return;
        }
        selected = e;
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setBackgroundColor(Color.TRANSPARENT);
        row.setBackgroundColor(Color.parseColor("#2A2540"));
        next.setEnabled(true);
    }

    private void onAction(Entry e) {
        if (isDownloaded(e)) return;
        if (e.url.contains("YOUR-HOST")) {
            Toast.makeText(this, "Set a real download URL for " + e.title + " in the code", Toast.LENGTH_LONG).show();
            return;
        }
        download(e);
    }

    private File imageFile(Entry e) {
        File dir = new File(AppConfig.maindirpath + "android-images");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, e.id + ".img");
    }

    private boolean isDownloaded(Entry e) {
        File f = imageFile(e);
        return f.isFile() && f.length() > 0;
    }

    private void download(Entry e) {
        e.action.setText("Downloading…");
        e.action.setEnabled(false);
        e.bar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File part = new File(imageFile(e).getPath() + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(e.url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.connect();
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != last) {
                                last = pct;
                                runOnUiThread(() -> e.bar.setProgress(pct));
                            }
                        }
                    }
                }
                if (!part.renameTo(imageFile(e))) throw new Exception("Could not save file");
                runOnUiThread(() -> {
                    e.bar.setVisibility(View.GONE);
                    e.action.setText("Downloaded");
                    e.action.setEnabled(true);
                });
            } catch (Exception ex) {
                part.delete();
                runOnUiThread(() -> {
                    e.bar.setVisibility(View.GONE);
                    e.action.setText("Download");
                    e.action.setEnabled(true);
                    Toast.makeText(this, "Download failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startSelected() {
        if (selected == null) return;
        String vmId = VMManager.idGenerator();
        boolean ok = VMManager.createNewVM(selected.title, "", imageFile(selected).getAbsolutePath(),
                "ARM64", "", selected.qemuParams, vmId, VMManager.startRandomPort());
        if (!ok) {
            Toast.makeText(this, "Could not create the virtual phone", Toast.LENGTH_LONG).show();
            return;
        }
        // New VMs are inserted at position 0 of the list.
        DataMainRoms cfg = VMManager.getVMConfig(0);
        MainStartVM.startNow(this, cfg);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
