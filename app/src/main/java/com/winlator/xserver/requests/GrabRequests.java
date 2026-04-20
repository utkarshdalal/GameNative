package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.graphics.Rect;
import android.util.Log;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class GrabRequests {
    private enum Status {SUCCESS, ALREADY_GRABBED, INVALID_TIME, NOT_VIEWABLE, FROZEN}

    public static void grabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        if (client.xServer.isRelativeMouseMovement()) {
            client.skipRequest();
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                outputStream.writeByte((byte)Status.ALREADY_GRABBED.ordinal());
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writePad(24);
            }
            return;
        }

        boolean ownerEvents = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Bitmask eventMask = new Bitmask(inputStream.readShort());
        byte pointerMode = inputStream.readByte();
        byte keyboardMode = inputStream.readByte();
        int confineToWindowId = inputStream.readInt();
        int cursorId = inputStream.readInt();
        int time = inputStream.readInt();

        Window confineToWindow = null;

        Status status;
        if (client.xServer.grabManager.getWindow() != null && client.xServer.grabManager.getClient() != client) {
            status = Status.ALREADY_GRABBED;
        }
        else if (window.getMapState() != Window.MapState.VIEWABLE) {
            status = Status.NOT_VIEWABLE;
        }
        else {
            status = Status.SUCCESS;
            if (confineToWindowId != 0) {
                confineToWindow = client.xServer.windowManager.getWindow(confineToWindowId);
                // Per X11 spec: silently ignore if not viewable, not a BadWindow error
                if (confineToWindow != null && confineToWindow.getMapState() != Window.MapState.VIEWABLE) {
                    confineToWindow = null;
                }
            }
            client.xServer.grabManager.activatePointerGrab(window, ownerEvents, eventMask, client, confineToWindow);
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)status.ordinal());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    public static void ungrabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        inputStream.skip(4);
        client.xServer.grabManager.deactivatePointerGrab();
    }
}
