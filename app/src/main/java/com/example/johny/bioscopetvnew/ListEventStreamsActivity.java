package com.example.johny.bioscopetvnew;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEvent;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEventStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ListEventStreamsActivity extends AppCompatActivity {
    private static final String TAG = "ListEventStreams";
    private static final long REFRESH_INTERVAL_MS = 60000;
    private static final long REFRESH_CHECK_INTERVAL_MS = 30000;

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
    private ProgressBar progressBarMainVideo;
    private EventStreamListAdapter eventStreamListAdapter;
    private Set<BroadcastEventStream> liveStreams = new HashSet<>();
    private Set<AsyncTask> asyncTasks = new HashSet<>();
    private Map<BroadcastEventStream, Long> eventLatestRefreshTimeMs = new HashMap<>();
    private BroadcastEventStream mainEventStream;
    private BroadcastEvent event;
    private boolean isLiveEvent;
    private int viewerCountForEvent = 1; // Min value is 1 since current user is a viewer

    private ImageButton tweetButton;

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
        isLiveEvent = Boolean.valueOf(extras.getString(MainActivity.IS_LIVE_KEY));
        Log.i(TAG, "isLiveEvent = " + isLiveEvent + " " + intent.getStringExtra(MainActivity.IS_LIVE_KEY));

        initializeClient();

        videoView = (VideoView) findViewById(R.id.videoview_view_stream);

        textViewEventName = (TextView) findViewById(R.id.textview_event_title);
        textViewEventName.setText(event.getEventName());

        textViewEventViewers = (TextView) findViewById(R.id.textview_event_viewers);
        textViewEventViewers.setText(viewerCountForEvent + "");

        textViewEventStatus = (TextView) findViewById(R.id.textview_event_status);

        textViewStreamSearchStatus = (TextView) findViewById(R.id.textview_stream_status);

        progressBarMainVideo = (ProgressBar) findViewById(R.id.progressbar_view_stream);

        tweetButton = (ImageButton) findViewById(R.id.tweetButton);

        tweetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String tweetUrl =
                        String.format("https://twitter.com/intent/tweet?text=%s&url=%s",
                                urlEncode(event.getEventName() +" is live on BioscopeTV! " +
                                        "Catch the action from multiple angles!! @Thebioscopeapp"),
                                urlEncode("http://wearebioscope.com"));
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
                refreshListOfEventStreams();
                refreshHandler.postDelayed(this, REFRESH_CHECK_INTERVAL_MS);
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

    private void playStreamAsMainVideo(final BroadcastEventStream stream) {
        Log.i(TAG, "Selected stream URL = " + stream.getEncodedUrl());
        mainEventStream = stream;
        eventStreamListAdapter.notifyDataSetChanged();
        try {
            videoView.setVideoURI(Uri.parse(URLDecoder.decode(stream.getEncodedUrl(), "UTF-8")));
            videoView.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        progressBarMainVideo.setVisibility(View.VISIBLE);
                        Log.i(TAG, "Buffering just started!");
                    } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.i(TAG, "Received first video frame!");
                        progressBarMainVideo.setVisibility(View.GONE);
                    }
                    //viewHolder.progressBar.setVisibility(mp.isPlaying()? View.INVISIBLE : View.VISIBLE);
                    return true;
                }
            });

            videoView.start();
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Failed to decode encoded URL");
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume invoked!");
        super.onResume();
        refreshHandler.post(refreshRunnable);
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

    private void refreshListOfEventStreams() {
        // Cancel any running tasks
        for (AsyncTask task : asyncTasks) {
            if (task != null) {
                task.cancel(true);
            }
        }

        asyncTasks.add(new ListEventStreamsTask().execute());
        asyncTasks.add(new UpdateEventStatsTask().execute(event.getEventId(), userId));
        asyncTasks.add(new GetEventStatsTask().execute(event.getEventId()));
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
                Log.i(TAG, "Received ListEventStreams response = " + response);
                final List<BroadcastEventStream> eventStreams =  gson.fromJson(response,
                        new TypeToken<List<BroadcastEventStream>>() {
                        }.getType());

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
            }

            // Remove expired streams from view
            for (Iterator<BroadcastEventStream> it = liveStreams.iterator(); it.hasNext();) {
                BroadcastEventStream liveStream = it.next();
                if (!eventStreams.contains(liveStream)) {
                    it.remove();//remove from livestreams
                    eventStreamListAdapter.remove(liveStream);
                    Log.i(TAG, "Removed non-live stream : streamId = " + liveStream.getStreamId());
                }
            }

            // Add received streams to view
            for (final BroadcastEventStream stream : eventStreams) {
                if (stream != null) {
                    eventStreamListAdapter.add(stream);
                    Log.i(TAG, "Found non-live stream : streamId = " + stream.getStreamId());
                }
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
        public EventStreamListAdapter(Context context) {
            super(context, 0);
        }

        @Override
        public void add(BroadcastEventStream object) {
            if (!liveStreams.contains(object)) {
                liveStreams.add(object);
                if (liveStreams.size() == 1) {
                    ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressbar_loading_streams);
                    progressBar.setVisibility(View.GONE);
                    playStreamAsMainVideo(object);
                }
                super.add(object);
            } else {
                notifyDataSetChanged();
            }
        }

        @Override
        public void remove(BroadcastEventStream object) {
            super.remove(object);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
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
                Uri url = Uri.parse(URLDecoder.decode(eventStream.getEncodedUrl(), "UTF-8"));
                if (eventStream.equals(mainEventStream)) {
                    viewHolder.streamVideo.setBackground(getDrawable(R.drawable.rectangle));
                } else {
                    viewHolder.streamVideo.setBackground(null);
                }
                if (shouldRefreshThumbnail(eventStream, position)) {
                    viewHolder.streamVideo.setVideoURI(url);
                    viewHolder.streamVideo.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                        @Override
                        public boolean onInfo(MediaPlayer mp, int what, int extra) {
                            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                viewHolder.progressBar.setVisibility(View.VISIBLE);
                                Log.i(TAG, "Buffering just started!");
                            } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                Log.i(TAG, "Received first video frame!");
                                viewHolder.progressBar.setVisibility(View.GONE);
                                mp.pause();
                                eventLatestRefreshTimeMs.put(eventStream, System.currentTimeMillis());
                            }
                            return true;
                        }
                    });
                    viewHolder.streamVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            Log.i(TAG, "OnPrepared completed! Duration = " + mp.getDuration());
                            mp.setVolume(0f, 0f);
                        }
                    });
                    viewHolder.streamVideo.start();
