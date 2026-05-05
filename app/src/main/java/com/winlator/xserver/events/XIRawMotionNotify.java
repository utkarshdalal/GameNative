package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

public class XIRawMotionNotify extends Event {
    public static final int GENERIC_EVENT_CODE = 35;
    private static final short XI_RAWMOTION_EVTYPE = 17;

    private final byte extensionOpcode;
    private final int deviceId;
    private final double[] valuators;
    private final int valuatorMask;

    public XIRawMotionNotify(int deviceId,
                             byte extensionOpcode,
                             double[] valuators,
                             int valuatorMask) {
        super(GENERIC_EVENT_CODE);
        this.deviceId = deviceId;
        this.extensionOpcode = extensionOpcode;
        this.valuators = valuators;
        this.valuatorMask = valuatorMask;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {

            // Mask length in 4-byte units. We need 1 unit to hold our X/Y bits (0x03).
            short maskLenUnits = 1;

            int numAxes = valuators.length;
            int payloadBytes =
                4 +                    // mask
                (numAxes * 8) +        // axisvalues
                (numAxes * 8);         // axisvalues_raw

            int payloadLengthUnits = payloadBytes / 4;

            // --------------------------------------------------------
            // 1. STANDARD GENERIC EVENT HEADER (32 bytes)
            // --------------------------------------------------------
            outputStream.writeByte(this.code);               // [0] type = 35 (GenericEvent)
            outputStream.writeByte(extensionOpcode);         // [1] extension opcode
            outputStream.writeShort(sequenceNumber);         // [2-3] sequence number (passed from XClient)
            outputStream.writeInt(payloadLengthUnits);       // [4-7] length of extra payload
            outputStream.writeShort(XI_RAWMOTION_EVTYPE);    // [8-9] evtype = 17
            outputStream.writeShort((short) deviceId);       // [10-11] deviceid
            outputStream.writeInt((int) System.currentTimeMillis()); // [12-15] time
            outputStream.writeInt(0);                        // [16-19] detail (0 for motion)
            outputStream.writeShort((short) deviceId);       // [20-21] sourceid
            outputStream.writeShort(maskLenUnits);           // [22-23] valuators_len
            outputStream.writeInt(0);                        // [24-27] flags
            outputStream.writePad(4);                        // [28-31] padding to complete 32 bytes

            // --------------------------------------------------------
            // 2. PAYLOAD: MASK (4 bytes)
            // --------------------------------------------------------
            outputStream.writeInt(valuatorMask);

            // --------------------------------------------------------
            // 3. PAYLOAD: AXISVALUES (16 bytes)
            // --------------------------------------------------------
            for (double v : valuators) {
                outputStream.writeFP3232(v);
            }

            // --------------------------------------------------------
            // 4. PAYLOAD: AXISVALUES_RAW (16 bytes)
            // --------------------------------------------------------
            for (double v : valuators) {
                outputStream.writeFP3232(v);
            }
        }
    }
}
