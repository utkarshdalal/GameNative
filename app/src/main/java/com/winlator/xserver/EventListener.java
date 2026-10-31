package com.winlator.xserver;

import com.winlator.xserver.events.Event;
import com.winlator.xserver.events.InputDeviceEvent;
import com.winlator.xconnector.ClientSocket;

import java.io.IOException;

public class EventListener {
    public final XClient client;
    public final Bitmask eventMask;

    public EventListener(XClient client, Bitmask eventMask) {
        this.client = client;
        this.eventMask = eventMask;
    }

    public boolean isInterestedIn(int eventId) {
        return eventMask.isSet(eventId);
    }

    public boolean isInterestedIn(Bitmask mask) {
        return this.eventMask.intersects(mask);
    }

    public void sendEvent(Event event) {
        try {
            event.send(client.getSequenceNumber(), client.getOutputStream());
        }
        catch (ClientSocket.WouldBlockException e) {
            if (!(event instanceof InputDeviceEvent)) e.printStackTrace();
            // Input events are transient. Drop one rather than blocking Android's
            // main thread when an X11 client stops draining its socket.
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
