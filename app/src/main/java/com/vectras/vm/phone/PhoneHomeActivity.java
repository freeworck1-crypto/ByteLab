package com.vectras.vm.phone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;
import com.vectras.vm.AppConfig;
import com.vectras.vm.R;
import com.vectras.vm.VMManager;
import com.vectras.vm.main.MainActivity;
import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.main.vms.DataMainRoms;
import com.vectras.vm.manager.VmFileManager;
import com.vectras.vm.utils.DeviceUtils;
import com.vectras.vm.utils.FastDownloader;
import com.vectras.vm.utils.FileUtils;
import com.vectras.vterm.Terminal2;

import java.io.File;

/**
 * The whole app in one screen: Download -> Run -> a virtual Android phone.
 * Everything inside the phone (browser, files, Wi-Fi, installing APKs) is the Android system itself.
 */
public class PhoneHomeActivity extends AppCompatActivity {

    static final String PREFS = "phone_home";
    private static final String KEY = "phone_android";
    private static final String FILE_NAME = "android-phone.iso";
    private static final String IMAGE_URL =
            "https://github.com/freeworck1-crypto/ByteLab/releases/download/android-5.1/ByteLab-android-x86_64-5.1.iso";

    private MaterialButton button;
    private ProgressBar progress;
    private TextView status;
    private SwitchCompat rootSwitch;
    private boolean busy = false;

    private final FastDownloader.Listener listener = state -> runOnUiThread(() -> render(state));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        getWindow().setStatusBarColor(Color.parseColor("#0E1716"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E1716"));
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        busy = false;
        refreshIdle();
    }

    @Override
    protected void onPause() {
        FastDownloader.get().detach(KEY, listener);
        super.onPause();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        final float d = getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0E1716"));
        root.setFitsSystemWindows(true);
        int pad = (int) (24 * d);
        root.setPadding(pad, (int) (56 * d), pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.bytelab_logo);
        root.addView(logo, new LinearLayout.LayoutParams((int) (112 * d), (int) (112 * d)));

        TextView title = new TextView(this);
        title.setText("Android Phone");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        // Long-press the title for the classic advanced screen.
        title.setOnLongClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            return true;
        });
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("A virtual Android phone. Download once, then it works offline.");
        sub.setTextColor(Color.parseColor("#9FB3AF"));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        button = new MaterialButton(this);
        button.setTextSize(18);
        button.setAllCaps(true);
        button.setOnClickListener(v -> onButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams((int) (280 * d), (int) (60 * d));
        bp.topMargin = (int) (36 * d);
        root.addView(button, bp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                (int) (280 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = (int) (16 * d);
        root.addView(progress, pp);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FB3AF"));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (8 * d), 0, 0);
        root.addView(status);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        RadioGroup display = new RadioGroup(this);
        display.setOrientation(LinearLayout.HORIZONTAL);
        display.setGravity(Gravity.CENTER);
        RadioButton frame = new RadioButton(this);
        frame.setId(View.generateViewId());
        frame.setText("Phone frame");
        frame.setTextColor(Color.WHITE);
        RadioButton full = new RadioButton(this);
        full.setId(View.generateViewId());
        full.setText("Full screen");
        full.setTextColor(Color.WHITE);
        full.setPadding(full.getPaddingLeft(), 0, (int) (8 * d), 0);
        display.addView(frame);
        display.addView(full);
        if (prefs.getBoolean("frame", true)) frame.setChecked(true); else full.setChecked(true);
        display.setOnCheckedChangeListener((group, checkedId) ->
                prefs.edit().putBoolean("frame", checkedId == frame.getId()).apply());
        root.addView(display);

        rootSwitch = new SwitchCompat(this);
        rootSwitch.setText("Root");
        rootSwitch.setTextColor(Color.WHITE);
        rootSwitch.setChecked(prefs.getBoolean("root", false));
        rootSwitch.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("root", checked).apply());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (8 * d);
        root.addView(rootSwitch, rp);

        return root;
    }

    // --------------------------------------------------------------- states

    private File target() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    private boolean isDownloaded(File f) {
        return f != null && f.isFile() && f.length() > 0
                && !new File(f.getParentFile(), f.getName() + ".part").exists();
    }

    private String savedVmId() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("vm_id", "");
    }

    private boolean vmExists() {
        String id = savedVmId();
        return !id.isEmpty() && VMManager.isVMExist(id);
    }

    private void setIdle(String buttonText, String statusText) {
        button.setEnabled(true);
        button.setText(buttonText);
        progress.setVisibility(View.INVISIBLE);
        status.setText(statusText);
    }

    private void refreshIdle() {
        if (FastDownloader.get().attach(KEY, listener)) return; // a download is running
        File f = target();
        if (vmExists()) {
            setIdle("Run", "");
        } else if (isDownloaded(f)) {
            setIdle("Run", "Ready.");
        } else if (f != null && new File(f.getParentFile(), f.getName() + ".part").isFile()) {
            setIdle("Resume", "Partly downloaded.");
        } else {
            setIdle("Download", "About 450 MB. Needed only once.");
        }
    }

    // -------------------------------------------------------------- actions

