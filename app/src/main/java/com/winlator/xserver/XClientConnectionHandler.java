package com.winlator.xserver;

import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;

public class XClientConnectionHandler implements ConnectionHandler {
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        XClient xClient = new XClient(xServer, client.getInputStream(), client.getOutputStream());
        xClient.connectorClient = client;
        client.setTag(xClient);
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        ((XClient)client.getTag()).freeResources();
    }
}
