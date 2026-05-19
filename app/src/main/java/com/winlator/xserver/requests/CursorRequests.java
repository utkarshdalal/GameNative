package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class CursorRequests {
    public static void createCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        int sourcePixmapId = inputStream.readInt();
        int maskPixmapId = inputStream.readInt();

        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);

        Pixmap sourcePixmap = client.xServer.pixmapManager.getPixmap(sourcePixmapId);
        if (sourcePixmap == null) throw new BadPixmap(sourcePixmapId);

        Pixmap maskPixmap = client.xServer.pixmapManager.getPixmap(maskPixmapId);
        if (maskPixmap != null && (
                maskPixmap.drawable.visual.depth != 1 ||
                        maskPixmap.drawable.width != sourcePixmap.drawable.width ||
                        maskPixmap.drawable.height != sourcePixmap.drawable.height)) {
            throw new BadMatch();
        }

        byte foreRed = (byte)inputStream.readShort();
        byte foreGreen = (byte)inputStream.readShort();
        byte foreBlue = (byte)inputStream.readShort();
        byte backRed = (byte)inputStream.readShort();
        byte backGreen = (byte)inputStream.readShort();
        byte backBlue = (byte)inputStream.readShort();
        short x = inputStream.readShort();
        short y = inputStream.readShort();

        Cursor cursor = client.xServer.cursorManager.createCursor(cursorId, x, y, sourcePixmap, maskPixmap);
        if (cursor == null) throw new BadIdChoice(cursorId);
        client.xServer.cursorManager.recolorCursor(cursor, foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue);
        client.registerAsOwnerOfResource(cursor);
    }

    public static void freeCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        client.xServer.cursorManager.freeCursor(inputStream.readInt());
    }

    public static void getPointerMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError, IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // Fix scroll when using XInput2 extension
            byte[] buttonsMap = { 1, 2, 3, 4, 5, 6, 7 };
            int nElts = buttonsMap.length;

            int lengthUnits = (nElts + 3) / 4;

            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) nElts);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(lengthUnits);
            outputStream.writePad(24);
            outputStream.write(buttonsMap);

            int pad = (-nElts) & 3;
            if (pad > 0) {
                outputStream.writePad(pad);
            }
        }
    }
}
