package com.vectras.vm.Fragment;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;

import com.vectras.qemu.MainSettingsManager;
import com.vectras.vm.R;
import com.vectras.vm.databinding.ControlsFragmentBinding;
import com.vectras.vm.x11.AndroidMode;
import com.vectras.vm.x11.X11Activity;

public class ControlersOptionsFragment extends DialogFragment {
    public ControlsFragmentBinding binding;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Dialog alertDialog = new Dialog(getActivity(), R.style.MainDialogTheme);
        alertDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        alertDialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        alertDialog.setContentView(R.layout.fragment_controlers_options);
        alertDialog.findViewById(R.id.gamepadBtn).setOnClickListener(v -> {
            MainSettingsManager.setControlMode(getActivity(), "G");
            binding.desktop.setVisibility(View.GONE);
            binding.gamepad.setVisibility(View.VISIBLE);
            alertDialog.cancel();
        });
        alertDialog.findViewById(R.id.desktopBtn).setOnClickListener(v -> {
            MainSettingsManager.setControlMode(getActivity(), "D");
            binding.desktop.setVisibility(View.VISIBLE);
            binding.gamepad.setVisibility(View.GONE);
            alertDialog.cancel();
        });
        alertDialog.findViewById(R.id.hideBtn).setOnClickListener(v -> {
            MainSettingsManager.setControlMode(getActivity(), "H");
            binding.desktop.setVisibility(View.GONE);
            binding.gamepad.setVisibility(View.GONE);
            alertDialog.cancel();
        });

        if (binding != null) {
            alertDialog.findViewById(R.id.hide_all).setVisibility(View.VISIBLE);

            alertDialog.findViewById(R.id.hide_all).setOnClickListener(v -> {
                binding.mainControl.setVisibility(View.GONE);
                alertDialog.cancel();
            });
        }
        // "Customize" button: choose which keys / buttons are shown (built-in X11 screen only).
        if (getActivity() instanceof X11Activity) {
            View hideBtn = alertDialog.findViewById(R.id.hideBtn);
            if (hideBtn != null && hideBtn.getParent() instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) hideBtn.getParent();
                ImageButton custom = new ImageButton(getActivity());
                float d = getResources().getDisplayMetrics().density;
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (100 * d), (int) (100 * d));
                lp.setMargins((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
                custom.setLayoutParams(lp);
                custom.setBackgroundResource(R.drawable.controls_button);
                custom.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                custom.setImageResource(R.drawable.settings_24px);
                android.util.TypedValue tv = new android.util.TypedValue();
                getActivity().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true);
                custom.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tv.data));
                custom.setColorFilter(tv.data);
                custom.setOnClickListener(v -> {
                    final X11Activity x11 = (X11Activity) getActivity();
                    alertDialog.cancel();
                    AndroidMode.showCustomizeDialog(x11, () -> {
                        if (x11.addIn != null) x11.addIn.applyCustomization();
                    });
                });
                row.addView(custom);
            }
        }

        alertDialog.show();
        return alertDialog;
    }

    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (setOnDismiss != null) setOnDismiss.run();
    }

    public Runnable setOnDismiss;
}