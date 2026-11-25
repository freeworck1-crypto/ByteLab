package com.vectras.vm.container;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;

/** Details + Install / Open / Snapshot / Restore / Delete screen for a lightweight Linux rootfs. */
public class ContainerInfoActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "container_id";

    private ContainerEntry entry;
    private TextView status;
    private Button main;
    private Button delete;
    private Button snapshot;
    private Button restore;
    private Switch rootSwitch;
    private boolean busy = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        entry = ContainerManager.find(this, getIntent().getStringExtra(EXTRA_ID));
        if (entry == null) {
            Toast.makeText(this, "Container not found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle(entry.name);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(entry.name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView meta = new TextView(this);
        meta.setText("Lightweight Linux container  •  " + entry.size + "  •  native CPU architecture  •  no QEMU");
        meta.setPadding(0, pad / 3, 0, pad / 2);
        col.addView(meta);

        TextView desc = new TextView(this);
        desc.setText(entry.desc);
        col.addView(desc);

        rootSwitch = new Switch(this);
        rootSwitch.setText("Root inside this container");
        rootSwitch.setChecked(ContainerManager.isRootEnabled(this, entry.id));
        rootSwitch.setPadding(0, pad, 0, 0);
        rootSwitch.setOnCheckedChangeListener((b, on) -> {
            ContainerManager.setRootEnabled(this, entry.id, on);
            Toast.makeText(this, on ? "Container root ON — applies next launch." : "Container root OFF — normal user next launch.", Toast.LENGTH_SHORT).show();
        });
        col.addView(rootSwitch);

        TextView rootNote = new TextView(this);
        rootNote.setText("Container root is provided by PRoot (-0). It does not root Android or the real phone. Linux namespace acceleration is not assumed on stock Android.");
        rootNote.setTextSize(12);
        col.addView(rootNote);

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad / 2);
        col.addView(status);

        main = new Button(this);
        main.setOnClickListener(v -> onMain());
        col.addView(main);

        snapshot = new Button(this);
        snapshot.setText("Create snapshot");
        snapshot.setOnClickListener(v -> confirmSnapshot());
        col.addView(snapshot);

        restore = new Button(this);
        restore.setText("Restore last snapshot");
        restore.setOnClickListener(v -> confirmRestore());
        col.addView(restore);

        delete = new Button(this);
        delete.setText("Delete container");
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.name + "?")
                .setMessage("Everything inside this container is removed. Existing snapshots are kept.")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    ContainerManager.delete(this, entry.id);
                    ui.post(this::refresh);
                }).start())
                .setNegativeButton("Cancel", null).show());
        col.addView(delete);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        sv.setFillViewport(true);
        setContentView(sv);
        ViewCompat.setOnApplyWindowInsetsListener(sv, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        refresh();
    }

    private void refresh() {
        boolean installed = ContainerManager.isInstalled(this, entry.id);
        boolean hasSnapshot = ContainerSnapshotManager.snapshotPath(this, entry.id).isFile();
        main.setText(busy ? "Please wait…" : installed ? "OPEN" : "INSTALL (" + entry.size + ")");
        main.setEnabled(!busy);
        snapshot.setVisibility(installed && !busy ? View.VISIBLE : View.GONE);
        restore.setVisibility(installed && hasSnapshot && !busy ? View.VISIBLE : View.GONE);
        delete.setVisibility(installed && !busy ? View.VISIBLE : View.GONE);
        rootSwitch.setVisibility(entry.rootSupported ? View.VISIBLE : View.GONE);
        if (!busy && status.getText().length() == 0)
            status.setText(installed ? "Installed — tap Open to start." : "Not installed yet.");
    }

    private void confirmSnapshot() {
        new AlertDialog.Builder(this).setTitle("Create container snapshot?")
                .setMessage("Close the Linux terminal/session first. The snapshot saves the root filesystem, permissions and symlinks. It does not save a running process or RAM state.")
                .setPositiveButton("Create", (d, w) -> runSnapshot(false))
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this).setTitle("Restore snapshot?")
                .setMessage("Close the Linux terminal/session first. The current rootfs will be replaced by the saved snapshot. Changes made after that snapshot will be lost.")
                .setPositiveButton("Restore", (d, w) -> runSnapshot(true))
                .setNegativeButton("Cancel", null).show();
    }

    private void runSnapshot(boolean doRestore) {
        if (busy) return;
        busy = true;
        refresh();
        status.setGravity(Gravity.START);
        status.setText(doRestore ? "Verifying and restoring snapshot…" : "Creating snapshot…");
        new Thread(() -> {
            String result;
            try {
                if (doRestore) {
                    ContainerSnapshotManager.restore(ContainerSnapshotManager.snapshotPath(this, entry.id), ContainerManager.rootfs(this, entry.id));
                    result = "Snapshot restored successfully.";
                } else {
                    File created = ContainerSnapshotManager.create(this, entry.id, ContainerManager.rootfs(this, entry.id));
                    result = "Snapshot saved: " + created.getName();
                }
            } catch (Exception e) {
                result = "Snapshot operation failed: " + e.getMessage();
            }
            final String message = result;
            ui.post(() -> {
                busy = false;
                status.setText(message);
                refresh();
            });
        }).start();
    }

    private void onMain() {
        if (ContainerManager.isInstalled(this, entry.id)) {
            try { ContainerManager.open(this, entry); }
            catch (Exception e) { Toast.makeText(this, "Could not open: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            return;
        }
        busy = true;
        refresh();
        status.setGravity(Gravity.START);
        new Thread(() -> ContainerManager.install(getApplicationContext(), entry, new ContainerManager.Progress() {
            @Override public void onStatus(String message) { ui.post(() -> status.setText(message)); }
            @Override public void onDone(boolean ok, String message) {
                ui.post(() -> {
                    busy = false;
                    status.setText(message);
                    refresh();
                    if (ok && !isFinishing() && !isDestroyed()) {
                        try { ContainerManager.open(ContainerInfoActivity.this, entry); }
                        catch (Exception e) { Toast.makeText(ContainerInfoActivity.this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                    }
                });
            }
        })).start();
    }
}
