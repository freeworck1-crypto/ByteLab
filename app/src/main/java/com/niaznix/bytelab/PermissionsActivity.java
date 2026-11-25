package com.niaznix.bytelab;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.vectras.vm.R;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen where the user can see and grant every permission ByteLab can use.
 * Nothing is requested automatically: each permission is only asked for when the
 * user taps its button.
 */
public class PermissionsActivity extends AppCompatActivity {

    private static final int REQUEST_CODE = 4242;
    private static final String PREFS = "bytelab_permissions";

    private static final int NOT_NEEDED = 0;
    private static final int GRANTED = 1;
    private static final int DENIED = 2;
    private static final int AUTO = 3;

    private static class Row {
        final String kind;
        final int titleRes;
        final int descRes;
        TextView status;
        MaterialButton button;

        Row(String kind, int titleRes, int descRes) {
            this.kind = kind;
            this.titleRes = titleRes;
            this.descRes = descRes;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_permissions);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(R.string.app_permissions);
        heading.setTextSize(24);
        root.addView(heading);

        TextView intro = new TextView(this);
        intro.setText(R.string.app_permissions_intro);
        intro.setPadding(0, dp(4), 0, dp(12));
        root.addView(intro);

        addRow(root, new Row("overlay", R.string.perm_overlay_title, R.string.perm_overlay_desc));
        addRow(root, new Row("install", R.string.perm_install_title, R.string.perm_install_desc));
        addRow(root, new Row("battery", R.string.perm_battery_title, R.string.perm_battery_desc));
        addRow(root, new Row("files", R.string.perm_files_title, R.string.perm_files_desc));
        addRow(root, new Row("camera", R.string.perm_camera_title, R.string.perm_camera_desc));
        addRow(root, new Row("mic", R.string.perm_mic_title, R.string.perm_mic_desc));
        addRow(root, new Row("location", R.string.perm_location_title, R.string.perm_location_desc));
        addRow(root, new Row("nearby", R.string.perm_nearby_title, R.string.perm_nearby_desc));
        addRow(root, new Row("notifications", R.string.perm_notifications_title, R.string.perm_notifications_desc));
        addRow(root, new Row("wifi", R.string.perm_wifi_title, R.string.perm_wifi_desc));
        addRow(root, new Row("adb", R.string.perm_adb_title, R.string.perm_adb_desc));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void addRow(LinearLayout parent, Row row) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(row.titleRes);
        title.setTextSize(17);
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setText(row.descRes);
        desc.setTextSize(13);
        box.addView(desc);

        row.status = new TextView(this);
        row.status.setTextSize(13);
        row.status.setPadding(0, dp(2), 0, 0);
        box.addView(row.status);

        row.button = new MaterialButton(this);
        row.button.setOnClickListener(v -> request(row.kind));
        box.addView(row.button);

        parent.addView(box);
        rows.add(row);
    }

    private void refresh() {
        for (Row row : rows) {
            int state = stateOf(row.kind);
            if (row.kind.equals("adb")) {
                row.status.setVisibility(View.GONE);
                row.button.setVisibility(View.VISIBLE);
                row.button.setText(R.string.perm_button_settings);
                continue;
            }
            row.status.setVisibility(View.VISIBLE);
            switch (state) {
                case GRANTED:
                    row.status.setText(R.string.perm_status_granted);
                    row.button.setVisibility(View.GONE);
                    break;
                case AUTO:
                    row.status.setText(R.string.perm_status_auto);
                    row.button.setVisibility(View.GONE);
                    break;
                case NOT_NEEDED:
                    row.status.setText(R.string.perm_status_not_needed);
                    row.button.setVisibility(View.GONE);
                    break;
                default:
                    row.status.setText(R.string.perm_status_not_granted);
                    row.button.setVisibility(View.VISIBLE);
                    row.button.setText(usesSettingsPage(row.kind)
                            ? R.string.perm_button_settings : R.string.perm_button_allow);
                    break;
            }
        }
    }