    private void onButton() {
        if (busy) return;
        FastDownloader fd = FastDownloader.get();
        if (fd.isActive(KEY)) {
            fd.cancel(KEY); // pause, partial data is kept
            return;
        }
        if (vmExists() && startExisting()) return;
        File f = target();
        if (isDownloaded(f)) {
            createAndStart(f);
        } else {
            startDownload(f);
        }
    }

    private void startDownload(File f) {
        if (f == null) {
            Toast.makeText(this, "No storage available.", Toast.LENGTH_LONG).show();
            return;
        }
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "Could not create the download folder.", Toast.LENGTH_LONG).show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        button.setText("Pause");
        status.setText("Connecting…");
        FastDownloader.get().start(KEY, new String[]{IMAGE_URL}, f, listener);
    }

    private void render(FastDownloader.State s) {
        if (isFinishing() || isDestroyed()) return;
        progress.setVisibility(View.VISIBLE);
        button.setEnabled(true);
        if (s.finished) {
            FastDownloader.get().detach(KEY, listener);
            progress.setIndeterminate(false);
            progress.setProgress(100);
            File f = s.file != null ? s.file : target();
            if (!busy) createAndStart(f);
        } else if (s.failed) {
            progress.setIndeterminate(false);
            button.setText("Resume");
            status.setText(s.message);
        } else if (s.cancelled) {
            progress.setIndeterminate(false);
            button.setText("Resume");
            status.setText("Paused at " + s.percent() + "%");
        } else {
            button.setText("Pause");
            if (s.total > 0 && s.done > 0) {
                progress.setIndeterminate(false);
                progress.setProgress(s.percent());
                String speed = FastDownloader.speedText(s.bytesPerSecond);
                status.setText(s.percent() + "%  •  "
                        + Formatter.formatFileSize(this, s.done) + " / "
                        + Formatter.formatFileSize(this, s.total)
                        + (speed.isEmpty() ? "" : "  •  " + speed));
            } else {
                progress.setIndeterminate(true);
                status.setText(s.message);
            }
        }
    }

    /** Starts the phone that was already created. Returns false if its saved settings are gone. */
    private boolean startExisting() {
        String id = savedVmId();
        try {
            DataMainRoms existing = new Gson().fromJson(
                    FileUtils.readAFile(VmFileManager.getConfigFile(id)), DataMainRoms.class);
            if (existing != null) {
                existing.guestRoot = rootSwitch.isChecked();
                existing.wifi = true;
                busy = true;
                status.setText("Starting…");
                MainStartVM.startNow(this, existing);
                return true;
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("vm_id").apply();
        return false;
    }

    /** First run: makes the phone's disk, registers it, then starts it. */
    private void createAndStart(File iso) {
        if (iso == null || !iso.isFile() || iso.length() <= 0) {
            setIdle("Download", "The image file is missing. Download it again.");
            return;
        }
        busy = true;
        button.setEnabled(false);
        button.setText("Preparing");
        status.setText("Preparing the phone (first run only)…");

        final String vmId = VMManager.idGenerator();
        final File vmDisk = new File(VmFileManager.getPath(vmId, "disk.qcow2"));
        File parent = vmDisk.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            busy = false;
            setIdle("Run", "Could not create the phone folder.");
            return;
        }

        final Terminal2 terminal = new Terminal2(this);
        String safe = vmDisk.getAbsolutePath().replace("'", "'\\''");
        terminal.execute("qemu-img create -f qcow2 '" + safe + "' 16G", new Terminal2.Terminal2Callback() {
            @Override
            public void onRunning(String command, String newLine) { }

            @Override
            public void onFinished(String command, String log, int exitStatus) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (exitStatus != terminal.SUCCESS || !vmDisk.isFile() || vmDisk.length() <= 0) {
                        busy = false;
                        setIdle("Run", "Could not prepare the phone. Tap Run to try again.");
                        return;
                    }
                    registerAndStart(iso, vmDisk, vmId);
                });
            }

            @Override
            public void onError(String command, Exception exception) {
                runOnUiThread(() -> {
                    busy = false;
                    setIdle("Run", "Could not prepare the phone. Tap Run to try again.");
                });
            }
        });
    }

    private void registerAndStart(File iso, File vmDisk, String vmId) {
        DataMainRoms config = new DataMainRoms();
        config.vmID = vmId;
        config.itemName = "Android Phone";
        config.itemIcon = "";
        config.itemArch = "X86_64";
        config.itemPath = vmDisk.getAbsolutePath();
        config.imgCdrom = iso.getAbsolutePath();
        config.itemExtra = "-boot order=cd -vga std";
        config.memory = 2048;
        config.cores = 1;
        config.graphicCard = 1;
        config.networkCard = 3;
        config.soundCard = 2;
        config.accel = DeviceUtils.is64bit() ? 2 : 1;
        config.qmpPort = VMManager.startRandomPort();
        config.guestRoot = rootSwitch.isChecked();
        config.wifi = true; // shows a Wi-Fi switch inside the virtual phone

        if (!FileUtils.isFileExists(AppConfig.romsdatajson)) {
            FileUtils.writeToFile(AppConfig.maindirpath, "roms-data.json", "[]");
        }
        if (!VMManager.addVM(config, -1)) {
            busy = false;
            setIdle("Run", "Could not save the phone. Tap Run to try again.");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("vm_id", vmId).apply();
        status.setText("Starting…");
        MainStartVM.startNow(this, config);
    }
}
