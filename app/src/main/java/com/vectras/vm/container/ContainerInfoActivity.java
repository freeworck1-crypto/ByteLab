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

/** Details + Install / Open / Delete screen for one lightweight container. Built in code (no layout file). */
public class ContainerInfoActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "container_id";

    private ContainerEntry entry;
    private TextView status;
    private Button main;
    private Button delete;
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
        meta.setText("Lightweight container  •  download " + entry.size + "  •  no full OS image");
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
            Toast.makeText(this, on ? "Root ON — applies next time you open it." : "Root OFF — you will be a normal user next time.", Toast.LENGTH_SHORT).show();
        });
        col.addView(rootSwitch);

        TextView rootNote = new TextView(this);
        rootNote.setText("This is root inside the container only (like proot -0). Your real phone is never rooted and its files stay protected.");
        rootNote.setTextSize(12);
        col.addView(rootNote);

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad / 2);
        col.addView(status);

        main = new Button(this);
        main.setOnClickListener(v -> onMain());
        col.addView(main);

        delete = new Button(this);
        delete.setText("Delete container");
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.name + "?")
                .setMessage("Everything inside this container is removed.")
                .setPositiveButton("Delete", (d, w) -> {
                    new Thread(() -> {
                        ContainerManager.delete(this, entry.id);
                        ui.post(this::refresh);
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show());
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
        main.setText(busy ? "Please wait…" : installed ? "OPEN" : "INSTALL (" + entry.size + ")");
        main.setEnabled(!busy);
        delete.setVisibility(installed && !busy ? View.VISIBLE : View.GONE);
        rootSwitch.setVisibility(entry.rootSupported ? View.VISIBLE : View.GONE);
        if (!busy) status.setText(installed ? "Installed — tap Open to start it instantly." : "Not installed yet.");
    }

    private void onMain() {
        if (ContainerManager.isInstalled(this, entry.id)) {
            try {
                ContainerManager.open(this, entry);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
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
