package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal X Generic Event Extension (XGE) 1.0 implementation.
 *
 * XInput2 transports its events through XGE. libXi will not initialize XI2 unless
 * this extension is advertised and its single QueryVersion request succeeds.
 */
public class GenericEventExtension implements Extension {
    public static final byte MAJOR_OPCODE = -106;
    private static final int QUERY_VERSION = 0;
    private static final int SERVER_MAJOR = 1;
    private static final int SERVER_MINOR = 0;

    @Override
    public String getName() {
        return "Generic Event Extension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        if (client.getRequestData() != QUERY_VERSION) {
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadImplementation();
        }

        int clientMajor = inputStream.readShort() & 0xffff;
        int clientMinor = inputStream.readShort() & 0xffff;
        inputStream.skip(client.getRemainingRequestLength());

        int negotiatedMajor = Math.min(clientMajor, SERVER_MAJOR);
        int negotiatedMinor = negotiatedMajor < SERVER_MAJOR
                ? clientMinor
                : Math.min(clientMinor, SERVER_MINOR);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) negotiatedMajor);
            outputStream.writeShort((short) negotiatedMinor);
            outputStream.writePad(20);
        }
    }
}
