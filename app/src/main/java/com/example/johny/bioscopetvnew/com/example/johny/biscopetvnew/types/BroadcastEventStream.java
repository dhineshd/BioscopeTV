package com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types;

import lombok.Data;

/**
 * Created by dhinesh.dharman on 12/27/15.
 */
@Data
public class BroadcastEventStream {
    private String streamId;
    private String eventId;
    private String encodedUrl;
    private String creator;
    private long timestampMs;
}
