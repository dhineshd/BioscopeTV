package com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types;

import lombok.Data;

/**
 * Created by dhinesh.dharman on 1/7/16.
 */
@Data
public class BroadcastEvent {
    private String eventId;
    private String eventName;
    private String creator;
    private long creationTimeMs;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BroadcastEvent that = (BroadcastEvent) o;

        return !(eventId != null ? !eventId.equals(that.eventId) : that.eventId != null);

    }

    @Override
    public int hashCode() {
        return eventId != null ? eventId.hashCode() : 0;
    }
}
