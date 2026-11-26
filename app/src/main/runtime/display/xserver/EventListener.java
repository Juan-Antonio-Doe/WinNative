package com.winlator.cmod.runtime.display.xserver;

import com.winlator.cmod.runtime.display.xserver.events.Event;
import com.winlator.cmod.runtime.system.LogManager;

import java.io.IOException;
import java.util.Objects;

public class EventListener {
  public final XClient client;
  public final Bitmask eventMask;
  private final String TAG = "EventListener";

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
    } catch (IOException e) {
      if (!client.isTeardown()) {
        LogManager.logW(TAG, "Failed to send event: " + e.getMessage());
      }
    }
  }
}
