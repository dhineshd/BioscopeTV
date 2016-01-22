package com.example.johny.bioscopetvnew;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEvent;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEventStream;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.EncodedThumbnail;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.EventStats;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import lombok.ToString;

public class ListEventStreamsActivity extends AppCompatActivity {
    private static final String TAG = "ListEventStreams";
    private static final long REFRESH_INTERVAL_MS = 5000;

    private String userId;
    private long latestUserInteractionTimestampMs;
    private Handler refreshHandler;
    private Runnable refreshRunnable;

    private Gson gson = new Gson();
    private VideoView videoView;
    private TextView textViewEventName;
    private TextView textViewEventViewers;
    private TextView textViewEventStatus;
    private TextView textViewStreamSearchStatus;
    private EventStreamListAdapter eventStreamListAdapter;
    private Set<BroadcastEventStream> streamsSet = new HashSet<>();
    private Set<AsyncTask> asyncTasks = new HashSet<>();
    private LruCache<BroadcastEventStream, Bitmap> streamThumbnailCache;
    private BroadcastEventStream mainEventStream;
    private BroadcastEvent event;
    private boolean isLiveEvent;
    private int viewerCountForEvent = 1; // Min value is 1 since current user is a viewer

    private TextView mainVideoBufferingStreamTextView;
    private ImageButton tweetButton;
    private TextView videoViewStatus;

