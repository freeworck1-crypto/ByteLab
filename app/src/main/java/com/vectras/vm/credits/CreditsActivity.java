package com.vectras.vm.credits;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.appbar.MaterialToolbar;
import com.vectras.vm.AppConfig;
import com.vectras.vm.R;

/**
 * The ONE place in the app that lists third-party software, who made it and under which licence.
 * Nothing else in the app mentions it. Built in code so it needs no layout file.
 */
public class CreditsActivity extends AppCompatActivity {

    // name, author / project, licence, what ByteLab uses it for
    private static final String[][] COMPONENTS = {
            {"Vectras VM for Android", "Noureldeen Elsayed and contributors", "GPL-2.0",
                    "The original application ByteLab was adapted from (VM manager, setup and display code)."},
            {"QEMU", "The QEMU project", "GPL-2.0 and others",
                    "The machine emulator that runs every virtual machine."},
            {"PRoot", "PRoot project", "GPL-2.0+",
                    "Runs the bundled Linux environment without root."},
            {"Alpine Linux", "Alpine Linux Development Team", "Various (mostly MIT / GPL)",
                    "The small Linux environment that hosts QEMU inside the app."},
            {"Termux terminal libraries", "Termux", "Apache-2.0 / GPL-3.0",
                    "Terminal emulator and terminal view."},
            {"Termux:X11 (Lorie)", "Termux:X11 contributors", "GPL-3.0",
                    "Built-in X11 display for desktop-style guests."},
            {"androidVNC / bVNC", "Michael A. MacDonald, antlersoft and contributors", "GPL-2.0",
                    "VNC remote display client."},
            {"EDK II (OVMF / AAVMF)", "TianoCore", "BSD-2-Clause-Patent",
                    "UEFI firmware images used to boot modern guests."},
            {"SeaBIOS", "SeaBIOS project", "LGPL-3.0",
                    "Legacy BIOS firmware image."},
            {"QEMU 3dfx wrappers", "kjliew and contributors", "GPL-2.0",
                    "Optional 3dfx graphics driver disk."},
            {"AndroidX, Material Components", "Google / Android Open Source Project", "Apache-2.0",
                    "User-interface building blocks."},
            {"Guava, Gson", "Google", "Apache-2.0",
                    "Utility and JSON libraries."},
            {"Retrofit", "Square", "Apache-2.0",
                    "Network requests."},
            {"Glide", "Bump Technologies", "BSD-2-Clause / MIT / Apache-2.0",
                    "Image loading."},
            {"Lottie", "Airbnb", "Apache-2.0",
                    "Animations."},
            {"Apache Commons IO and Compress", "The Apache Software Foundation", "Apache-2.0",
                    "File and archive handling."},
            {"Inter typeface", "Rasmus Andersson", "SIL Open Font License 1.1",
                    "The app font."},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);

        final int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
        final int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF444444);
        final int surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        final int card = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, 0xFFEEEEEE);
        final int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF2255AA);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        setContentView(root);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.credits_title);
        toolbar.setNavigationIcon(R.drawable.arrow_back_rounded_24px);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, dp(4), pad, pad);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            scroll.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        // Header: product + builder
        TextView name = text(getString(R.string.app_name), 28, onSurface, true);
        list.addView(name);
        TextView built = text(getString(R.string.credits_built_by), 15, primary, true);
        built.setPadding(0, dp(2), 0, dp(12));
        list.addView(built);
        TextView intro = text(getString(R.string.credits_intro), 14, onSurfaceVariant, false);
        intro.setPadding(0, 0, 0, dp(16));
        list.addView(intro);

        // Components
        for (String[] c : COMPONENTS) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16), dp(14), dp(16), dp(14));
            item.setBackground(roundedBg(card, dp(14)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            item.setLayoutParams(lp);

            item.addView(text(c[0], 16, onSurface, true));
            item.addView(text(c[1], 13, onSurfaceVariant, false));
            TextView licence = text(c[2], 12, primary, true);
            licence.setPadding(0, dp(4), 0, dp(4));
            item.addView(licence);
            item.addView(text(c[3], 13, onSurfaceVariant, false));
            list.addView(item);
        }

        TextView note = text(getString(R.string.credits_licence_note), 13, onSurfaceVariant, false);
        note.setPadding(0, dp(12), 0, dp(8));
        list.addView(note);

        MaterialButton source = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        source.setText(R.string.credits_source_code);
        source.setOnClickListener(v -> openUrl(AppConfig.vectrasRepo));
        list.addView(source);

        MaterialButton libs = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        libs.setText(R.string.credits_all_libraries);
        libs.setOnClickListener(v -> startActivity(new Intent(this, OssLicensesMenuActivity.class)));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        list.addView(libs, lp2);

        TextView footer = text(getString(R.string.credits_footer), 13, onSurfaceVariant, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(24), 0, dp(8));
        list.addView(footer);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private android.graphics.drawable.Drawable roundedBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
