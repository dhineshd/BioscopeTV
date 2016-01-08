/*
   For step-by-step instructions on connecting your Android application to this backend module,
   see "App Engine Java Endpoints Module" template documentation at
   https://github.com/GoogleCloudPlatform/gradle-appengine-templates/tree/master/HelloEndpoints
*/

package com.bioscope.tv.backend;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.ApiNamespace;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.repackaged.com.google.gson.Gson;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

/** An endpoint class we are exposing */
@Api(
  name = "bioscopeBroadcastService",
  version = "v1",
  namespace = @ApiNamespace(
    ownerDomain = "backend.tv.bioscope.com",
    ownerName = "backend.tv.bioscope.com",
    packagePath=""
  )
)
public class MyEndpoint {
    private static final int MAX_EVENTS_TO_LIST = 5;
    private static final int MAX_EVENT_STREAMS_TO_LIST = 5;

    private DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

    private Gson gson = new Gson();

    /** A method to create a new event */
    @ApiMethod(name = "createEvent")
    public MyBean createEvent(@Named("eventName") String eventName, @Named("creator") String creator) {
        MyBean response = new MyBean();

        // Generate unique eventId (TODO : Query and check for collisions)
        String eventId = java.util.UUID.randomUUID().toString();

        Entity event = new Entity("Event", eventId);
        event.setProperty("eventId", eventId);
        event.setProperty("timestampMs", System.currentTimeMillis());
        event.setProperty("eventName", eventName);
        event.setProperty("creator", creator);

        datastore.put(event);

        response.setData(eventId);
        return response;
    }

    /** A method to create a stream for a given event */
    @ApiMethod(name = "createEventStream")
    public MyBean createEventStream(
            @Named("eventId") String eventId,
            @Named("encodedUrl") String encodedUrl,
            @Named("creator") String creator) {

        MyBean response = new MyBean();

        // Check if stream already exists for given URL and create only if it doesn't.

        boolean eventStreamExists = false;
        String streamId = "";

        for (Map<String, Object> eventStream : listEventStreamsHelper(eventId)) {
            if (encodedUrl.equalsIgnoreCase((String) eventStream.get("encodedUrl"))) {
                eventStreamExists = true;
                streamId = (String) eventStream.get("streamId");
                break;
            }
        }
        if (!eventStreamExists) {

            // Generate unique streamId (TODO : Query and check for collisions)
            streamId = java.util.UUID.randomUUID().toString();

            Entity eventStream = new Entity("EventStream", streamId);
            eventStream.setProperty("streamId", streamId);
            eventStream.setProperty("eventId", eventId);
            eventStream.setProperty("timestampMs", System.currentTimeMillis());
            eventStream.setProperty("encodedUrl", encodedUrl);
            eventStream.setProperty("creator", creator);
            eventStream.setProperty("isLive", true);

            datastore.put(eventStream);
        }

        response.setData(streamId);
        return response;
    }

    /** A method to update stream info for a given event */
    @ApiMethod(name = "updateEventStream")
    public MyBean updateEventStreamStatus(
            @Named("streamId") String streamId,
            @Named("isLive") boolean isLive) {

        MyBean response = new MyBean();

        Key streamKey = KeyFactory.createKey("EventStream", streamId);
        Entity stream = null;
        try {
            stream = datastore.get(streamKey);
            stream.setProperty("isLive", isLive);
            datastore.put(stream);
        } catch (EntityNotFoundException e) {
            // TODO : entity not found, propagate failure to client
        }

        return response;
    }

    /** A method to list all events */
    @ApiMethod(name = "listEvents")
    public MyBean listEvents() {
        MyBean response = new MyBean();

        Query query = new Query("Event").addSort("timestampMs", Query.SortDirection.DESCENDING);

        List<Map<String, Object>> events = new LinkedList<>();
        for (Entity result : datastore.prepare(query).asList(
                FetchOptions.Builder.withLimit(MAX_EVENTS_TO_LIST))) {
            events.add(result.getProperties());
        }
        response.setData(gson.toJson(events));
        return response;
    }

    /** A method to list streams for a given event */
    @ApiMethod(name = "listEventStreams")
    public MyBean listEventStreams(
            @Named("eventId") String eventId,
            @Named("isLive") Boolean isLive) {
        MyBean response = new MyBean();

        Filter eventFilter = new FilterPredicate("eventId", FilterOperator.EQUAL, eventId);
        Filter filter = eventFilter;
        if (isLive != null) {
            Filter statusFilter = new FilterPredicate("isLive", FilterOperator.EQUAL, isLive);
            filter = Query.CompositeFilterOperator.and(eventFilter, statusFilter);
        }

        Query query = new Query("EventStream").setFilter(filter)
                .addSort("timestampMs", Query.SortDirection.DESCENDING);

        List<Map<String, Object>> eventStreams = new LinkedList<>();
        for (Entity result : datastore.prepare(query).asList(
                FetchOptions.Builder.withLimit(MAX_EVENT_STREAMS_TO_LIST))) {
            eventStreams.add(result.getProperties());
        }
        response.setData(gson.toJson(eventStreams));
        return response;
    }

    List<Map<String, Object>> listEventStreamsHelper(final String eventId) {
        Filter propertyFilter = new FilterPredicate("eventId", FilterOperator.EQUAL, eventId);
        Query query = new Query("EventStream").setFilter(propertyFilter)
                .addSort("timestampMs", Query.SortDirection.DESCENDING);
        List<Map<String, Object>> eventStreams = new LinkedList<>();
        for (Entity result : datastore.prepare(query).asList(
                FetchOptions.Builder.withLimit(MAX_EVENT_STREAMS_TO_LIST))) {
            eventStreams.add(result.getProperties());
        }
        return eventStreams;
    }
}