//                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
//                    mmr.setDataSource(url.toString(), Collections.EMPTY_MAP);
//                    viewHolder.streamThumbnail.setImageBitmap(mmr.getFrameAtTime(0));

                    Log.i(TAG, "Performing thumbnail update for position = " + position);
                } else {
                    Log.i(TAG, "Skipping thumbnail update for position = " + position);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load view for position = " + position, e);
            }

            return convertView;
        }

        private ViewHolder createViewHolder(final View view) {
            ViewHolder viewHolder = new ViewHolder();
            //viewHolder.streamInfo = (TextView) view.findViewById(R.id.list_item_event_stream_textview);
            viewHolder.streamThumbnail = (ImageView) view.findViewById(R.id.list_item_event_stream_imageview);
            viewHolder.streamVideo = (VideoView) view.findViewById(R.id.list_item_event_stream_videoview);
            viewHolder.progressBar = (ProgressBar) view.findViewById(R.id.list_item_event_stream_progressbar);
            return viewHolder;
        }

        private boolean shouldRefreshThumbnail(final BroadcastEventStream stream, final int position) {
            Long latestRefreshTimeMs = eventLatestRefreshTimeMs.get(stream);
            return (latestRefreshTimeMs == null ||
                    (System.currentTimeMillis() - latestRefreshTimeMs > REFRESH_INTERVAL_MS));

        }
    }

    // Not using getter/setter or Lombok for optimization
    private static class ViewHolder {
        TextView streamInfo;
        VideoView streamVideo;
        ImageView streamThumbnail;
        ProgressBar progressBar;
    }

}
