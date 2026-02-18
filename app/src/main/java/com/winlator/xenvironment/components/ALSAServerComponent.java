package com.winlator.xenvironment.components;

import android.util.Log;

import com.winlator.alsaserver.ALSAClientConnectionHandler;
import com.winlator.alsaserver.ALSARequestHandler;
import com.winlator.core.KeyValueSet;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.xenvironment.ImageFs;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final ALSAClient.Options options;
    private final UnixSocketConfig socketConfig;
    private volatile boolean isPaused = false;

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this.socketConfig = socketConfig;
        this.options = options;
    }

    @Override // com.winlator.xenvironment.EnvironmentComponent
    public void start() {
        if (this.connector != null) {
            return;
        }
        ALSAClient.assignFramesPerBuffer(this.environment.getContext());
        ImageFs imagefs = ImageFs.find(this.environment.getContext());

        XConnectorEpoll xConnectorEpoll = new XConnectorEpoll(this.socketConfig, new ALSAClientConnectionHandler(this.options, imagefs.getVariant()), new ALSARequestHandler());
        this.connector = xConnectorEpoll;
        xConnectorEpoll.setMultithreadedClients(true);
        this.connector.start();
        isPaused = false;
    }

    @Override // com.winlator.xenvironment.EnvironmentComponent
    public void stop() {
        XConnectorEpoll xConnectorEpoll = this.connector;
        if (xConnectorEpoll != null) {
            xConnectorEpoll.stop();
            this.connector = null;
        }
        isPaused = false;
    }

    public void pause() {
        if (isPaused) return;
        XConnectorEpoll xConnectorEpoll = this.connector;
        if (xConnectorEpoll != null) {
            // Pause all connected ALSA clients
            for (int i = 0; i < xConnectorEpoll.getConnectedClientsCount(); i++) {
                com.winlator.xconnector.Client client = xConnectorEpoll.getConnectedClientAt(i);
                if (client != null && client.getTag() instanceof ALSAClient) {
                    ((ALSAClient) client.getTag()).pause();
                }
            }
        }
        isPaused = true;
    }

    public void resume() {
        if (!isPaused) return;
        XConnectorEpoll xConnectorEpoll = this.connector;
        if (xConnectorEpoll != null) {
            // Resume all connected ALSA clients
            for (int i = 0; i < xConnectorEpoll.getConnectedClientsCount(); i++) {
                com.winlator.xconnector.Client client = xConnectorEpoll.getConnectedClientAt(i);
                if (client != null && client.getTag() instanceof ALSAClient) {
                    ((ALSAClient) client.getTag()).start();
                }
            }
        }
        isPaused = false;
    }
}
