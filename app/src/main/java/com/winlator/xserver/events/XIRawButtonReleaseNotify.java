package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

public class XIRawButtonReleaseNotify extends Event {
    public static final int GENERIC_EVENT_CODE = 35;
    private static final short XI_RAWBUTTONRELEASE_EVTYPE = 16;

    private final byte extensionOpcode;
    private final int deviceId;
    private final int buttonNumber;

    public XIRawButtonReleaseNotify(int deviceId, byte extensionOpcode, int buttonNumber) {
        super(GENERIC_EVENT_CODE);
        this.deviceId = deviceId;
        this.extensionOpcode = extensionOpcode;
        this.buttonNumber = buttonNumber;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // Extra payload length = 0.
            outputStream.writeByte(this.code);                      // [0] GenericEvent = 35
            outputStream.writeByte(extensionOpcode);                // [1] extension opcode
            outputStream.writeShort(sequenceNumber);                // [2-3] sequence number
            outputStream.writeInt(0);                               // [4-7] extra payload length = 0

            outputStream.writeShort(XI_RAWBUTTONRELEASE_EVTYPE);    // [8-9] evtype = 16
            outputStream.writeShort((short) deviceId);              // [10-11] deviceid
            outputStream.writeInt((int) System.currentTimeMillis());// [12-15] time
            outputStream.writeInt(buttonNumber);                    // [16-19] detail = button number
            outputStream.writeShort((short) deviceId);              // [20-21] sourceid
            outputStream.writeShort((short) 0);                     // [22-23] valuators_len = 0
            outputStream.writeInt(0);                               // [24-27] flags
            outputStream.writePad(4);                               // [28-31] padding
        }
    }
}
