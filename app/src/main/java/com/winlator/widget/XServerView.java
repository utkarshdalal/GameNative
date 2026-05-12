package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.collection.MutableObjectList;

import com.winlator.core.Callback;
import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    // private final ArrayList<Callback<MotionEvent>> mouseEventCallbacks = new ArrayList<>();
    private final XServer xServer;
    private int frameRateLimit = 0;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        this.xServer = xServer;
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        // setOnFocusChangeListener((view, gainFocus) -> {
        //     Log.d("XServerView", "onFocusChange: " + gainFocus + ", isMe: " + (view == this));
        // });
        //
        // requestFocus();
    }
    public XServer getxServer() {
        return xServer;
    }
    // public void onRelease() {
    //     releasePointerCapture();
    //     clearPointerEventListeners();
    // }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        requestRender();
    }

    // public void addPointerEventListener(Callback<MotionEvent> listener) {
    //     mouseEventCallbacks.add(listener);
    // }
    // public void removePointerEventListener(Callback<MotionEvent> listener) {
    //     mouseEventCallbacks.remove(listener);
    // }
    // public void clearPointerEventListeners() {
    //     mouseEventCallbacks.clear();
    // }
    // private void emitPointerEvent(MotionEvent event) {
    //     for (Callback<MotionEvent> listener : mouseEventCallbacks) {
    //         listener.call(event);
    //     }
    // }

    // @Override
    // public boolean onCapturedPointerEvent(MotionEvent event) {
    //     Log.d("XServerView", "onCapturedPointerEvent:\n\t" + event);
    //     emitPointerEvent(event);
    //     return true;
    // }


    // @Override
    // public boolean dispatchGenericMotionEvent(MotionEvent event) {
    //     Log.d("XServerView", "dispatchGenericMotionEvent:\n\t" + event);
    //     return super.dispatchGenericMotionEvent(event);
    // }
    //
    // @Override
    // protected boolean dispatchGenericPointerEvent(MotionEvent event) {
    //     Log.d("XServerView", "dispatchGenericPointerEvent:\n\t" + event);
    //     return super.dispatchGenericPointerEvent(event);
    // }
    //
    // @Override
    // protected void onFocusChanged(boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect) {
    //     super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    //     Log.d("XServerView", "Focus changed: " + gainFocus);
    // }
    //
    // @Override
    // public boolean dispatchCapturedPointerEvent(MotionEvent event) {
    //     Log.d("XServerView", "dispatchCapturedPointerEvent:\n\t" + event);
    //     emitPointerEvent(event);
    //     return super.dispatchCapturedPointerEvent(event);
    // }
    //
    // @Override
    // public void onPointerCaptureChange(boolean hasCapture) {
    //     super.onPointerCaptureChange(hasCapture);
    //     Log.d("XServerView", "onPointerCaptureChange: " + hasCapture);
    // }
    //
    // @Override
    // public void onWindowFocusChanged(boolean hasWindowFocus) {
    //     super.onWindowFocusChanged(hasWindowFocus);
    //     if (hasWindowFocus) {
    //         requestPointerCapture();
    //     } else {
    //         releasePointerCapture();
    //     }
    // }
}