    private boolean usesSettingsPage(String kind) {
        return kind.equals("overlay") || kind.equals("install") || kind.equals("battery")
                || (kind.equals("files") && Build.VERSION.SDK_INT >= 30)
                || (kind.equals("notifications") && Build.VERSION.SDK_INT < 33);
    }

    private int stateOf(String kind) {
        switch (kind) {
            case "overlay":
                return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this) ? GRANTED : DENIED;
            case "install":
                if (Build.VERSION.SDK_INT < 26) return AUTO;
                return getPackageManager().canRequestPackageInstalls() ? GRANTED : DENIED;
            case "battery": {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null || Build.VERSION.SDK_INT < 23) return NOT_NEEDED;
                return pm.isIgnoringBatteryOptimizations(getPackageName()) ? GRANTED : DENIED;
            }
            case "files":
                if (Build.VERSION.SDK_INT >= 30) {
                    return Environment.isExternalStorageManager() ? GRANTED : DENIED;
                }
                return has(Manifest.permission.READ_EXTERNAL_STORAGE)
                        && has(Manifest.permission.WRITE_EXTERNAL_STORAGE) ? GRANTED : DENIED;
            case "camera":
                return has(Manifest.permission.CAMERA) ? GRANTED : DENIED;
            case "mic":
                return has(Manifest.permission.RECORD_AUDIO) ? GRANTED : DENIED;
            case "location":
                return has(Manifest.permission.ACCESS_FINE_LOCATION) ? GRANTED : DENIED;
            case "nearby": {
                String[] needed = nearbyPermissions();
                if (needed.length == 0) return NOT_NEEDED;
                for (String p : needed) if (!has(p)) return DENIED;
                return GRANTED;
            }
            case "notifications":
                if (Build.VERSION.SDK_INT >= 33) {
                    return has(Manifest.permission.POST_NOTIFICATIONS) ? GRANTED : DENIED;
                }
                return NotificationManagerCompat.from(this).areNotificationsEnabled() ? GRANTED : DENIED;
            case "wifi":
                return AUTO;
            default:
                return NOT_NEEDED;
        }
    }

    private String[] nearbyPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[0];
    }

    private void request(String kind) {
        switch (kind) {
            case "overlay":
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true);
                break;
            case "install":
                if (Build.VERSION.SDK_INT >= 26) {
                    openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true);
                }
                break;
            case "battery":
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false);
                }
                break;
            case "files":
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, false);
                    }
                } else {
                    askRuntime(kind, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE});
                }
                break;
            case "camera":
                askRuntime(kind, new String[]{Manifest.permission.CAMERA});
                break;
            case "mic":
                askRuntime(kind, new String[]{Manifest.permission.RECORD_AUDIO});
                break;
            case "location":
                askRuntime(kind, new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION});
                break;
            case "nearby":
                askRuntime(kind, nearbyPermissions());
                break;
            case "notifications":
                if (Build.VERSION.SDK_INT >= 33) {
                    askRuntime(kind, new String[]{Manifest.permission.POST_NOTIFICATIONS});
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (Exception e) {
                        openAppDetails();
                    }
                }
                break;
            case "adb":
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                    } catch (Exception ignored) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                }
                break;
            default:
                break;
        }
    }

    private void askRuntime(String kind, String[] permissions) {
        if (permissions.length == 0) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean askedBefore = prefs.getBoolean("asked_" + kind, false);
        boolean blocked = false;
        if (askedBefore) {
            blocked = true;
            for (String p : permissions) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, p)) {
                    blocked = false;
                    break;
                }
            }
        }
        if (blocked) {
            // Android will no longer show its dialog; send the user to the app's settings.
            openAppDetails();
            return;
        }
        prefs.edit().putBoolean("asked_" + kind, true).apply();
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE);
    }

    private void openSettings(String action, boolean withPackage) {
        try {
            Intent intent = new Intent(action);
            if (withPackage) intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            // Nothing else to try.
        }
    }

    private boolean has(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
