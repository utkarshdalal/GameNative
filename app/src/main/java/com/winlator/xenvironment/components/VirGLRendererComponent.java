package com.winlator.xenvironment.components;

import android.util.Log;

import androidx.annotation.Keep;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.io.IOException;

public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private long sharedEGLContextPtr;

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        Log.d("VirGLRendererComponent", "Starting...");
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.start();
    }

    @Override
    public void stop() {
        Log.d("VirGLRendererComponent", "Stopping...");
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private void killConnection(int fd) {
        connector.killConnection(connector.getClient(fd));
    }

    @Keep
    private long getSharedEGLContext() {
        Log.d("VirGLRendererComponent", "Calling getSharedEGLContext");
        if (sharedEGLContextPtr != 0) return sharedEGLContextPtr;
        if (!(xServer.getRenderer() instanceof GLRenderer)) {
            Log.w("VirGLRendererComponent", "getSharedEGLContext: not on the GL renderer path; returning 0");
            return 0;
        }
        GLRenderer renderer = (GLRenderer) xServer.getRenderer();
        final Thread thread = Thread.currentThread();
        try {
            renderer.xServerView.queueEvent(() -> {
                sharedEGLContextPtr = getCurrentEGLContextPtr();
                synchronized (thread) {
                    thread.notify();
                }
            });
            synchronized (thread) {
                thread.wait();
            }
        } catch (Exception e) {
            return 0;
        }
        Log.d("VirGLRendererComponent", "Finished getSharedEGLContext");
        return sharedEGLContextPtr;
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        long clientPtr = (long)client.getTag();
        destroyClient(clientPtr);
    }

    @Override
    public void handleNewConnection(Client client) {
        Log.d("VirGLRendererComponent", "Calling handleNewConnection");
        getSharedEGLContext();
        long clientPtr = handleNewConnection(client.clientSocket.fd);
        client.setTag(clientPtr);
        Log.d("VirGLRendererComponent", "Finished handleNewConnection");
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        Log.d("VirGLRendererComponent", "Calling handleRequest");
        long clientPtr = (long)client.getTag();
        handleRequest(clientPtr);
        Log.d("VirGLRendererComponent", "Finished handleRequest");
        return true;
    }

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Log.d("VirGLRendererComponent", "Calling flushFrontbuffer");
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) return;

        synchronized (drawable.renderLock) {
            drawable.setData(null);
            Texture texture = drawable.getTexture();
            texture.copyFromFramebuffer(framebuffer, drawable.width, drawable.height);
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) onDrawListener.run();
        Log.d("VirGLRendererComponent", "Finished flushFrontbuffer");
    }

    private native long handleNewConnection(int fd);

    private native void handleRequest(long clientPtr);

    private native long getCurrentEGLContextPtr();

    private native void destroyClient(long clientPtr);

    private native void destroyRenderer(long clientPtr);
}
