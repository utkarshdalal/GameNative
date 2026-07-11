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

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

import androidx.annotation.NonNull;

import com.winlator.xr.XrActivity;
import com.winlator.xserver.Drawable;

import java.util.ArrayList;

public class XrContentDialog extends Dialog {
    protected View contentView;

    private static int counter;
    private static int[] pixels;
    private static Bitmap bitmap;
    private static Canvas canvas;
    private static Drawable drawable;
    private static ArrayList<XrContentDialog> instances = new ArrayList<>();

    public XrContentDialog(@NonNull Context context, int layoutResId) {
        super(context, layoutResId);
    }

    public View getContentView() {
        return contentView;
    }

    @Override
    public void show() {
        super.show();
        instances.add(this);
    }

    @Override
    public void dismiss() {
        instances.remove(this);
        super.dismiss();
    }

    public Drawable getDrawable() {
        if (counter++ > 10) {
            XrActivity.getInstance().runOnUiThread(this::redraw);
            counter = 0;
        }
        return drawable;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        //workaround for buggy Meta Quest OS
        if (!hasFocus && XrActivity.isActive()) {
            dismiss();
        } else {
            super.onWindowFocusChanged(hasFocus);
        }
    }

    public void onKeyAction(int keyCode) {
        BaseInputConnection input = new BaseInputConnection(contentView, true);
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    public void redraw() {
        //Check if the view is ready
        View v = getContentView();
        if (v == null) {
            return;
        }
        int w = v.getMeasuredWidth();
        int h = v.getMeasuredHeight();
        if (w * h == 0) {
            return;
        }

        //Allocate render arrays
        if ((pixels == null) || (bitmap.getWidth() != w) || (bitmap.getHeight() != h)) {
            pixels = new int[w * h];
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            drawable = Drawable.fromBitmap(bitmap);
        }

        //Apply background
        android.graphics.drawable.Drawable background = v.getBackground();
        if (background != null) {
            background.draw(canvas);
        } else {
            canvas.drawColor(Color.WHITE);
        }

        //Render window
        v.draw(canvas);

        //Double buffering
        if (bitmap != null) {
            drawable.drawBitmap(bitmap);
        }
    }

    public static XrContentDialog getFrontInstance() {
        return instances.isEmpty() ? null : instances.get(instances.size() - 1);
    }
}
