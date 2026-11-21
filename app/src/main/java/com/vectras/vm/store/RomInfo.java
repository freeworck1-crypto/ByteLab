package com.vectras.vm.store;

import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.Gson;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.vectras.qemu.MainSettingsManager;
import com.vectras.vm.AppConfig;
import com.vectras.vm.ImagePrvActivity;
import com.vectras.vm.R;
import com.vectras.vm.VMManager;
import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.main.vms.DataMainRoms;
import com.vectras.vm.manager.VmFileManager;
import com.vectras.vterm.Terminal2;
import com.vectras.vm.utils.DeviceUtils;
import com.vectras.vm.creator.VMCreatorActivity;
import com.vectras.vm.databinding.ActivityRomInfoBinding;
import com.vectras.vm.file.FilePickerDialog;
import com.vectras.vm.utils.DialogUtils;
import com.vectras.vm.utils.FileUtils;
import com.vectras.vm.utils.ImageUtils;
import com.vectras.vm.utils.IntentUtils;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RomInfo extends AppCompatActivity {
    ActivityRomInfoBinding binding;
    public static boolean isFinishNow = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final com.vectras.vm.utils.FastDownloader.Listener downloadListener = this::renderDownloadState;
    private boolean launching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityRomInfoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        initialize();
    }

    public void onResume() {
        super.onResume();
        if (isFinishNow) {
            finish();
            isFinishNow = false;
            return;
        }
        if (launching) return;

        String key = downloadKey();
        // A download may still be running in the background; re-attach to it.
        if (com.vectras.vm.utils.FastDownloader.get().attach(key, downloadListener)) return;

        File target = targetFile();
        if (isDownloadedFile(target)) {
            showCompletedState();
        } else if (target != null && new File(target.getParentFile(), target.getName() + ".part").isFile()) {
            binding.btnDownload.setText("RESUME");
            binding.downloadProgressContainer.setVisibility(View.VISIBLE);
            binding.tvDownloadStatus.setText("Partly downloaded — tap Resume to continue.");
        }
    }

    public void onDestroy() {
        com.vectras.vm.utils.FastDownloader.get().detach(downloadKey(), downloadListener);
        executor.shutdown();
        super.onDestroy();
    }

    private String downloadKey() {
        String id = getIntent().getStringExtra("id");
        if (id == null || id.trim().isEmpty()) id = getIntent().getStringExtra("filename");
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @Nullable
    private File targetFile() {
        String requestedName = getIntent().getStringExtra("filename");
        File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (requestedName == null || requestedName.trim().isEmpty() || downloads == null) return null;
        return new File(downloads, new File(requestedName).getName());
    }

    /** A finished download is the real file with no ".part" file next to it. */
    private boolean isDownloadedFile(@Nullable File target) {
        return target != null && target.isFile() && target.length() > 0
                && !new File(target.getParentFile(), target.getName() + ".part").exists();
    }

    private void showCompletedState() {
        binding.downloadProgressContainer.setVisibility(View.VISIBLE);
        binding.progressDownload.setIndeterminate(false);
        binding.progressDownload.setProgress(100);
        binding.tvDownloadStatus.setText("Download complete — tap Run to start");
        binding.btnDownload.setEnabled(true);
        binding.btnDownload.setText(getIntent().getBooleanExtra("romApp", false) ? "INSTALL" : "RUN");
    }

    private void onDownloadButton() {
        String key = downloadKey();
        if (com.vectras.vm.utils.FastDownloader.get().isActive(key)) {
            com.vectras.vm.utils.FastDownloader.get().cancel(key); // pause; partial data is kept
            return;
        }
        File target = targetFile();
        if (isDownloadedFile(target)) {
            if (getIntent().getBooleanExtra("romApp", false)) installDownloadedApk(target);
            else launchDownloadedRom(target);
        } else {
            download();
        }
    }

    private void initialize() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.btnDownload.setOnClickListener(v -> onDownloadButton());

        // Importing a file is done from the "+" button on the home screen, not from the store.
        binding.btnPick.setVisibility(View.GONE);

        if (getIntent().hasExtra("title")) {
            binding.textName.setText(getIntent().getStringExtra("title"));
        }

        if (getIntent().hasExtra("shortdesc")) {
            binding.textSize.setText(getIntent().getStringExtra("shortdesc"));
        }

        if (getIntent().hasExtra("desc")) {
            String description = getIntent().getStringExtra("desc");
            boolean rootSupported = getIntent().getBooleanExtra("rootSupported", false);
            String rootStatus = rootSupported
                    ? "\n\nRoot: available inside this virtual machine only. After it starts, long-press it on the home screen and choose \"Root (guest only)\". Your real phone is never rooted."
                    : "\n\nRoot: can be enabled for this virtual machine only (long-press it on the home screen → Root (guest only)). Your real phone is never rooted.";
            binding.descTxt.setText((description == null ? "" : description) + rootStatus);
        }

        if (getIntent().hasExtra("icon")) {
            Glide.with(this).load(getIntent().getStringExtra("icon")).placeholder(R.drawable.ic_computer_180dp_with_padding).error(R.drawable.ic_computer_180dp_with_padding).into(binding.ivIcon);
        }

        int currentVerifyIcon = R.drawable.verified_user_24px;
        String currentVerifyText = getString(R.string.verified);
        String currentVerifyContent = getString(R.string.verified_content);

        if (getIntent().hasExtra("verified")) {
            if (!getIntent().getBooleanExtra("verified", false)) {
                binding.ivVerified.setImageResource(R.drawable.gpp_maybe_24px);
                binding.tvVerified.setText(getString(R.string.not_verified));

                currentVerifyIcon = R.drawable.gpp_maybe_24px;
                currentVerifyText = getString(R.string.not_verified);
                currentVerifyContent = getString(R.string.not_verified_content);
            }
        }

        if (getIntent().hasExtra("creator")) {
            binding.tvCreator.setText(getIntent().getStringExtra("creator"));
        }

        binding.tvArch.setText(getArchName(Objects.requireNonNull(getIntent().getStringExtra("arch"))));

        if (getIntent().hasExtra("size")) {
            binding.tvSize.setText(getIntent().getStringExtra("size"));
        }

        if (getIntent().hasExtra("filename")) {
            binding.tvFilename.setText(getIntent().getStringExtra("filename"));
        }

        binding.ivIcon.setOnClickListener(v -> {
            if (getIntent().hasExtra("icon")) {
                Intent intent = new Intent();
                intent.putExtra("uri", getIntent().getStringExtra("icon"));
                intent.setClass(getApplicationContext(), ImagePrvActivity.class);
                startActivity(intent);
            }
        });

        String finalCurrentVerifyText = currentVerifyText;
        String finalCurrentVerifyContent = currentVerifyContent;
        int finalCurrentVerifyIcon = currentVerifyIcon;
        binding.lnVerified.setOnClickListener((v -> DialogUtils.oneDialog(
                RomInfo.this,
                finalCurrentVerifyText,
                finalCurrentVerifyContent,
                getString(R.string.ok),
                true,
                finalCurrentVerifyIcon,
                true,
                null,
                null)));

        binding.lnCreator.setOnClickListener((v -> DialogUtils.oneDialog(
                RomInfo.this,
                getString(getIntent().hasExtra("isRomInfo") && getIntent().getBooleanExtra("isRomInfo", false) ? R.string.who_created_this_rom : R.string.shared_by),
                getIntent().getStringExtra("creator") + ".",
                getString(R.string.ok),
                true,
                R.drawable.account_circle_24px,
                true,
                null,
                null)));

        binding.lnArch.setOnClickListener((v -> DialogUtils.oneDialog(
                RomInfo.this,
                getString(R.string.architecture),
                getArchName(Objects.requireNonNull(getIntent().getStringExtra("arch"))) + ".",
                getString(R.string.ok),
                true,
                R.drawable.devices_other_24px,
                true,
                null,
                null)));

        binding.lnSize.setOnClickListener((v -> DialogUtils.oneDialog(
                RomInfo.this,
                getString(R.string.sizetext),
                getIntent().getStringExtra("size") + ".",
                getString(R.string.ok),
                true,
                R.drawable.hard_drive_24px,
                true,
                null,
                null)));

        binding.lnFilename.setOnClickListener((v -> DialogUtils.oneDialog(
                RomInfo.this,
                getString(R.string.file_name),
                getIntent().getStringExtra("filename") + ".",
                getString(R.string.ok),
                true,
                R.drawable.file_copy_24px,
                true,
                null,
                null)));

    }

    private void download() {
        String url = getIntent().getStringExtra("getrom");
        boolean isApp = getIntent().getBooleanExtra("romApp", false);
        boolean isDirect = getIntent().getBooleanExtra("directDownload", false) || isApp;

        // Web pages (for example Microsoft's ISO selector) are not direct files:
        // open the official page instead of saving an HTML page as an ISO.
        if (!isDirect) {
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(this, "This image has no download link yet.", Toast.LENGTH_LONG).show();
                return;
            }
            IntentUtils.openUrl(this, url);
            return;
        }

        File destination = targetFile();
        if (url == null || url.trim().isEmpty() || destination == null) {
            Toast.makeText(this, "Download information is incomplete.", Toast.LENGTH_LONG).show();
            return;
        }
        File downloads = destination.getParentFile();
        if (downloads != null && !downloads.exists() && !downloads.mkdirs()) {
            Toast.makeText(this, "Could not create download folder.", Toast.LENGTH_LONG).show();
            return;
        }

        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            Toast.makeText(this, "This download link is not supported.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] mirrors = getIntent().getStringArrayExtra("mirrors");
        String[] urls = new String[1 + (mirrors == null ? 0 : mirrors.length)];
        urls[0] = url;
        if (mirrors != null) System.arraycopy(mirrors, 0, urls, 1, mirrors.length);

        getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                .putBoolean("complete_" + destination.getName(), false).apply();

        binding.downloadProgressContainer.setVisibility(View.VISIBLE);
        binding.btnDownload.setText("PAUSE");
        binding.progressDownload.setIndeterminate(true);
        binding.tvDownloadStatus.setText("Connecting to download server…");
        com.vectras.vm.utils.FastDownloader.get().start(downloadKey(), urls, destination, downloadListener);
    }

    private void renderDownloadState(com.vectras.vm.utils.FastDownloader.State s) {
        if (isFinishing() || isDestroyed() || binding == null) return;
        binding.downloadProgressContainer.setVisibility(View.VISIBLE);
        binding.btnDownload.setEnabled(true);

        if (s.finished) {
            File downloaded = s.file != null ? s.file : targetFile();
            if (downloaded != null) {
                getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                        .putBoolean("complete_" + downloaded.getName(), true).apply();
            }
            com.vectras.vm.utils.FastDownloader.get().detach(downloadKey(), downloadListener);
            binding.progressDownload.setIndeterminate(false);
            binding.progressDownload.setProgress(100);
            boolean isApp = getIntent().getBooleanExtra("romApp", false);
            binding.btnDownload.setText(isApp ? "INSTALL" : "RUN");
            if (isApp) {
                binding.tvDownloadStatus.setText("Download complete — ready to install");
            } else {
                binding.tvDownloadStatus.setText("Download complete — starting virtual machine…");
                launchDownloadedRom(downloaded);
            }
        } else if (s.failed) {
            binding.progressDownload.setIndeterminate(false);
            binding.btnDownload.setText("RESUME");
            binding.tvDownloadStatus.setText(s.message);
        } else if (s.cancelled) {
            binding.progressDownload.setIndeterminate(false);
            binding.btnDownload.setText("RESUME");
            binding.tvDownloadStatus.setText("Paused at " + s.percent() + "% — tap Resume to continue.");
        } else {
            binding.btnDownload.setText("PAUSE");
            if (s.total > 0 && s.done > 0) {
                binding.progressDownload.setIndeterminate(false);
                binding.progressDownload.setProgress(s.percent());
                String speed = com.vectras.vm.utils.FastDownloader.speedText(s.bytesPerSecond);
                binding.tvDownloadStatus.setText(s.percent() + "%  •  "
                        + android.text.format.Formatter.formatFileSize(this, s.done) + " / "
                        + android.text.format.Formatter.formatFileSize(this, s.total)
                        + (speed.isEmpty() ? "" : "  •  " + speed));
            } else {
                binding.progressDownload.setIndeterminate(true);
                binding.tvDownloadStatus.setText(s.message);
            }
        }
    }

    private void launchDownloadedRom(File downloaded) {
        if (downloaded == null || !downloaded.isFile() || downloaded.length() <= 0) {
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText("RETRY");
            binding.tvDownloadStatus.setText("Downloaded file not found. Tap Retry.");
            Toast.makeText(this, "ByteLab could not find the downloaded image.", Toast.LENGTH_LONG).show();
            return;
        }

        String arch = getIntent().getStringExtra("arch");
        if (arch == null || arch.trim().isEmpty()) arch = "X86_64";
        final String vmArch = arch;
        String format = getIntent().getStringExtra("romFormat");
        if (format == null || format.trim().isEmpty()) {
            format = downloaded.getName().toLowerCase(Locale.ROOT).endsWith(".img") ? "disk" : "iso";
        }

        String savedVmId = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getString("vm_id_" + downloadKey(), "");
        if (!savedVmId.isEmpty() && VMManager.isVMExist(savedVmId)) {
            try {
                DataMainRoms existing = new Gson().fromJson(
                        FileUtils.readAFile(VmFileManager.getConfigFile(savedVmId)),
                        DataMainRoms.class
                );
                if (existing != null) {
                    MainStartVM.startNow(this, existing);
                    finish();
                    return;
                }
            } catch (Exception ignored) { }
        }

        String vmId = VMManager.idGenerator();
        String vmDiskPath = VmFileManager.getPath(vmId, "disk.qcow2");

        if ("disk".equalsIgnoreCase(format)) {
            createAndStartRomVm(downloaded, null, vmId, arch);
            return;
        }

        File vmDisk = new File(vmDiskPath);
        File parent = vmDisk.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Toast.makeText(this, "Could not create the virtual machine folder.", Toast.LENGTH_LONG).show();
            return;
        }

        binding.btnDownload.setEnabled(false);
        binding.btnDownload.setText("PREPARING");
        binding.tvDownloadStatus.setText("Creating a virtual disk…");

        Terminal2 terminal = new Terminal2(this);
        String safeDiskPath = vmDiskPath.replace("'", "'\\''");
        terminal.execute("qemu-img create -f qcow2 '" + safeDiskPath + "' 16G",
                new Terminal2.Terminal2Callback() {
                    @Override
                    public void onRunning(String command, String newLine) { }

                    @Override
                    public void onFinished(String command, String log, int status) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (status != terminal.SUCCESS || !vmDisk.isFile() || vmDisk.length() <= 0) {
                                binding.btnDownload.setEnabled(true);
                                binding.btnDownload.setText("RUN");
                                binding.tvDownloadStatus.setText("Could not prepare virtual disk. Tap Run to retry.");
                                Toast.makeText(RomInfo.this, "Virtual disk creation failed: " + log, Toast.LENGTH_LONG).show();
                                return;
                            }
                            createAndStartRomVm(downloaded, vmDisk, vmId, vmArch);
                        });
                    }

                    @Override
                    public void onError(String command, Exception exception) {
                        runOnUiThread(() -> {
                            binding.btnDownload.setEnabled(true);
                            binding.btnDownload.setText("RUN");
                            binding.tvDownloadStatus.setText("Could not prepare virtual disk. Tap Run to retry.");
                            Toast.makeText(RomInfo.this, "Virtual disk error: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void createAndStartRomVm(File downloaded, File vmDisk, String vmId, String arch) {
        DataMainRoms config = new DataMainRoms();
        config.vmID = vmId;
        config.itemName = getIntent().getStringExtra("title");
        config.itemIcon = "";
        config.itemArch = arch;
        config.itemPath = vmDisk == null ? downloaded.getAbsolutePath() : vmDisk.getAbsolutePath();
        config.imgCdrom = vmDisk == null ? "" : downloaded.getAbsolutePath();
        config.itemExtra = vmDisk == null ? "" : "-boot order=cd -vga std";
        config.memory = getIntent().getStringExtra("romCategory") != null
                && "windows".equalsIgnoreCase(getIntent().getStringExtra("romCategory")) ? 4096 : 2048;
        config.cores = 1;
        config.graphicCard = 1;
        config.networkCard = 3;
        config.soundCard = 2;
        config.accel = DeviceUtils.is64bit() ? 2 : 1;
        config.qmpPort = VMManager.startRandomPort();
        launching = true;

        if (!FileUtils.isFileExists(AppConfig.romsdatajson)) {
            FileUtils.writeToFile(AppConfig.maindirpath, "roms-data.json", "[]");
        }

        if (!VMManager.addVM(config, -1)) {
            launching = false;
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText("RUN");
            binding.tvDownloadStatus.setText("Could not register this virtual machine.");
            Toast.makeText(this, "Could not add the OS to your VM list.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                .putString("vm_id_" + downloadKey(), vmId)
                .apply();
        binding.tvDownloadStatus.setText("Starting " + config.itemName + "…");
        MainStartVM.startNow(this, config);
        finish();
    }

    private void installDownloadedApk(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(settings);
                Toast.makeText(this, "Allow ByteLab to install this downloaded app, then tap Get again.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Enable permission to install unknown apps for ByteLab.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the APK installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private String getArchName(String arch) {
        return switch (arch) {
            case "X86_64" -> getString(R.string.x86_64);
            case "i386", "I386" -> getString(R.string.i386_qemu);
            case "ARM64" -> getString(R.string.arm64_qemu);
            case "PPC" -> getString(R.string.powerpc_qemu);
            default -> getString(R.string.unknow);
        };
    }
}
