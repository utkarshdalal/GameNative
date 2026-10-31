package com.winlator.xserver;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.events.Event;

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
        XOutputStream outputStream = client.getOutputStream();
        try (XStreamLock ignored = outputStream.lockDroppable()) {
            event.send(client.getSequenceNumber(), outputStream);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
