package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.XIRawButtonPressNotify;
import com.winlator.xserver.events.XIRawButtonReleaseNotify;
import com.winlator.xserver.events.XIRawMotionNotify;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import timber.log.Timber;

public class XInput2Extension implements Extension {
    public static final byte MAJOR_OPCODE = -105;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static final int XI_MAJOR = 2;
    private static final int XI_MINOR = 2;
    private static final int XI_ALL_DEVICES = 0;
    private static final int XI_ALL_MASTER_DEVICES = 1;
    private static final int MASTER_POINTER_ID = 2;
    private static final int MASTER_KEYBOARD_ID = 3;
    private static final int XI_BUTTON_CLASS = 1;
    private static final int XI_VALUATOR_CLASS = 2;
    private static final int XI_RawButtonPress_MASK = 1 << 15;
    private static final int XI_RawButtonRelease_MASK = 1 << 16;
    private static final int XI_RawMotion_MASK  = 1 << 17;
    // advertise enough buttons to cover a normal mouse + wheel buttons
    private static final int RawMotion_XY_MASK = (1 << 0) | (1 << 1);
    private static final int POINTER_BUTTON_COUNT = 7;

    private final List<Selection> selections = new CopyOnWriteArrayList<>();

    private static abstract class ClientOpcodes {
        private static final byte GET_EXTENSION_VERSION = 1;  // X_GetExtensionVersion (XI 1.x)
        private static final byte GET_CLIENT_POINTER    = 45; // X_XIGetClientPointer (XI 2.x)
        private static final byte SELECT_EVENTS         = 46; // X_XISelectEvents (XI 2.x)
        private static final byte QUERY_VERSION         = 47; // X_XIQueryVersion (XI 2.x)
        private static final byte QUERY_DEVICE          = 48; // X_XIQueryDevice (XI 2.x)
    }

