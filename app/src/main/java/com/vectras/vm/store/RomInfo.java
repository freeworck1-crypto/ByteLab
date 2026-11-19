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
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.vectras.qemu.MainSettingsManager;
import com.vectras.vm.ImagePrvActivity;
import com.vectras.vm.R;
import com.vectras.vm.VMManager;
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
        long savedId = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getLong("active_download_id", -1L);
        if (savedId >= 0) {
            activeDownloadId = savedId;
            checkPendingDownload(savedId);
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

        binding.btnDownload.setOnClickListener(v -> download());

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
            binding.descTxt.setText(getIntent().getStringExtra("desc"));
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

        // If the file was already downloaded, continue straight to import/install.
        boolean completedBefore = getSharedPreferences("rom_store_downloads", MODE_PRIVATE)
                .getBoolean("complete_" + fileName, false);
        if (completedBefore && destination.isFile() && destination.length() > 0) {
            if (isApp) installDownloadedApk(destination);
            else handlePickedRom(Uri.fromFile(destination));
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
                    .putLong("active_download_id", activeDownloadId)
                    .putString("active_filename", fileName)
                    .putBoolean("complete_" + fileName, false)
                    .apply();
            binding.btnDownload.setEnabled(false);
            binding.btnDownload.setText("Downloading…");
            Toast.makeText(this, "Download started. You can follow its progress in notifications.", Toast.LENGTH_LONG).show();
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
                getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                        .remove("active_download_id").remove("active_filename").apply();
                Toast.makeText(this, "Download failed (code " + reason + "). Please try again.", Toast.LENGTH_LONG).show();
                binding.btnDownload.setEnabled(true);
                binding.btnDownload.setText(R.string.getit);
                return;
            }
            String requestedName = getIntent().getStringExtra("filename");
            File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File downloaded = downloads == null || requestedName == null ? null
                    : new File(downloads, new File(requestedName).getName());
            if (downloaded == null || !downloaded.isFile() || downloaded.length() == 0) {
                Toast.makeText(this, "The downloaded file could not be found.", Toast.LENGTH_LONG).show();
                binding.btnDownload.setEnabled(true);
                binding.btnDownload.setText(R.string.getit);
                return;
            }
            getSharedPreferences("rom_store_downloads", MODE_PRIVATE).edit()
                    .remove("active_download_id").remove("active_filename")
                    .putBoolean("complete_" + new File(requestedName).getName(), true)
                    .apply();
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText(R.string.getit);
            if (getIntent().getBooleanExtra("romApp", false)) {
                installDownloadedApk(downloaded);
            } else {
                Toast.makeText(this, "Download complete. Importing into ByteLab…", Toast.LENGTH_SHORT).show();
                handlePickedRom(Uri.fromFile(downloaded));
            }
        } catch (Exception e) {
            binding.btnDownload.setEnabled(true);
            binding.btnDownload.setText(R.string.getit);
            Toast.makeText(this, "Could not finish download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (cursor != null) cursor.close();
        }
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
            case "i386" -> getString(R.string.i386_qemu);
            case "ARM64" -> getString(R.string.arm64_qemu);
            case "PPC" -> getString(R.string.powerpc_qemu);
            default -> getString(R.string.unknow);
        };
    }
}
