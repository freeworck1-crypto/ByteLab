package com.vectras.vm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vectras.vm.main.MainActivity;
import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.main.vms.DataMainRoms;
import com.vectras.vm.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * "Select Android System" screen.
 * - Download a version (needs your own direct URL below) OR tap "Add custom image" to pick a file from the phone.
 * - Tap a downloaded row, then Next to boot it, or "Add shortcut" to pin it on the home screen.
 */
public class AndroidSystemPickerActivity extends AppCompatActivity {

    public static final String EXTRA_LAUNCH_ID = "bytelab_android_launch_id";
    private static final String PREFS = "android_picker";

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
    private final List<Entry> catalog = new ArrayList<>();

    private Entry selected;
    private View selectedRow;
    private Button next, shortcutBtn;
    private LinearLayout list;
    private ActivityResultLauncher<String[]> pickImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pickImage = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importCustomImage);

        loadCatalog();

        // Opened from a home-screen shortcut: boot that Android directly.
        String launchId = getIntent().getStringExtra(EXTRA_LAUNCH_ID);
        if (launchId != null) {
            Entry e = find(launchId);
            if (e != null && isDownloaded(e)) {
                launch(e);
            } else {
                Toast.makeText(this, "This Android image is missing. Download or add it again.", Toast.LENGTH_LONG).show();
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#14121C"));
        root.setPadding(dp(16), dp(40), dp(16), dp(16));
        root.addView(text("Select Android System", 22, Color.WHITE, true));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button custom = new Button(this);
        custom.setText("+ Add custom image (from phone)");
        custom.setOnClickListener(v -> pickImage.launch(new String[]{"*/*"}));
        root.addView(custom, new LinearLayout.LayoutParams(-1, dp(48)));

        shortcutBtn = new Button(this);
        shortcutBtn.setText("Add shortcut to home screen");
        shortcutBtn.setEnabled(false);
        shortcutBtn.setOnClickListener(v -> addShortcut());
        root.addView(shortcutBtn, new LinearLayout.LayoutParams(-1, dp(48)));

        next = new Button(this);
        next.setText("Next");
        next.setEnabled(false);
        next.setOnClickListener(v -> { if (selected != null) launch(selected); });
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

    // ---------- catalog ----------

    private void loadCatalog() {
        catalog.clear();
        catalog.add(new Entry("a5_1_1", "Android 5.1.1", "arm64 / arm32", "221MB", BASE + "android-5.1.1.img", ""));
        catalog.add(new Entry("a7_1_2", "Android 7.1.2", "arm64 / arm32", "298MB", BASE + "android-7.1.2.img", ""));
        catalog.add(new Entry("a9_0_0", "Android 9.0.0", "arm64", "282MB", BASE + "android-9.0.0.img", ""));
        catalog.add(new Entry("a11_0_0", "Android 11.0.0", "arm64", "351MB", BASE + "android-11.0.0.img", ""));
        catalog.add(new Entry("a12_1_0", "Android 12.1.0", "arm64", "520MB", BASE + "android-12.1.0.img", ""));
        try {
            JSONArray arr = new JSONArray(prefs().getString("custom", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                catalog.add(new Entry(o.getString("id"), o.getString("title"), "custom", "", "", ""));
            }
        } catch (Exception ignored) {
        }
    }

    private Entry find(String id) {
        for (Entry e : catalog) if (e.id.equals(id)) return e;
        return null;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------- UI ----------

    private void buildRows() {
        list.removeAllViews();
        selected = null;
        selectedRow = null;
        next.setEnabled(false);
        shortcutBtn.setEnabled(false);
        for (Entry e : catalog) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(14), dp(8), dp(14));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(e.title, 17, Color.WHITE, true));
            String sub = "Arch: " + e.arch + (e.size.isEmpty() ? "" : "  |  Size: " + e.size);
            info.addView(text(sub, 13, Color.parseColor("#B8B3C6"), false));
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
        if (selectedRow != null) selectedRow.setBackgroundColor(Color.TRANSPARENT);
        selected = e;
        selectedRow = row;
        row.setBackgroundColor(Color.parseColor("#2A2540"));
        next.setEnabled(true);
        shortcutBtn.setEnabled(true);
    }

    private void onAction(Entry e) {
        if (isDownloaded(e)) return;
        if (e.url.isEmpty() || e.url.contains("YOUR-HOST")) {
            Toast.makeText(this, "No download link set for " + e.title + ". Use \"Add custom image\" instead.", Toast.LENGTH_LONG).show();
            return;
        }
        download(e);
    }

    // ---------- files ----------

    private static File imageFile(String id) {
        File dir = new File(AppConfig.maindirpath + "android-images");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id + ".img");
    }

    private boolean isDownloaded(Entry e) {
        File f = imageFile(e.id);
        return f.isFile() && f.length() > 0;
    }

    private void download(Entry e) {
        e.action.setText("Downloading…");
        e.action.setEnabled(false);
        e.bar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File part = new File(imageFile(e.id).getPath() + ".part");
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
                if (!part.renameTo(imageFile(e.id))) throw new Exception("Could not save file");
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

    private void importCustomImage(Uri uri) {
        if (uri == null) return;
        String name = "custom image";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        final String title = name;
        final String id = "custom_" + System.currentTimeMillis();
        Toast.makeText(this, "Copying image…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(imageFile(id))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                JSONArray arr = new JSONArray(prefs().getString("custom", "[]"));
                arr.put(new JSONObject().put("id", id).put("title", title));
                prefs().edit().putString("custom", arr.toString()).apply();
                runOnUiThread(() -> {
                    loadCatalog();
                    buildRows();
                    Toast.makeText(this, "Added: " + title, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ex) {
                imageFile(id).delete();
                runOnUiThread(() -> Toast.makeText(this, "Could not add image: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------- boot ----------

    private void launch(Entry e) {
        String prefKey = "vm_" + e.id;
        String vmId = prefs().getString(prefKey, "");
        int pos = vmId.isEmpty() ? -1 : findVmPosition(vmId);

        if (pos < 0) {
            vmId = VMManager.idGenerator();
            boolean ok = VMManager.createNewVM(e.title, "", imageFile(e.id).getAbsolutePath(),
                    "ARM64", "", e.qemuParams, vmId, VMManager.startRandomPort());
            if (!ok) {
                Toast.makeText(this, "Could not create the virtual phone", Toast.LENGTH_LONG).show();
                return;
            }
            prefs().edit().putString(prefKey, vmId).apply();
            pos = findVmPosition(vmId);
            if (pos < 0) pos = 0;
        }
        DataMainRoms cfg = VMManager.getVMConfig(pos);
        MainStartVM.startNow(this, cfg);
    }

    private static int findVmPosition(String vmId) {
        try {
            JsonArray arr = JsonParser.parseString(FileUtils.readAFile(AppConfig.romsdatajson)).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (o.has("vmID") && vmId.equals(o.get("vmID").getAsString())) return i;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---------- home-screen shortcut ----------

    private void addShortcut() {
        if (selected == null) return;
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "Your launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, AndroidSystemPickerActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.putExtra(EXTRA_LAUNCH_ID, selected.id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, "android_" + selected.id)
                .setShortLabel(selected.title)
                .setLongLabel(selected.title)
                .setIcon(IconCompat.createWithResource(this, getApplicationInfo().icon))
                .setIntent(i)
                .build();
        ShortcutManagerCompat.requestPinShortcut(this, info, null);
    }

    // ---------- helpers ----------

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
