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
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
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
    private static final int MAX_EVENTS_TO_LIST = 10;
    private static final int MAX_EVENT_STREAMS_TO_LIST = 5;
    private static final long MAX_ACCEPTABLE_STALENESS_FOR_LIVE_STREAM_MS = 60000;
    private static final long MAX_ACCEPTABLE_STALENESS_FOR_EVENT_STATS_MS = 30000;


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
        event.setProperty("creationTimeMs", System.currentTimeMillis());
        event.setProperty("eventName", eventName);
        event.setProperty("creator", creator);

        datastore.put(event);

        response.setData(eventId);
        return response;
    }

    /** A method to view event stats */
    @ApiMethod(name = "getEventStats")
    public MyBean getEventStats(@Named("eventId") String eventId) {
        MyBean response = new MyBean();

        // TODO : Check if event exists and throw exception otherwise

        Filter eventFilter = new FilterPredicate("eventId", FilterOperator.EQUAL, eventId);
        Filter freshnessFilter = new FilterPredicate("lastUpdatedTimeMs",
                FilterOperator.GREATER_THAN,
                System.currentTimeMillis() - MAX_ACCEPTABLE_STALENESS_FOR_EVENT_STATS_MS);
        Filter filter = Query.CompositeFilterOperator.and(eventFilter, freshnessFilter);

        Query query = new Query("EventStats").setFilter(filter);
        int viewerCount = datastore.prepare(query).countEntities(FetchOptions.Builder.withDefaults());

        EventStats eventStats = new EventStats();
        eventStats.viewerCount = viewerCount;

        response.setData(gson.toJson(eventStats));
        return response;
    }

    /** A method to update event stats */
    @ApiMethod(name = "updateEventStats", path = "update_event_stats/{eventId}/{viewerId}")
    public MyBean updateEventStats(@Named("eventId") String eventId, @Named("viewerId") String viewerId) {
        MyBean response = new MyBean();

        Key eventStatsKey = KeyFactory.createKey("EventStats", viewerId);
        Entity eventStats = null;
        try {
            eventStats = datastore.get(eventStatsKey);
            eventStats.setProperty("lastUpdatedTimeMs", System.currentTimeMillis());
        } catch (EntityNotFoundException e) {
            // Create nee entity
            eventStats = new Entity("EventStats", viewerId);
            eventStats.setProperty("viewerId", viewerId);
            eventStats.setProperty("eventId", eventId);
            long currentTime = System.currentTimeMillis();
            eventStats.setProperty("creationTimeMs", currentTime);
            eventStats.setProperty("lastUpdatedTimeMs", currentTime);
        }
        datastore.put(eventStats);

        return response;
    }

    private class EventStats {
        int viewerCount;
    }

    /** A method to create a stream for a given event */
    @ApiMethod(name = "createEventStream")
    public MyBean createEventStream(
            @Named("eventId") String eventId,
            @Named("streamName") String streamName,
            @Named("encodedUrl") String encodedUrl,
            @Named("creator") String creator) {

        // TODO : Check if event exists and throw exception otherwise

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
            eventStream.setProperty("streamName", streamName);
            long currentTime = System.currentTimeMillis();
            eventStream.setProperty("creationTimeMs", currentTime);
            eventStream.setProperty("lastUpdatedTimeMs", currentTime);
            eventStream.setProperty("encodedUrl", encodedUrl);
            eventStream.setProperty("creator", creator);
            eventStream.setProperty("isLive", true);

            datastore.put(eventStream);

            // TODO : Remove this in future once all clients have migrated to app versions 12+ (no thumbnail)
            createGenerateThumbnailTask(streamId, encodedUrl);

            createGenerateAlternateUrlTask(streamId, encodedUrl, true);
        }

        response.setData(streamId);
        return response;
    }

    private class EventStream {
        String streamId;
        String encodedUrl;
        boolean isLive;

    }


    private static final String CREATE_THUMBNAIL_TASK_QUEUE_NAME = "pull-queue";
    private static final String CREATE_STREAM_TASK_QUEUE_NAME = "create-stream-task-queue";

    private void createGenerateThumbnailTask(final String streamId, final String encodedUrl) {

        // Create an asynchronous task (to be processed in GCE) to generate thumbnails for stream
        // and write them to data store
        EventStream stream = new EventStream();
        stream.encodedUrl = encodedUrl;
        stream.streamId = streamId;
        Queue q = QueueFactory.getQueue(CREATE_THUMBNAIL_TASK_QUEUE_NAME);
        q.add(TaskOptions.Builder.withMethod(TaskOptions.Method.PULL)
                .payload(gson.toJson(stream)));
    }

    private void createGenerateAlternateUrlTask(final String streamId, final String encodedUrl, final boolean isLive) {

        // Create an asynchronous task (to be processed in GCE) to generate thumbnails for stream
        // and write them to data store
        EventStream stream = new EventStream();
        stream.encodedUrl = encodedUrl;
        stream.streamId = streamId;
        stream.isLive = isLive;
        Queue q = QueueFactory.getQueue(CREATE_STREAM_TASK_QUEUE_NAME);
        q.add(TaskOptions.Builder.withMethod(TaskOptions.Method.PULL)
                .payload(gson.toJson(stream)));
    }

    /** A method to update stream info for a given event */
    @ApiMethod(name = "updateEventStream")
    public MyBean updateEventStream(
            @Named("streamId") String streamId,
            @Named("isLive") boolean isLive) {

        // TODO : Check if stream exists and throw exception otherwise

        MyBean response = new MyBean();

        Key streamKey = KeyFactory.createKey("EventStream", streamId);
        Entity stream = null;
        try {
            stream = datastore.get(streamKey);
            stream.setProperty("isLive", isLive);
            stream.setProperty("lastUpdatedTimeMs", System.currentTimeMillis());
            datastore.put(stream);

            // TODO : Remove this in future once all clients have migrated to app versions 12+ (no thumbnail)
            createGenerateThumbnailTask(streamId, (String) stream.getProperty("encodedUrl"));

            createGenerateAlternateUrlTask(streamId, (String) stream.getProperty("encodedUrl"), isLive);

        } catch (EntityNotFoundException e) {
            // ignore (to be handled as part of input validation)
        }

        return response;
    }

    /** A method to update stream info for a given event */
    @ApiMethod(name = "updateEventStreamThumbnail", path = "update_event_stream_thumbnail/{streamId}/{encodedThumbnail}")
    public MyBean updateEventStreamThumbnail(
            @Named("streamId") String streamId,
            @Named("encodedThumbnail") String encodedThumbnail) {

        // TODO : Check if stream exists and throw exception otherwise

        MyBean response = new MyBean();

        Key streamKey = KeyFactory.createKey("EventStream", streamId);
        Entity stream = null;
        try {
            stream = datastore.get(streamKey);
            stream.setProperty("encodedThumbnail", encodedThumbnail);
            stream.setProperty("lastThumbnailUpdatedTimeMs", System.currentTimeMillis());
            datastore.put(stream);
        } catch (EntityNotFoundException e) {
            // ignore (to be handled as part of input validation)
        }

        return response;
    }

    /** A method to list all events */
    @ApiMethod(name = "listEvents")
    public MyBean listEvents() {
        MyBean response = new MyBean();

        Query query = new Query("Event").addSort("creationTimeMs", Query.SortDirection.DESCENDING);

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
            if (Boolean.TRUE.equals(isLive)) {
                // Only consider streams updated recently even if they are all marked live
                Filter freshnessFilter = new FilterPredicate("lastUpdatedTimeMs",
                        FilterOperator.GREATER_THAN,
                        System.currentTimeMillis() - MAX_ACCEPTABLE_STALENESS_FOR_LIVE_STREAM_MS);
                filter = Query.CompositeFilterOperator.and(eventFilter, statusFilter, freshnessFilter);
            }
        }

        Query query = new Query("EventStream").setFilter(filter)
                .addSort("lastUpdatedTimeMs", Query.SortDirection.DESCENDING);

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
                .addSort("lastUpdatedTimeMs", Query.SortDirection.DESCENDING);
        List<Map<String, Object>> eventStreams = new LinkedList<>();
        for (Entity result : datastore.prepare(query).asList(
                FetchOptions.Builder.withLimit(MAX_EVENT_STREAMS_TO_LIST))) {
            eventStreams.add(result.getProperties());
        }
        return eventStreams;
    }
}
