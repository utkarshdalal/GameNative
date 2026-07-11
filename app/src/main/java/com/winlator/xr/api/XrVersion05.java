package com.winlator.xr.api;

import androidx.annotation.NonNull;

import com.winlator.xserver.Keyboard;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.ArrayList;

public class XrVersion05 extends XrVersion04 {

    private final ArrayList<String> pendingInputs = new ArrayList<>();

    @Override
    public void consumeInputs(XServer xServer) {
        Pointer mouse = xServer.pointer;
        Keyboard keyboard = xServer.keyboard;

        ArrayList<String> inputs;
        synchronized (pendingInputs) {
            inputs = new ArrayList<>(pendingInputs);
            pendingInputs.clear();
        }

        for (String message : inputs) {
            String[] parts = message.split(",");

            if (parts.length > 1) {
                if (parts[0].equalsIgnoreCase("M")) {
                    //Eg: M,1,0,0,0,0
                    boolean leftClick = Boolean.parseBoolean(parts[1]);
                    boolean rightClick = false;
                    boolean middleClick = false;
                    boolean scrollUp = false;
                    boolean scrollDown = false;

                    if (parts.length > 2) {
                        rightClick = Boolean.parseBoolean(parts[2]);
                    }

                    if (parts.length > 3) {
                        middleClick = Boolean.parseBoolean(parts[3]);
                    }

                    if (parts.length > 4) {
                        scrollUp = Boolean.parseBoolean(parts[4]);
                    }

                    if (parts.length > 5) {
                        scrollDown = Boolean.parseBoolean(parts[5]);
                    }

                    mouse.setButton(Pointer.Button.BUTTON_LEFT, leftClick);
                    mouse.setButton(Pointer.Button.BUTTON_RIGHT, rightClick);
                    mouse.setButton(Pointer.Button.BUTTON_MIDDLE, middleClick);
                    mouse.setButton(Pointer.Button.BUTTON_SCROLL_UP, scrollUp);
                    mouse.setButton(Pointer.Button.BUTTON_SCROLL_DOWN, scrollDown);
                }
                else if (parts[0].equalsIgnoreCase("K")) {
                    //Eg: K,50,38 to press SHIFT_L and A at the same time
                    ArrayList<XKeycode> sendKeys = new ArrayList<>();

                    for (int i = 1; i < parts.length; i++) {
                        sendKeys.add(keyFromString(parts[i]));
                    }

                    for (XKeycode key : sendKeys) {
                        keyboard.setKeyPress(key.getId(), 0);
                    }

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    for (XKeycode key : sendKeys) {
                        keyboard.setKeyRelease(key.getId());
                    }
                }
            }
        }
    }

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (intent == PortIntent.HMD_STATE) {
            super.dataReceived(intent, message);
        } else if (intent == PortIntent.XSERVER_INPUT) {
            synchronized (pendingInputs) {
                pendingInputs.add(message);
            }
        }
    }

    @Override
    public int getPortIn(PortIntent intent) {
        return switch (intent) {
            case HMD_STATE -> super.getPortIn(intent);
            case XSERVER_INPUT -> 7728;
        };
    }

    private XKeycode keyFromString(@NonNull String idString) {
        byte id = Byte.parseByte(idString);
        for (XKeycode key : XKeycode.values()) {
            if (key.getId() == id) {
                return key;
            }
        }
        return XKeycode.KEY_NONE;
    }
}
