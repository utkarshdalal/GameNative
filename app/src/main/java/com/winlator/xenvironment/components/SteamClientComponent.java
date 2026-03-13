package com.winlator.xenvironment.components;

import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.steampipeserver.SteamPipeServer;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class SteamClientComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    // public abstract static class RequestCodes {
    //     public static final byte INIT = 0;
    //     public static final byte GET_TICKET = 1;
    //     public static final byte AUTH_SESSION = 2;
    // }

    private SteamPipeServer connector;
    // private XConnectorEpoll connector;
    // private final UnixSocketConfig socketConfig;

    // public SteamClientComponent(UnixSocketConfig socketConfig) {
    //     this.socketConfig = socketConfig;
    // }

    @Override
    public void start() {
        Log.d("SteamClientComponent", "Starting...");
        stop();
        connector = new SteamPipeServer();
        connector.start();
    }

    @Override
    public void stop() {
        Log.d("SteamClientComponent", "Stopping...");
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Override
    public void handleNewConnection(Client client) {
        Log.d("SteamClientComponent", "New connection");
        client.createIOStreams();
        // client.setTag(new ALSAClient());
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        Log.d("SteamClientComponent", "Connection shutdown");
        // ((ALSAClient)client.getTag()).release();
    }

    @Override
    public boolean handleRequest(Client client) {
        // XInputStream input = client.getInputStream();
        // if (input == null) return false;
        //
        // int cmdType = input.readInt();
        // Log.d("SteamClientComponent", "Received " + cmdType);
        //
        // switch (cmdType) {
        //     case RequestCodes.INIT:
        //         Log.d("SteamClientComponent", "Received INIT");
        //         break;
        //     case RequestCodes.GET_TICKET:
        //         Log.d("SteamClientComponent", "Received GET_TICKET");
        //         break;
        //     case RequestCodes.AUTH_SESSION:
        //         Log.d("SteamClientComponent", "Received AUTH_SESSION");
        //         break;
        // }
        // return true;
        return true;
    }
}
