package com.vectras.vm.store;

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.app.DownloadManager;
import android.database.Cursor;
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
    private DownloadManager downloadManager;
    private long activeDownloadId = -1L;
    private boolean downloadReceiverRegistered = false;
    private final Handler downloadHandler = new Handler(Looper.getMainLooper());
    private final Runnable downloadPoller = new Runnable() {
        @Override public void run() {
            if (activeDownloadId >= 0) {
                refreshDownloadState(activeDownloadId);
                downloadHandler.postDelayed(this, 900);
            }
        }
    };
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (completedId != activeDownloadId || completedId < 0) return;
            finishRomDownload(completedId);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityRomInfoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        registerDownloadReceiver();

        initialize();
    }

    public void onResume() {
        super.onResume();
        if (isFinishNow) {
            finish();
            isFinishNow = false;
            return;
        }
        String key = downloadKey();
        long savedId = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getLong("active_download_id_" + key, -1L);
        String fileName = getIntent().getStringExtra("filename");
        boolean completed = fileName != null && getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getBoolean("complete_" + new File(fileName).getName(), false);
        if (completed) {
            showCompletedState();
        } else if (savedId >= 0) {
            activeDownloadId = savedId;
            refreshDownloadState(savedId);
            downloadHandler.removeCallbacks(downloadPoller);
            downloadHandler.post(downloadPoller);
        }
    }

    private String downloadKey() {
        String id = getIntent().getStringExtra("id");
        if (id == null || id.trim().isEmpty()) id = getIntent().getStringExtra("filename");
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void showCompletedState() {
        binding.downloadProgressContainer.setVisibility(View.VISIBLE);
        binding.progressDownload.setProgress(100);
        binding.tvDownloadStatus.setText("Download complete — ready to import");
        binding.btnDownload.setEnabled(true);
        binding.btnDownload.setText(getIntent().getBooleanExtra("romApp", false) ? "INSTALL" : "RUN");
    }

    private void refreshDownloadState(long downloadId) {
        if (downloadManager == null || isFinishing() || isDestroyed()) return;
        Cursor cursor = null;
        try {
            cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor == null || !cursor.moveToFirst()) return;
            int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
            int soFarCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int status = statusCol >= 0 ? cursor.getInt(statusCol) : -1;
            long soFar = soFarCol >= 0 ? cursor.getLong(soFarCol) : 0;
            long total = totalCol >= 0 ? cursor.getLong(totalCol) : -1;
            binding.downloadProgressContainer.setVisibility(View.VISIBLE);
            binding.btnDownload.setEnabled(false);
            if (status == DownloadManager.STATUS_PENDING) {
                binding.btnDownload.setText("WAITING");
                binding.progressDownload.setIndeterminate(true);
                binding.tvDownloadStatus.setText("Waiting to start…");
            } else if (status == DownloadManager.STATUS_RUNNING) {
                binding.btnDownload.setText("DOWNLOADING");
                binding.progressDownload.setIndeterminate(false);
                int percent = total > 0 ? (int) Math.min(100, (soFar * 100L) / total) : 0;
                binding.progressDownload.setProgress(percent);
                binding.tvDownloadStatus.setText(total > 0
                        ? percent + "%  •  " + android.text.format.Formatter.formatFileSize(this, soFar)
                            + " / " + android.text.format.Formatter.formatFileSize(this, total)
                        : android.text.format.Formatter.formatFileSize(this, soFar) + " downloaded");
            } else if (status == DownloadManager.STATUS_PAUSED) {
                int reason = reasonCol >= 0 ? cursor.getInt(reasonCol) : -1;
                binding.btnDownload.setText("WAITING");
                binding.progressDownload.setIndeterminate(true);
                binding.tvDownloadStatus.setText(reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
                        || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                        ? "Waiting for internet connection… Download will resume automatically."
                        : "Download paused. Waiting to resume…");
            } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                finishRomDownload(downloadId);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = reasonCol >= 0 ? cursor.getInt(reasonCol) : -1;
                downloadHandler.removeCallbacks(downloadPoller);
                activeDownloadId = -1L;
                getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                        .remove("active_download_id_" + downloadKey()).remove("active_filename").apply();
                binding.btnDownload.setEnabled(true);
                binding.btnDownload.setText("RETRY");
                binding.progressDownload.setIndeterminate(false);
                binding.tvDownloadStatus.setText("Download failed (" + reason + "). Tap Retry.");
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void checkPendingDownload(long downloadId) {
        if (downloadManager == null) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        Cursor cursor = null;
        try {
            cursor = downloadManager.query(query);
            if (cursor == null || !cursor.moveToFirst()) return;
            int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (index < 0) return;
            int status = cursor.getInt(index);
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                finishRomDownload(downloadId);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public void onDestroy() {
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) { }
            downloadReceiverRegistered = false;
        }
        downloadHandler.removeCallbacks(downloadPoller);
        executor.shutdown();
        super.onDestroy();
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
            downloadReceiverRegistered = true;
        } catch (Exception ignored) {
            downloadReceiverRegistered = false;
        }
    }

    private final ActivityResultLauncher<String> romPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::handlePickedRom);

    public void handlePickedRom(Uri uri) {
        if (uri == null) return;

        executor.execute(() -> {
            String filePath;
            try {
                File selectedFilePath = new File(getPath(uri));
                filePath = selectedFilePath.getPath();
            } catch (Exception e) {
                filePath = "";
            }

            String selectedFileName = FileUtils.getFileNameFromUri(this, uri);

            if (isFinishing() || isDestroyed()) return;

            String finalFilePath = filePath;
            runOnUiThread(() -> {
                if (selectedFileName.equals(getIntent().getStringExtra("filename")) ||
                        (selectedFileName.endsWith(".cvbi.zip") && selectedFileName.equals(getIntent().getStringExtra("filename") + ".zip"))) {
                    Intent intent = new Intent();

                    if (getIntent().hasExtra("icon") &&
                            !Objects.requireNonNull(getIntent().getStringExtra("icon")).isEmpty() &&
                            (!selectedFileName.endsWith(".cvbi")
                                    || !selectedFileName.endsWith(".cvbi.zip"))) {

                        Glide.with(this)
                                .asBitmap()
                                .load(getIntent().getStringExtra("icon"))
                                .into(new CustomTarget<Bitmap>() {
                                    @Override
                                    public void onResourceReady(@NonNull Bitmap resource,
                                                                @Nullable Transition<? super Bitmap> transition) {
                                        File cacheDir = getExternalCacheDir();

                                        if (cacheDir != null)
                                            ImageUtils.saveBitmapToPNGFile(resource, cacheDir.getAbsolutePath(), "thumbnail.png");

                                        intent.putExtra("romicon", cacheDir != null ? cacheDir.getAbsolutePath() + "/thumbnail.png" : "");
                                    }

                                    @Override
                                    public void onLoadCleared(@Nullable Drawable placeholder) {
                                        intent.putExtra("romicon", "");
                                    }
                                });
                    } else {
                        intent.putExtra("romicon", "");
                    }

                    intent.setClass(getApplicationContext(), VMCreatorActivity.class);
                    intent.putExtra("addromnow", "");
                    intent.putExtra("romname", getIntent().getStringExtra("title"));
                    intent.putExtra("romfilename", getIntent().getStringExtra("filename"));
                    intent.putExtra("finalromfilename", getIntent().getStringExtra("finalromfilename"));
                    intent.putExtra("rompath", finalFilePath);
                    intent.putExtra("romuri", uri.toString());
                    if (Objects.requireNonNull(getIntent().getStringExtra("extra")).contains(selectedFileName)) {
                        intent.putExtra("addtodrive", "");
                        intent.putExtra("romextra", getIntent().getStringExtra("extra"));
                    } else {
                        intent.putExtra("addtodrive", "1");
                        intent.putExtra("romextra", getIntent().getStringExtra("extra"));
                    }

                    VMManager.setArch(getIntent().hasExtra("arch") && getIntent().getStringExtra("arch") != null ? Objects.requireNonNull(getIntent().getStringExtra("arch")) : "", this);

                    startActivity(intent);
                } else {
                    DialogUtils.oneDialog(RomInfo.this,
                            getString(R.string.problem_has_been_detected),
                            getString(R.string.please_select) + " " + getIntent().getStringExtra("filename"),
                            getString(R.string.ok),
                            true, R.drawable.warning_48px,
                            true,
                            null,
                            null);
                }
            });
        });
    }

    public String getPath(Uri uri) {
        return FileUtils.getPath(RomInfo.this, uri);
    }

    private void initialize() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.btnDownload.setOnClickListener(v -> {
            String filename = getIntent().getStringExtra("filename");
            boolean complete = filename != null && getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                    .getBoolean("complete_" + new File(filename).getName(), false);
            if (complete) {
                if (getIntent().getBooleanExtra("romApp", false)) {
                    File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (folder != null) installDownloadedApk(new File(folder, new File(filename).getName()));
                } else {
                    File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (folder != null) launchDownloadedRom(new File(folder, new File(filename).getName()));
                }
            } else {
                download();
            }
        });

        if (getIntent().hasExtra("isRomInfo") && getIntent().getBooleanExtra("isRomInfo", false)) {
            if (getIntent().getBooleanExtra("romApp", false)) {
                binding.btnPick.setVisibility(View.GONE);
            }
            binding.btnPick.setOnClickListener(v -> {
                try {
                    if (MainSettingsManager.getBuiltInFilePicker(this)) {
                        FilePickerDialog filePickerDialog = new FilePickerDialog();
                        filePickerDialog.pick(this, filePickerDialog.ROM_FILE, (path -> handlePickedRom(Uri.fromFile(new File(path)))));
                    } else {
                        romPicker.launch("*/*");
                    }
                } catch (Exception e) {
                    IntentUtils.showErrorDialog(this);
                }
            });
        } else {
            binding.btnPick.setVisibility(View.GONE);
        }

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
                    ? "\\n\\nGuest root: This image is marked as root-enabled."
                    : "\\n\\nGuest root: Not included in this image. Root access depends on the guest OS image.";
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

        // Web pages (for example Microsoft's ISO selector) are not direct files.
        // Keep their official page flow instead of silently saving an HTML page as an ISO.
        if (!isDirect) {
            IntentUtils.openUrl(this, url);
            return;
        }

        String requestedName = getIntent().getStringExtra("filename");
        if (url == null || requestedName == null || requestedName.trim().isEmpty()) {
            Toast.makeText(this, "Download information is incomplete.", Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = new File(requestedName).getName();
        File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) {
            Toast.makeText(this, "Download storage is unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!downloads.exists() && !downloads.mkdirs()) {
            Toast.makeText(this, "Could not create download folder.", Toast.LENGTH_LONG).show();
            return;
        }
        File destination = new File(downloads, fileName);
        long existingId = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getLong("active_download_id_" + downloadKey(), -1L);
        if (existingId >= 0) {
            activeDownloadId = existingId;
            refreshDownloadState(existingId);
            downloadHandler.removeCallbacks(downloadPoller);
            downloadHandler.post(downloadPoller);
            return;
        }

        // If the file was already downloaded, continue straight to import/install.
        boolean completedBefore = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getBoolean("complete_" + fileName, false);
        if (completedBefore && destination.isFile() && destination.length() > 0) {
            showCompletedState();
            return;
        }
        if (destination.exists()) destination.delete();

        try {
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                Toast.makeText(this, "This download link is not supported.", Toast.LENGTH_LONG).show();
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(parsed);
            request.setTitle(getIntent().getStringExtra("title"));
            request.setDescription("Downloading OS image for ByteLab");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            activeDownloadId = downloadManager.enqueue(request);
            getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                    .putLong("active_download_id_" + downloadKey(), activeDownloadId)
                    .putString("active_filename", fileName)
                    .putBoolean("complete_" + fileName, false)
                    .apply();
            binding.downloadProgressContainer.setVisibility(View.VISIBLE);
            binding.btnDownload.setEnabled(false);
            binding.btnDownload.setText("DOWNLOADING");
            binding.progressDownload.setIndeterminate(true);
            binding.tvDownloadStatus.setText("Connecting to download server…");
            downloadHandler.removeCallbacks(downloadPoller);
            downloadHandler.post(downloadPoller);
            Toast.makeText(this, "Download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText(R.string.getit);
            Toast.makeText(this, "Could not start download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void finishRomDownload(long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        Cursor cursor = null;
        try {
            cursor = downloadManager.query(query);
            if (cursor == null || !cursor.moveToFirst()) return;
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
            int status = statusIndex >= 0 ? cursor.getInt(statusIndex) : -1;
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED) return;
                int reason = reasonIndex >= 0 ? cursor.getInt(reasonIndex) : -1;
                downloadHandler.removeCallbacks(downloadPoller);
                activeDownloadId = -1L;
                getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                        .remove("active_download_id_" + downloadKey()).remove("active_filename").apply();
                Toast.makeText(this, "Download failed (code " + reason + "). Please try again.", Toast.LENGTH_LONG).show();
                binding.btnDownload.setEnabled(true);
                binding.btnDownload.setText("RETRY");
                binding.tvDownloadStatus.setText("Download failed. Tap Retry.");
                return;
            }
            String requestedName = getIntent().getStringExtra("filename");
            File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File downloaded = downloads == null || requestedName == null ? null
                    : new File(downloads, new File(requestedName).getName());
            if (downloaded == null || !downloaded.isFile() || downloaded.length() == 0) {
                Toast.makeText(this, "The downloaded file could not be found.", Toast.LENGTH_LONG).show();
                binding.btnDownload.setEnabled(true);
                binding.btnDownload.setText("RETRY");
                binding.tvDownloadStatus.setText("Download failed. Tap Retry.");
                return;
            }
            downloadHandler.removeCallbacks(downloadPoller);
            activeDownloadId = -1L;
            getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                    .remove("active_download_id_" + downloadKey()).remove("active_filename")
                    .putBoolean("complete_" + new File(requestedName).getName(), true)
                    .apply();
            downloadHandler.removeCallbacks(downloadPoller);
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText(getIntent().getBooleanExtra("romApp", false) ? "INSTALL" : "RUN");
            binding.downloadProgressContainer.setVisibility(View.VISIBLE);
            binding.progressDownload.setIndeterminate(false);
            binding.progressDownload.setProgress(100);
            binding.tvDownloadStatus.setText("Download complete — preparing virtual machine…");

            if (getIntent().getBooleanExtra("romApp", false)) {
                binding.tvDownloadStatus.setText("Download complete — ready to install");
                Toast.makeText(this, "Download complete. Tap Install.", Toast.LENGTH_LONG).show();
            } else {
                // No picker, import screen, or manual file copy: attach the downloaded
                // ISO as a virtual CD-ROM and start a new VM automatically.
                launchDownloadedRom(downloaded);
            }
        } catch (Exception e) {
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText(R.string.getit);
            Toast.makeText(this, "Could not finish download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (cursor != null) cursor.close();
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
        config.itemExtra = vmDisk == null ? "" : "-boot order=dc -vga std";
        config.memory = getIntent().getStringExtra("romCategory") != null
                && "windows".equalsIgnoreCase(getIntent().getStringExtra("romCategory")) ? 4096 : 2048;
        config.cores = 1;
        config.graphicCard = 1;
        config.networkCard = 3;
        config.soundCard = 2;
        config.accel = DeviceUtils.is64bit() ? 2 : 1;
        config.qmpPort = VMManager.startRandomPort();

        if (!FileUtils.isFileExists(AppConfig.romsdatajson)) {
            FileUtils.writeToFile(AppConfig.maindirpath, "roms-data.json", "[]");
        }

        if (!VMManager.addVM(config, -1)) {
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
