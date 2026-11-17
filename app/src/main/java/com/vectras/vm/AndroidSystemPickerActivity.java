package com.vectras.vm;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
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

/**
 * ByteLab home screen. The UI is HTML (assets/ui/index.html) shown in a WebView.
 * The list of systems comes from your backend: BACKEND_URL + "/api/catalog".
 */
public class AndroidSystemPickerActivity extends AppCompatActivity {

    // TODO: put your Render URL here (no slash at the end).
    private static final String BACKEND_URL = "https://YOUR-APP.onrender.com";

    public static final String EXTRA_LAUNCH_ID = "bytelab_android_launch_id";
    private static final String PREFS = "android_picker";

    private WebView web;
    private boolean pageReady = false;
    private JSONArray serverItems = new JSONArray();
    private ActivityResultLauncher<String[]> pickImage;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickImage = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importCustomImage);

        getWindow().setStatusBarColor(Color.parseColor("#0E1716"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E1716"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E1716"));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1716"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Saved catalog first, so shortcuts and offline use work.
        try {
            serverItems = new JSONArray(prefs().getString("catalog_cache", "[]"));
        } catch (Exception ignored) {
        }

        String launchId = getIntent().getStringExtra(EXTRA_LAUNCH_ID);
        if (launchId != null) {
            JSONObject it = findItem(launchId);
            if (it != null && fileFor(launchId) != null) launch(it);
            else Toast.makeText(this, "This system is missing. Download it again.", Toast.LENGTH_LONG).show();
        }

        web.loadUrl("file:///android_asset/ui/index.html");
    }

    // ---------- bridge (called from HTML) ----------

    private class Bridge {
        @JavascriptInterface
        public void ready() {
            pageReady = true;
            pushCatalog(null);
            loadCatalog();
        }

        @JavascriptInterface
        public void toast(String t) {
            runOnUiThread(() -> Toast.makeText(AndroidSystemPickerActivity.this, t, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void download(String id) {
            AndroidSystemPickerActivity.this.download(id);
        }

        @JavascriptInterface
        public void launch(String id) {
            runOnUiThread(() -> {
                JSONObject it = findItem(id);
                if (it != null) AndroidSystemPickerActivity.this.launch(it);
            });
        }

        @JavascriptInterface
        public void shortcut(String id) {
            runOnUiThread(() -> addShortcut(id));
        }

        @JavascriptInterface
        public void remove(String id) {
            removeImage(id);
        }

        @JavascriptInterface
        public void addCustom() {
            runOnUiThread(() -> pickImage.launch(new String[]{"*/*"}));
        }

        @JavascriptInterface
        public void openVmList() {
            runOnUiThread(() -> {
                startActivity(new Intent(AndroidSystemPickerActivity.this, MainActivity.class));
                finish();
            });
        }
    }

    // ---------- catalog ----------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void loadCatalog() {
        new Thread(() -> {
            if (BACKEND_URL.contains("YOUR-APP")) {
                pushCatalog("Backend not set. Put your Render URL in BACKEND_URL (AndroidSystemPickerActivity.java).");
                return;
            }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BACKEND_URL + "/api/catalog").openConnection();
                c.setConnectTimeout(60000); // free Render servers can take ~50s to wake up
                c.setReadTimeout(60000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                JSONArray arr = new JSONObject(sb.toString()).getJSONArray("items");
                serverItems = arr;
                prefs().edit().putString("catalog_cache", arr.toString()).apply();
                pushCatalog(null);
            } catch (Exception e) {
                pushCatalog("Can't reach the server (" + e.getMessage() + "). Showing your saved list.");
            }
        }).start();
    }

    private JSONArray customItems() {
        try {
            return new JSONArray(prefs().getString("custom", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONObject findItem(String id) {
        try {
            for (int i = 0; i < serverItems.length(); i++)
                if (id.equals(serverItems.getJSONObject(i).getString("id"))) return serverItems.getJSONObject(i);
            JSONArray c = customItems();
            for (int i = 0; i < c.length(); i++)
                if (id.equals(c.getJSONObject(i).getString("id"))) return c.getJSONObject(i);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Sends server list + your own images (with "downloaded" flags) to the HTML page. */
    private void pushCatalog(String notice) {
        if (!pageReady) return;
        try {
            JSONArray all = new JSONArray();
            for (int i = 0; i < serverItems.length(); i++) {
                JSONObject o = new JSONObject(serverItems.getJSONObject(i).toString());
                o.put("downloaded", fileFor(o.getString("id")) != null);
                all.put(o);
            }
            JSONArray c = customItems();
            for (int i = 0; i < c.length(); i++) {
                JSONObject o = new JSONObject(c.getJSONObject(i).toString());
                o.put("downloaded", fileFor(o.getString("id")) != null);
                all.put(o);
            }
            JSONObject info = new JSONObject();
            if (notice != null) info.put("notice", notice);
            final String js = "onCatalog(" + JSONObject.quote(all.toString()) + "," + info + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {
        }
    }

    // ---------- files ----------

    private File fileFor(String id) {
        String path = prefs().getString("file_" + id, "");
        if (path.isEmpty()) return null;
        File f = new File(path);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    private File newImageFile(String id, String fileName) {
        File dir = new File(AppConfig.maindirpath + "android-images");
        if (!dir.exists()) dir.mkdirs();
        String ext = ".img";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0 && fileName.length() - dot <= 8) ext = fileName.substring(dot).toLowerCase();
        return new File(dir, id + ext);
    }

    private void download(String id) {
        JSONObject it = findItem(id);
        if (it == null) return;
        final String url = it.optString("downloadUrl", "");
        final String fileName = it.optString("fileName", "");
        String lower = fileName.toLowerCase();
        if (url.isEmpty()) {
            toastUi("Not uploaded yet");
            pushCatalog(null);
            return;
        }
        if (lower.endsWith(".zip") || lower.endsWith(".xz") || lower.endsWith(".gz") || lower.endsWith(".7z")) {
            toastUi("Archives are not supported. Upload a raw .img or .qcow2 file.");
            pushCatalog(null);
            return;
        }
        new Thread(() -> {
            File target = newImageFile(id, fileName);
            File part = new File(target.getPath() + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(60000);
                c.setReadTimeout(60000);
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
                                final int p = pct;
                                runOnUiThread(() -> web.evaluateJavascript("onProgress(" + JSONObject.quote(id) + "," + p + ")", null));
                            }
                        }
                    }
                }
                if (target.exists()) target.delete();
                if (!part.renameTo(target)) throw new Exception("Could not save file");
                prefs().edit().putString("file_" + id, target.getAbsolutePath()).apply();
            } catch (Exception ex) {
                part.delete();
                toastUi("Download failed: " + ex.getMessage());
            }
            runOnUiThread(() -> web.evaluateJavascript("onProgressDone(" + JSONObject.quote(id) + ")", null));
            pushCatalog(null);
        }).start();
    }

    private void importCustomImage(Uri uri) {
        if (uri == null) return;
        String name = "Custom image";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        final String title = name;
        final String id = "custom_" + System.currentTimeMillis();
        toastUi("Copying image…");
        new Thread(() -> {
            File target = newImageFile(id, title);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                JSONArray arr = customItems();
                arr.put(new JSONObject().put("id", id).put("platform", "custom").put("name", title)
                        .put("arch", "custom image").put("vmArch", "ARM64").put("size", "")
                        .put("params", "").put("iconUrl", "").put("available", true));
                prefs().edit().putString("custom", arr.toString()).putString("file_" + id, target.getAbsolutePath()).apply();
                toastUi("Added: " + title);
            } catch (Exception ex) {
                target.delete();
                toastUi("Could not add image: " + ex.getMessage());
            }
            pushCatalog(null);
        }).start();
    }

    private void removeImage(String id) {
        File f = fileFor(id);
        if (f != null) f.delete();
        SharedPreferences.Editor e = prefs().edit().remove("file_" + id);
        if (id.startsWith("custom_")) {
            JSONArray arr = customItems(), keep = new JSONArray();
            try {
                for (int i = 0; i < arr.length(); i++)
                    if (!id.equals(arr.getJSONObject(i).getString("id"))) keep.put(arr.getJSONObject(i));
            } catch (Exception ignored) {
            }
            e.putString("custom", keep.toString());
        }
        e.apply();
        pushCatalog(null);
    }

    // ---------- boot ----------

    private void launch(JSONObject it) {
        String id = it.optString("id");
        File img = fileFor(id);
        if (img == null) {
            Toast.makeText(this, "Download it first", Toast.LENGTH_SHORT).show();
            return;
        }
        String prefKey = "vm_" + id;
        String vmId = prefs().getString(prefKey, "");
        int pos = vmId.isEmpty() ? -1 : findVmPosition(vmId);

        if (pos < 0) {
            vmId = VMManager.idGenerator();
            boolean ok = VMManager.createNewVM(it.optString("name", "Android"), "", img.getAbsolutePath(),
                    it.optString("vmArch", "ARM64"), "", it.optString("params", ""), vmId, VMManager.startRandomPort());
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

    private void addShortcut(String id) {
        JSONObject it = findItem(id);
        if (it == null) return;
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "Your launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, AndroidSystemPickerActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.putExtra(EXTRA_LAUNCH_ID, id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        String label = it.optString("name", "Android");
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, "bytelab_" + id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(this, getApplicationInfo().icon))
                .setIntent(i)
                .build();
        ShortcutManagerCompat.requestPinShortcut(this, info, null);
    }

    private void toastUi(String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }
}