    private static class Selection {
        Window window;
        XClient client;
        int id;
        Bitmask mask;
        int deviceId;
    }

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 24; }

    @Override
    public int getNumErrors() { return 5; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private boolean isMasterDevice(int deviceId) {
        return deviceId == MASTER_POINTER_ID || deviceId == MASTER_KEYBOARD_ID;
    }

    private boolean matchesSelection(Selection sel, int deviceId) {
        return sel.deviceId == XI_ALL_DEVICES
                || (sel.deviceId == XI_ALL_MASTER_DEVICES && isMasterDevice(deviceId))
                || sel.deviceId == deviceId;
    }

    /*
     XInput2 wire format located on termuxfs /usr/include/X11/extensions
     XIproto.h and XI2proto.h for the struct definitions
     */

    private static void getExtensionVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            // typedef struct
            //    CARD8	repType;	/* X_Reply */
            //    CARD8	RepType;	/* always X_GetExtensionVersion */
            //    CARD16	sequenceNumber;
            //    CARD32	length;
            //    CARD16	major_version;
            //    CARD16	minor_version;
            //    BOOL	present;
            //    CARD8	pad1, pad2, pad3;
            //    CARD32	pad01;
            //    CARD32	pad02;
            //    CARD32	pad03;
            //    CARD32	pad04;
            // xGetExtensionVersionReply;
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            outputStream.writeShort((short) 2);
            outputStream.writeShort((short) 0);
            outputStream.writeByte((byte) 1);
            outputStream.writePad(19);
        }
    }

    private static void getClientPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            // typedef struct
            //    uint8_t     repType;                /**< Input extension major opcode */
            //    uint8_t     RepType;                /**< Always ::X_GetClientPointer */
            //    uint16_t    sequenceNumber;
            //    uint32_t    length;
            //    BOOL        set;                    /**< client pointer is set? */
            //    uint8_t     pad0;
            //    uint16_t    deviceid;
            //    uint32_t    pad1;
            //    uint32_t    pad2;
            //    uint32_t    pad3;
            //    uint32_t    pad4;
            //    uint32_t    pad5;
            // xXIGetClientPointerReply;
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort((short) 2);

            outputStream.writePad(20);
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        short clientMajor = (short)(inputStream.readShort() & 0xFFFF);
        short clientMinor = (short)(inputStream.readShort() & 0xFFFF);

        inputStream.skip(client.getRemainingRequestLength());

        short negotiatedMajor;
        short negotiatedMinor;

        if (clientMajor < XI_MAJOR || (clientMajor == XI_MAJOR && clientMinor < XI_MINOR)) {
            negotiatedMajor = clientMajor;
            negotiatedMinor = clientMinor;
        } else {
            negotiatedMajor = XI_MAJOR;
            negotiatedMinor = XI_MINOR;
        }

        try (XStreamLock lock = outputStream.lock()) {
            // typedef struct
            //    uint8_t     repType;                /**< ::X_Reply */
            //    uint8_t     RepType;                /**< Always ::X_XIQueryVersion */
            //    uint16_t    sequenceNumber;
            //    uint32_t    length;
            //    uint16_t    major_version;
            //    uint16_t    minor_version;
            //    uint32_t    pad1;
            //    uint32_t    pad2;
            //    uint32_t    pad3;
            //    uint32_t    pad4;
            //    uint32_t    pad5;
            // xXIQueryVersionReply;
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            outputStream.writeShort(negotiatedMajor);
            outputStream.writeShort(negotiatedMinor);

            outputStream.writePad(20);
        }
    }

    private void writeButtonClass(XOutputStream outputStream, int sourceId, int numButtons) throws IOException {
        int stateBytes = Math.max(4, ((numButtons + 31) / 32) * 4);
        int labelsBytes = numButtons * 4;
        int totalBytes = 8 + stateBytes + labelsBytes;
        int length = totalBytes / 4;

        // typedef struct
        //    uint16_t    type;           /**< Always ButtonClass */
        //    uint16_t    length;         /**< Length in 4 byte units */
        //    uint16_t    sourceid;       /**< source device for this class */
        //    uint16_t    num_buttons;    /**< Number of buttons provided */
        // xXIButtonInfo;
        outputStream.writeShort((short) XI_BUTTON_CLASS);   // type
        outputStream.writeShort((short) length);            // length
        outputStream.writeShort((short) sourceId);          // sourceid
        outputStream.writeShort((short) numButtons);        // num_buttons

        // Struct is followed by a button bit-mask (padded to four byte chunks) and
        // then num_buttons * Atom that names the buttons in the device-native setup

        // button state mask, all released by default
        outputStream.writeInt(0);
        if (stateBytes > 4) {
            outputStream.writePad(stateBytes - 4);
        }

        // labels: one Atom per button; 0 == None/unlabeled
        for (int i = 0; i < numButtons; i++) {
            outputStream.writeInt(0);
        }
    }

    private void writeValuatorClass(XOutputStream outputStream, int axisNumber) throws IOException {
        // typedef struct
        //    uint16_t    type;           /**< Always ValuatorClass       */
        //    uint16_t    length;         /**< Length in 4 byte units */
        //    uint16_t    sourceid;       /**< source device for this class */
        //    uint16_t    number;         /**< Valuator number            */
        //    Atom        label;          /**< Axis label                 */
        //    FP3232      min;            /**< Min value                  */
        //    FP3232      max;            /**< Max value                  */
        //    FP3232      value;          /**< Last published value       */
        //    uint32_t    resolution;     /**< Resolutions in units/m     */
        //    uint8_t     mode;           /**< ModeRelative or ModeAbsolute */
        //    uint8_t     pad1;
        //    uint16_t    pad2;
        // xXIValuatorInfo;
        // length = 44 bytes / 4 = 11 units
        outputStream.writeShort((short) XI_VALUATOR_CLASS); // type: 2 = XIValuatorClass
        outputStream.writeShort((short) 11);                // length
        outputStream.writeShort((short) MASTER_POINTER_ID); // sourceid
        outputStream.writeShort((short) axisNumber);        // number (Axis 0 for X, 1 for Y)

        outputStream.writeInt(0); // label

        // min/max: valid range for Absolute axes; conventionally 0 for Relative axes
        outputStream.writeFP3232(0);  // min
        outputStream.writeFP3232(0);  // max

        // value: current axis position; 0 at initialisation
        outputStream.writeFP3232(0);

        // resolution: units per meter, used by absolute axes; 0 for relative
        outputStream.writeInt(0);

        // mode: 0 = Relative (delta per event), 1 = Absolute (position in min/max range)
        outputStream.writeByte((byte) 0);
        outputStream.writePad(3);  // pad1 (1 byte) + pad2 (2 bytes)
    }

    private void queryDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        final int masterPointerId = 2; // Standard ID for the main mouse
        final String name = "Virtual Core Pointer";
        final byte[] nameBytes = name.getBytes();
        final int nameLen = nameBytes.length; // 20
        final int namePad = (nameLen + 3) & ~3; // Padded to 4-byte boundary (20)

        final int numButtons = POINTER_BUTTON_COUNT;
        final int buttonStateBytes = Math.max(4, ((numButtons + 31) / 32) * 4);
        final int buttonClassBytes = 8 + buttonStateBytes + (numButtons * 4);

        final int numValuators = 2;
        final int numClasses = 1 + numValuators; // Button + 2 valuators

        // Payload Size
        int deviceInfoSize =
                12 + // xXIDeviceInfo size
                namePad +
                buttonClassBytes +
                (44 * numValuators);

        int length = deviceInfoSize / 4; // payload length in 4-byte units (30)

        try (XStreamLock lock = outputStream.lock()) {
            // typedef struct
            //    uint8_t     repType;                /**< ::X_Reply */
            //    uint8_t     RepType;                /**< Always ::X_XIQueryDevice */
            //    uint16_t    sequenceNumber;
            //    uint32_t    length;
            //    uint16_t    num_devices;
            //    uint16_t    pad0;
            //    uint32_t    pad1;
            //    uint32_t    pad2;
            //    uint32_t    pad3;
            //    uint32_t    pad4;
            //    uint32_t    pad5;
            // xXIQueryDeviceReply;
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);      // repType
            outputStream.writeByte((byte)0);                    // RepType
            outputStream.writeShort(client.getSequenceNumber());// sequenceNumber
            outputStream.writeInt(length);                      // length
            outputStream.writeShort((short)1);                  // num_devices
            outputStream.writePad(22);                    // padding

            // typedef struct
            //    uint16_t    deviceid;
            //    uint16_t    use;            /**< ::XIMasterPointer, ::XIMasterKeyboard,
            //                                     ::XISlavePointer, ::XISlaveKeyboard,
            //                                     ::XIFloatingSlave */
            //    uint16_t    attachment;     /**< Current attachment or pairing.*/
            //    uint16_t    num_classes;    /**< Number of classes following this struct. */
            //    uint16_t    name_len;       /**< Length of name in bytes. */
            //    uint8_t     enabled;        /**< TRUE if device is enabled. */
            //    uint8_t     pad;
            // xXIDeviceInfo;
            outputStream.writeShort((short)masterPointerId); // deviceid
            outputStream.writeShort((short)1);               // use (1 = MasterPointer)
            outputStream.writeShort((short)0);               // attachment
            outputStream.writeShort((short)numClasses);      // num_classes
            outputStream.writeShort((short)nameLen);         // name_len
            outputStream.writeByte((byte)1);                 // enabled
            outputStream.writeByte((byte)0);                 // pad1

            // Device Name
            outputStream.write(nameBytes);
            outputStream.writePad(namePad - nameLen);

            // Classes
            writeButtonClass(outputStream, masterPointerId, numButtons);
            writeValuatorClass(outputStream, 0);
            writeValuatorClass(outputStream, 1);
        }
    }

    private void selectEvents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int numMasks = inputStream.readShort() & 0xFFFF;

        if (numMasks == 0) {
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadValue(numMasks);
        }

        inputStream.readShort();

        Window window = client.xServer.windowManager.getWindow(windowId);

        if (window == null) {
            inputStream.skip(client.getRemainingRequestLength());
            throw new BadWindow(windowId);
        }

        for (int i = 0; i < numMasks; i++) {
            int deviceId = inputStream.readShort() & 0xFFFF;
            int maskLen = inputStream.readShort() & 0xFFFF;

            Bitmask mask = new Bitmask(0);

            for (int word = 0; word < maskLen; word++) {
                long value = inputStream.readUnsignedInt();
                mask.set(value << (word * 32));
            }

            Selection sel = new Selection();
            sel.client = client;
            sel.window = window;
            sel.deviceId = deviceId;
            sel.mask = mask;
            sel.id = windowId;

            // XISelectEvents overwrites the previous mask for the same client/window/device
            selections.removeIf(old ->
                    old.client == client &&
                            old.id == windowId &&
                            old.deviceId == deviceId);

            selections.add(sel);
        }

        inputStream.skip(client.getRemainingRequestLength());
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        int opcode = client.getRequestData();

        switch (opcode) {
            case ClientOpcodes.GET_EXTENSION_VERSION:
                getExtensionVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CLIENT_POINTER:
                getClientPointer(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_EVENTS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectEvents(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_DEVICE:
                queryDevice(client, inputStream, outputStream);
                break;
            default:
                Timber.w("XInput2Extension: unhandled minor opcode=%d, requestData=%d, requestLength=%d",
                        opcode, client.getRequestData(), client.getRemainingRequestLength());
                inputStream.skip(client.getRemainingRequestLength());
                // throw new BadImplementation();
                break;
        }
    }

    public void onClientDisconnected(XClient client) {
        selections.removeIf(sel -> sel.client == client);
    }

    public void emitRawMotion(int deviceId, double deltaX, double deltaY) {
        for (Selection sel : selections) {
            if (!matchesSelection(sel, deviceId)) continue;
            if (!sel.mask.isSet(XI_RawMotion_MASK)) continue;

            try {
                sendXIRawMotionToClient(sel.client, deviceId, deltaX, deltaY);
            } catch (IOException ignored) {
            }
        }
    }

    public void emitRawButton(int deviceId, int buttonNumber, boolean pressed) {
        int maskBit = pressed ? XI_RawButtonPress_MASK : XI_RawButtonRelease_MASK;

        for (Selection sel : selections) {
            if (!matchesSelection(sel, deviceId)) continue;
            if (!sel.mask.isSet(maskBit)) continue;

            try {
                sendXIRawButtonToClient(sel.client, deviceId, buttonNumber, pressed);
            } catch (IOException ignored) {
            }
        }
    }

    private void sendXIRawMotionToClient(XClient client, int deviceId, double deltaX, double deltaY) throws IOException {
        client.sendEvent(new XIRawMotionNotify(deviceId, MAJOR_OPCODE,
                new double[] {deltaX, deltaY},
                RawMotion_XY_MASK
        ));
    }

    private void sendXIRawButtonToClient(XClient client, int deviceId, int buttonNumber, boolean pressed) throws IOException {
        if (pressed) {
            client.sendEvent(new XIRawButtonPressNotify(deviceId, MAJOR_OPCODE, buttonNumber));
        } else {
            client.sendEvent(new XIRawButtonReleaseNotify(deviceId, MAJOR_OPCODE, buttonNumber));
        }
    }
}