    private BioscopeBroadcastService serviceClient;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_event_streams);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        userId = ((BioscopeTVApplication) getApplication()).getUserId();

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        event = gson.fromJson(extras.getString(MainActivity.EVENT_KEY), BroadcastEvent.class);
        isLiveEvent = extras.getBoolean(MainActivity.IS_LIVE_KEY);

        initializeClient();

        mainVideoBufferingStreamTextView = (TextView) findViewById(R.id.textview_stream_buffering);

        videoView = (VideoView) findViewById(R.id.videoview_view_stream);

        textViewEventName = (TextView) findViewById(R.id.textview_event_title);
        textViewEventName.setText(event.getEventName());

        textViewEventViewers = (TextView) findViewById(R.id.textview_event_viewers);
        textViewEventViewers.setText(viewerCountForEvent + "");

        textViewEventStatus = (TextView) findViewById(R.id.textview_event_status);

        textViewStreamSearchStatus = (TextView) findViewById(R.id.textview_stream_status);

        tweetButton = (ImageButton) findViewById(R.id.tweetButton);

        videoViewStatus = (TextView) findViewById(R.id.videoview_status);

        tweetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String tweetUrl =
                        String.format("https://twitter.com/intent/tweet?text=%s&url=%s",
                                urlEncode(event.getEventName() +" is live on BioscopeTV! " +
                                        "Catch the action from multiple angles!! @Thebioscopeapp"),
                                urlEncode("goo.gl/xRee5b"));
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(tweetUrl));

                // Narrow down to official Twitter app, if available:
                List<ResolveInfo> matches = getPackageManager().queryIntentActivities(intent, 0);
                for (ResolveInfo info : matches) {
                    if (info.activityInfo.packageName.toLowerCase().startsWith("com.twitter")) {
                        intent.setPackage(info.activityInfo.packageName);
                    }
                }

                startActivity(intent);
            }
        });

        eventStreamListAdapter = new EventStreamListAdapter(getApplicationContext());
        GridView listViewEventStreams = (GridView) findViewById(R.id.listview_event_streams);
        listViewEventStreams.setAdapter(eventStreamListAdapter);
        listViewEventStreams.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                playStreamAsMainVideo(eventStreamListAdapter.getItem(position));
            }
        });

        // Refresh list periodically
        refreshHandler = new Handler();
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                performRefreshTasks();
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };

        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        streamThumbnailCache = new LruCache<BroadcastEventStream, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(BroadcastEventStream key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            Log.wtf(TAG, "UTF-8 should always be supported", e);
            throw new RuntimeException("URLEncoder.encode() failed for " + s);
        }
    }

    private Uri decodeURL(final String encodedUrl) throws UnsupportedEncodingException {
        // Note : Uri.parse(encodedUrl) doesnt not work on all devices. Hence, use of URLDecoder.
        return Uri.parse(URLDecoder.decode(encodedUrl, "UTF-8"));
    }

    private void playStreamAsMainVideo(final BroadcastEventStream stream) {

        if (stream.equals(mainEventStream)) {
            // Already playing chosen stream. nothing to do..
            Log.i(TAG, "Already playing selected stream URL = " + stream.getEncodedUrl());
            return;
        }

        Log.i(TAG, "Starting playback for selected stream URL = " + stream.getEncodedUrl());

        mainEventStream = stream;

        // Refresh to ensure stream we play in main video shows up as the selected
        // (rectangle) in the list of streams
        eventStreamListAdapter.notifyDataSetChanged();

        try {
            if (videoView.isPlaying()) {
                videoView.stopPlayback();
            }
            Uri uri = decodeURL(stream.getEncodedUrl());
            videoView.setVideoURI(uri);
            videoView.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        mainVideoBufferingStreamTextView.setVisibility(View.VISIBLE);
                        Log.i(TAG, "Buffering just started!");
                    } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.i(TAG, "Received first video frame!");
                        mainVideoBufferingStreamTextView.setVisibility(View.INVISIBLE);
                    }
                    //viewHolder.progressBar.setVisibility(mp.isPlaying()? View.INVISIBLE : View.VISIBLE);
                    return true;
                }
            });

            videoView.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play main video from stream = " + stream);
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume invoked!");
        super.onResume();

        performRefreshTasks();

        // Refresh periodically only for live events
        if (isLiveEvent) {
            refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        }
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause invoked!");
        super.onPause();

        refreshHandler.removeCallbacks(refreshRunnable);

        if (isFinishing()) {
            cleanup();
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        latestUserInteractionTimestampMs = System.currentTimeMillis();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (System.currentTimeMillis() - latestUserInteractionTimestampMs < 10) {
            Log.i(TAG, "Detected that user is leaving..");
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }

    private void cleanup() {
        Log.i(TAG, "Performing cleanup..");

        for (AsyncTask task : asyncTasks) {
            if (task != null) {
                task.cancel(true);
            }
        }
        if (refreshHandler != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
    }

    private void performRefreshTasks() {
        // Cancel any running tasks
        for (AsyncTask task : asyncTasks) {
            if (task != null) {
                task.cancel(true);
            }
        }

        asyncTasks.add(new ListEventStreamsTask().execute());

        // Update and get event stats only for live events
        if (isLiveEvent) {
            asyncTasks.add(new UpdateEventStatsTask().execute(event.getEventId(), userId));
            asyncTasks.add(new GetEventStatsTask().execute(event.getEventId()));
        }
    }

    private void initializeClient() {
        if(serviceClient == null) {  // Only do this once
            BioscopeBroadcastService.Builder builder =
                    new BioscopeBroadcastService.Builder(AndroidHttp.newCompatibleTransport(),
                    new AndroidJsonFactory(), null)
                    .setRootUrl(MainActivity.ROOT_URL)
                    .setGoogleClientRequestInitializer(new GoogleClientRequestInitializer() {
                        @Override
                        public void initialize(AbstractGoogleClientRequest<?> abstractGoogleClientRequest) throws IOException {
                            abstractGoogleClientRequest.setDisableGZipContent(true);
                        }
                    });
            // end options for devappserver

            serviceClient = builder.build();
        }
    }

    class ListEventStreamsTask extends AsyncTask<Void, Void, List<BroadcastEventStream>> {

        @Override
        protected List<BroadcastEventStream> doInBackground(Void... params) {

            try {
                String response = serviceClient.listEventStreams(event.getEventId(), isLiveEvent).execute().getData();
                Log.i(TAG, "Received ListEventStreams response..");
                final List<BroadcastEventStream> eventStreams =  gson.fromJson(response,
                        new TypeToken<List<BroadcastEventStream>>() {
                        }.getType());

                // Sort streams by name
                Collections.sort(eventStreams, new Comparator<BroadcastEventStream>() {
                    @Override
                    public int compare(BroadcastEventStream lhs, BroadcastEventStream rhs) {
                        if (lhs.getStreamName() != null && rhs.getStreamName() != null) {
                            return lhs.getStreamName().compareToIgnoreCase(rhs.getStreamName());
                        }
                        return 0;
                    }
                });

                return eventStreams;
            } catch (Exception e) {
                Log.e(TAG, "Failed to list event streams", e);
                return Collections.EMPTY_LIST;
            }
        }

        @Override
        protected void onPostExecute(final List<BroadcastEventStream> eventStreams) {

            if (eventStreams != null && !eventStreams.isEmpty()) {
                textViewStreamSearchStatus.setVisibility(View.INVISIBLE);
                textViewEventStatus.setVisibility(isLiveEvent? View.VISIBLE : View.INVISIBLE);
                textViewEventViewers.setVisibility(isLiveEvent? View.VISIBLE : View.INVISIBLE);
                tweetButton.setVisibility(isLiveEvent? View.VISIBLE : View.INVISIBLE);
                videoView.setVisibility(View.VISIBLE);
                videoViewStatus.setVisibility(View.INVISIBLE);
            }

            if(eventStreams == null || eventStreams.isEmpty()) {

                if(isLiveEvent) {
                    //This is a live event and there are no live streams
                    if(!videoView.isPlaying()) {
                        videoView.setVisibility(View.INVISIBLE);
                        videoViewStatus.setVisibility(View.VISIBLE);
                    }

                }
            }

            // Remove expired streams from view
            for (Iterator<BroadcastEventStream> it = streamsSet.iterator(); it.hasNext();) {
                BroadcastEventStream liveStream = it.next();
                if (!eventStreams.contains(liveStream)) {
                    it.remove();//remove from livestreams
                    eventStreamListAdapter.remove(liveStream);
                    Log.i(TAG, "Removed non-live stream : " + liveStream);
                }
            }

            // Add received streams to view
            for (final BroadcastEventStream stream : eventStreams) {
                if (stream != null) {
                    eventStreamListAdapter.add(stream);
                    Log.i(TAG, "Found non-live stream : streamId = " + stream.getStreamId());
                }
            }

            // Update main video if the corresponding stream doesnt appear in refreshed list
            if (eventStreamListAdapter.getCount() > 0 && !streamsSet.contains(mainEventStream)) {
                playStreamAsMainVideo(eventStreamListAdapter.getItem(0));
            }
        }
    }

    private class UpdateEventStatsTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... params) {
            String eventId = params[0];
            String viewerId = params[1];
            try {
                serviceClient.updateEventStats(eventId, viewerId).execute();
                Log.i(TAG, "Successfully updated event stats for eventId = " + eventId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update event stats for eventId = " + eventId, e);
            }
            return null;
        }
    }

    private class GetEventStatsTask extends AsyncTask<String, Void, EventStats> {

        @Override
        protected EventStats doInBackground(String... params) {
            String eventId = params[0];
            try {
                return gson.fromJson(serviceClient.getEventStats(eventId).execute().getData(), EventStats.class);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get event stats for eventId = " + eventId, e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(EventStats eventStats) {
            super.onPostExecute(eventStats);

            if (eventStats != null) {
                viewerCountForEvent = Math.max(1, eventStats.getViewerCount());
            }
            Log.i(TAG, "Number of viewers for event = " + viewerCountForEvent);

            textViewEventViewers.setText("" + viewerCountForEvent);
        }
    }

    private class EventStreamListAdapter extends ArrayAdapter<BroadcastEventStream> {
        private VideoView playingStreamVideoView;
        private ViewHolder playingStreamVideoViewHolder;

        public EventStreamListAdapter(Context context) {
            super(context, 0);
        }

        @Override
        public void add(BroadcastEventStream object) {

            // Decode and store thumbnail for stream
            EncodedThumbnail encodedThumbnail = object.getEncodedThumbnail();
            if (encodedThumbnail != null && encodedThumbnail.getValue() != null) {
                Bitmap thumbnailImage = decodeBase64(object.getEncodedThumbnail().getValue());
                streamThumbnailCache.put(object, thumbnailImage);
            }

            if (!streamsSet.contains(object)) {
                if (streamsSet.isEmpty()) {
                    ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressbar_loading_streams);
                    progressBar.setVisibility(View.GONE);
                    playStreamAsMainVideo(object);
                }
                streamsSet.add(object);
                super.add(object);
            } else {
                notifyDataSetChanged();
            }
        }

        private Bitmap decodeBase64(final String input) {
            byte[] decodedByte = Base64.decode(input, 0);
            return BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            try {
                // Get the data item for this position
                final BroadcastEventStream eventStream = getItem(position);

                final ViewHolder viewHolder;
                // Check if an existing view is being reused, otherwise inflate the view
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_event_stream, parent, false);
                    viewHolder = createViewHolder(convertView);
                    convertView.setTag(viewHolder);
                } else {
                    viewHolder = (ViewHolder) convertView.getTag();
                }

                // Set stream info (name etc)
                String streamName = eventStream.getStreamName();
                viewHolder.streamInfo.setText(streamName == null ? "" : streamName);

                // Show thumbnail
//                Bitmap thumbnailImage = streamThumbnailCache.get(eventStream);
//                if (thumbnailImage != null) {
//                    viewHolder.streamThumbnail.setImageBitmap(thumbnailImage);
//                } else {
//                    viewHolder.streamThumbnail.setImageDrawable(
//                            getDrawableForAPILevel(R.drawable.bioscope_launch_icon_full_tail));
//                }


                // Show rectangle around currently playing stream
                if (eventStream.equals(mainEventStream)) {
                    viewHolder.progressBar.setVisibility(View.INVISIBLE);
                    viewHolder.videoViewPath = "";
                    if (viewHolder.streamVideo.isPlaying()) {
                        Log.i(TAG, "Stopping playback since it is playing as main video");
                        viewHolder.streamVideo.stopPlayback();
                    }
                    viewHolder.itemLayout.setBackground(getDrawableForAPILevel(R.drawable.rectangle));
                } else {

                    // Play stream video (change the video only if requested stream is
                    // different from currently playing stream for this list element)

                    if (!viewHolder.videoViewPath.equals(eventStream.getEncodedUrl()) &&
                            !viewHolder.videoViewPath.equals(eventStream.getEncodedAlternateUrl())) {

                        if (viewHolder.streamVideo.isPlaying()) {
                            Log.i(TAG, "Stopping playback for position = " + position);
                            viewHolder.streamVideo.stopPlayback();
                        }
                        Uri uri = decodeURL(eventStream.getEncodedUrl());
                        Log.i(TAG, "Setting video path for position = " + position + " url = " + uri.toString());

                        viewHolder.videoViewPath = "";

                        // Use alternate stream (low quality) for live events. For non-live events,
                        // the alternate stream will be destroyed and hence not available.
                        if (isLiveEvent) {
                            if (eventStream.getEncodedAlternateUrl() != null) {
                                viewHolder.videoViewPath = eventStream.getEncodedAlternateUrl();
                            }
                        } else {
                            viewHolder.videoViewPath = eventStream.getEncodedUrl();
                        }

                        if (!viewHolder.videoViewPath.isEmpty()) {
                            viewHolder.streamVideo.setVideoURI(uri);
                            viewHolder.streamVideo.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                                @Override
                                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                                    Log.i(TAG, "onInfo for position = " + position + " what = " + what);
                                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                        viewHolder.progressBar.setVisibility(View.VISIBLE);
                                    } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                        viewHolder.progressBar.setVisibility(View.INVISIBLE);
                                    }
                                    return true;
                                }
                            });
                            viewHolder.streamVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer mp) {
                                    Log.i(TAG, "Prepared mp for position = " + position);
                                    // No audio for list videos playback
                                    mp.setVolume(0f, 0f);

                                    // For non-live events, we want to keep playing stream in a loop
                                    if (!isLiveEvent) {
                                        mp.setLooping(true);
                                    }
                                }
                            });
                            viewHolder.streamVideo.start();
                        }
                    }
                    viewHolder.itemLayout.setBackground(null);
                }

            } catch (Exception e) {
                Log.w(TAG, "Failed to load view for position = " + position, e);
            }

            return convertView;
        }

        private ViewHolder createViewHolder(final View view) {
            ViewHolder viewHolder = new ViewHolder();
            viewHolder.itemLayout = (FrameLayout) view.findViewById(R.id.list_item_event_stream_framelayout);
            viewHolder.streamInfo = (TextView) view.findViewById(R.id.list_item_event_stream_textview);
            //viewHolder.streamThumbnail = (ImageView) view.findViewById(R.id.list_item_event_stream_imageview);
            viewHolder.streamVideo = (VideoView) view.findViewById(R.id.list_item_event_stream_videoview);
            viewHolder.progressBar = (ProgressBar) view.findViewById(R.id.list_item_event_stream_progressbar);
            viewHolder.videoViewPath = "";
            return viewHolder;
        }
    }

    private Drawable getDrawableForAPILevel(int id) {
        // Use new method for API 21+
        if (Build.VERSION.SDK_INT >= 21) {
            return getDrawable(id);
        }
        return getResources().getDrawable(id);
    }

    // Not using getter/setter or Lombok for optimization
    @ToString
    private static class ViewHolder {
        FrameLayout itemLayout;
        TextView streamInfo;
        //ImageView streamThumbnail;
        VideoView streamVideo;
        String videoViewPath;
        ProgressBar progressBar;
    }

    private void switchView() {
        // TODO Fix this logic to work with scrolling when views are re-used.
//                    ViewGroup mainVideoViewParent = (ViewGroup) findViewById(R.id.layout_main_video);
//                    VideoView itemVideoView = viewHolder.streamVideo;
//                    // Set item videoview as main videoview if not already done
//                    if (!itemVideoView.getParent().equals(mainVideoViewParent)) {
//
//                        ViewGroup itemVideoViewParent = (ViewGroup) itemVideoView.getParent();
//                        ViewGroup.LayoutParams itemLayoutParams = itemVideoView.getLayoutParams();
//                        itemVideoViewParent.removeView(itemVideoView);
//
//                        Log.i(TAG, "Main video parent child count = " + mainVideoViewParent.getChildCount());
//                        Log.i(TAG, "Item video parent child count = " + itemVideoViewParent.getChildCount());
//
//                        if (playingStreamVideoView != null && playingStreamVideoViewHolder != null) {
//                            // Add the currently playing videoview back to list
//                            mainVideoViewParent.removeView(playingStreamVideoView);
//                            playingStreamVideoViewHolder.streamVideo = playingStreamVideoView;
//                        }
//                        RelativeLayout.LayoutParams mainVideoLayoutParams = new RelativeLayout.LayoutParams(
//                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
//                        mainVideoLayoutParams.alignWithParent = true;
//                        mainVideoViewParent.addView(itemVideoView, mainVideoLayoutParams);
//                        playingStreamVideoView = itemVideoView;
//                        playingStreamVideoViewHolder = viewHolder;
//                        Log.i(TAG, "Changed main videoview to play stream in position = " + position);
//                    } else {
//                        Log.i(TAG, "Already set selected video as main video position = " + position);
//                    }
    }

}
