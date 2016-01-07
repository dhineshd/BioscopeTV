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
    private long timestampMs;
}
