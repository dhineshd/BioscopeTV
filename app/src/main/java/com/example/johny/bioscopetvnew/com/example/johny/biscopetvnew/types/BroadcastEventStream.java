package com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types;

import lombok.Data;
import lombok.NonNull;

/**
 * Created by dhinesh.dharman on 12/27/15.
 */
@Data
public class BroadcastEventStream {
    @NonNull
    private String streamId;
    @NonNull
    private String eventId;
    private String streamName;
    @NonNull
    private String encodedUrl;
    private String encodedAlternateUrl;
    private String creator;
    private long creationTimeMs;
    private EncodedThumbnail encodedThumbnail;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BroadcastEventStream that = (BroadcastEventStream) o;

        // EventId and StreamId should uniquely identify BroadcastEventStream

        if (streamId != null ? !streamId.equals(that.streamId) : that.streamId != null)
            return false;
        return !(eventId != null ? !eventId.equals(that.eventId) : that.eventId != null);

    }

    @Override
    public int hashCode() {
        int result = streamId != null ? streamId.hashCode() : 0;
        result = 31 * result + (eventId != null ? eventId.hashCode() : 0);
        return result;
    }
}
