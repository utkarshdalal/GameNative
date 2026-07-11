/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;

import com.winlator.xr.XrActivity;
import com.winlator.contentdialog.ContentDialog;

import app.gamenative.R;

public class XrDialog extends ContentDialog {

    public enum MenuItem {
        SHOW_KEYBOARD,
        TASK_MANAGER,
        WINDOW_SCALE,
        RESHADE_MENU,
        EXIT_GAME
    }

    public XrDialog(XrActivity activity) {
        super(activity, R.layout.xr_dialog);
        setTitle(R.string.app_name);

        GridLayout grid = findViewById(R.id.GridXRMenu);
        grid.setColumnCount(5);
        addMenuItem(activity, grid, R.drawable.icon_keyboard, R.string.keyboard, MenuItem.SHOW_KEYBOARD, 1.0f);
        addMenuItem(activity, grid, R.drawable.icon_task_manager, R.string.task_manager, MenuItem.TASK_MANAGER, 1.0f);
        addMenuItem(activity, grid, R.drawable.icon_magnifier, R.string.magnifier, MenuItem.WINDOW_SCALE, 1.0f);
        if (XrActivity.getInstance().container.isXrUseReshade()) {
            addMenuItem(activity, grid, R.drawable.icon_popup_menu_bring_to_front, R.string.reshade, MenuItem.RESHADE_MENU, 1.0f);
        }
        addMenuItem(activity, grid, R.drawable.icon_exit, R.string.exit_game, MenuItem.EXIT_GAME, 1.0f);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean isImmersive = XrActivity.isImmersive;
        boolean isSBS = XrActivity.isSBS;

        CheckBox cbSBS = findViewById(R.id.CBEnableSBS);
        cbSBS.setEnabled(XrActivity.getInstance().lastMode3D < 0);
        cbSBS.setChecked(isSBS);
        CheckBox cbImmersiveMode = findViewById(R.id.CBEnableImmersiveMode);
        cbImmersiveMode.setEnabled(!XrActivity.isUDP);
        cbImmersiveMode.setChecked(isImmersive);
        CheckBox cbCurvedScreen = findViewById(R.id.CBEnableCurvedScreen);
        cbCurvedScreen.setChecked(preferences.getBoolean("use_cs", false));
        CheckBox cbPassthrough = findViewById(R.id.CBEnablePassthrough);
        cbPassthrough.setChecked(preferences.getBoolean("use_pt", true));
        TextView tvToApplyClose = findViewById(R.id.TVToApplyClose);

        CheckBox cbPlayerXRMouse = findViewById(R.id.CBPlayerXRMouse);
        cbPlayerXRMouse.setChecked(preferences.getBoolean("use_xr_mouse", true));
        CheckBox cbPlayerXRMouseLeftHanded = findViewById(R.id.CBPlayerXRMouseLeftHanded);
        cbPlayerXRMouseLeftHanded.setEnabled(cbPlayerXRMouse.isChecked());
        cbPlayerXRMouseLeftHanded.setChecked(preferences.getBoolean("use_xr_leftHanded", false));
        CheckBox cbPlayerXRMouseLightgun = findViewById(R.id.CBPlayerXRMouseLightgun);
        cbPlayerXRMouseLightgun.setEnabled(cbPlayerXRMouse.isChecked());
        cbPlayerXRMouseLightgun.setChecked(preferences.getBoolean("use_xr_lightgun", false));

        Runnable applyAll = () -> {
            SharedPreferences.Editor e = preferences.edit();
            e.putBoolean("use_cs", cbCurvedScreen.isChecked());
            e.putBoolean("use_pt", cbPassthrough.isChecked());
            e.putBoolean("use_xr_mouse", cbPlayerXRMouse.isChecked());
            e.putBoolean("use_xr_leftHanded", cbPlayerXRMouseLeftHanded.isChecked());
            e.putBoolean("use_xr_lightgun", cbPlayerXRMouseLightgun.isChecked());
            e.commit();

            XrActivity.mouseEmulation = cbPlayerXRMouse.isChecked();
            XrActivity.mouseLeftHanded = cbPlayerXRMouseLeftHanded.isChecked();
            XrActivity.mouseLightgun = cbPlayerXRMouseLightgun.isChecked();
            cbPlayerXRMouseLeftHanded.setEnabled(cbPlayerXRMouse.isChecked());
            cbPlayerXRMouseLightgun.setEnabled(cbPlayerXRMouse.isChecked());

            XrActivity.isSBS = cbSBS.isChecked();
            XrActivity.isImmersive = cbImmersiveMode.isChecked();
            XrActivity instance = XrActivity.getInstance();
            instance.nativeSetCurvedScreen(cbCurvedScreen.isChecked());
            instance.nativeSetUsePT(cbPassthrough.isChecked());

            boolean warn = (XrActivity.isImmersive != isImmersive);
            tvToApplyClose.setVisibility(warn ? View.VISIBLE : View.GONE);
        };

        // Apply changes immediatelly
        cbSBS.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbImmersiveMode.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbCurvedScreen.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPassthrough.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPlayerXRMouse.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPlayerXRMouseLeftHanded.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPlayerXRMouseLightgun.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.VISIBLE);
        findViewById(R.id.BTConfirm).setOnClickListener(v -> dismiss());
        setOnConfirmCallback(this::dismiss);
    }

    private void addMenuItem(Context context, GridLayout grid, int iconRes, int titleRes, MenuItem itemId, float alpha) {
        int padding = dpToPx(5, context);
        LinearLayout layout = new LinearLayout(context);
        layout.setPadding(padding, padding, padding, padding);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setOnClickListener(view -> {
            if (XrActivity.getInstance().onMenuItemClicked(itemId)) {
                dismiss();
            }
        });

        int size = dpToPx(40, context);
        View icon = new View(context);
        icon.setBackground(AppCompatResources.getDrawable(context, iconRes));
        if (icon.getBackground() != null) {
            icon.getBackground().setTint(context.getColor(R.color.colorPrimary));
        }
        icon.setAlpha(alpha); // Apply alpha for greying out
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(lp);
        layout.addView(icon);

        int width = dpToPx(80, context);
        TextView text = new TextView(context);
        text.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        text.setText(context.getString(titleRes));
        text.setGravity(Gravity.CENTER);
        text.setLines(2);
        text.setTextColor(Color.BLACK);
        text.setAlpha(alpha); // Apply alpha for greying out
        Typeface tf = ResourcesCompat.getFont(context, R.font.bricolage_grotesque_regular);
        if (tf != null) {
            text.setTypeface(tf);
        }
        layout.addView(text);

        grid.addView(layout);
    }

    private int dpToPx(float dp, Context context){
        return (int) (dp * context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }
}
