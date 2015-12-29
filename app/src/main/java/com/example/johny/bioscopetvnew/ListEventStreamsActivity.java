package com.example.johny.bioscopetvnew;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEventStream;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ListEventStreamsActivity extends AppCompatActivity {
    public static final String EVENT_ID_KEY = "EVENT_ID";

    private static final String CLIENT_ID = "qXKng;GQRIjCHE3@nKvviEjcI31_8lkRDc0-?ci6";
    private static final String CLIENT_SECRET = "@E=FJIv8Tkmp8AG@Vh;e1QE!Msku-uVh?=hmguvStuVKW59sRx_HIJrDla=eDx8GsWBav=l8_sZ31hPz6qjKFRTdpKD@3SoX?;cqUn8trkxnnD;A6gIuuvD:0C466Gwx";
    private static final String TAG = "ListEventStreams";

    private String eventId;

    private Gson gson = new Gson();

    private BioscopeBroadcastService serviceClient;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_event_streams);

        Intent intent = getIntent();
        eventId = intent.getStringExtra(EVENT_ID_KEY);

        initializeClient();

        Button refreshButton = (Button) findViewById(R.id.button_refresh_streams);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshListOfEventStreams();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshListOfEventStreams();
    }

    private void refreshListOfEventStreams() {
        new ListEventStreamsTask().execute();
    }

    private void initializeClient() {
        if(serviceClient == null) {  // Only do this once
            BioscopeBroadcastService.Builder builder = new BioscopeBroadcastService.Builder(AndroidHttp.newCompatibleTransport(),
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
                String response = serviceClient.listEventStreams(eventId).execute().getData();

                Log.i(TAG, "Received ListEventStreams response = " + response);

                return gson.fromJson(response,
                        new TypeToken<List<BroadcastEventStream>>() {
                        }.getType());
            } catch (Exception e) {
                Log.e(TAG, "Failed to list events", e);
                return Collections.EMPTY_LIST;
            }
        }

        @Override
        protected void onPostExecute(final List<BroadcastEventStream> eventStreams) {

            ListView listViewEventStreams = (ListView) findViewById(R.id.listview_event_streams);
            EventStreamListAdapter adapter = new EventStreamListAdapter(getApplicationContext(), eventStreams);
            listViewEventStreams.setAdapter(adapter);
            listViewEventStreams.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    final BroadcastEventStream selectedEventStream = eventStreams.get(position);

                    Log.i(TAG, "Selected stream URL = " + selectedEventStream.getEncodedUrl());

//                    try {
//                        Kickflip.startMediaPlayerActivity(ListEventStreamsActivity.this,
//                                URLDecoder.decode(selectedEventStream.url, "UTF-8"), false);
//
//                    } catch (UnsupportedEncodingException e) {
//                        Log.e(TAG, "Failed to decode stream URL", e);
//                    }

                    Intent intent = new Intent(ListEventStreamsActivity.this, ViewStreamActivity.class);
                    intent.putExtra(ViewStreamActivity.STREAM_INFO_KEY, gson.toJson(selectedEventStream));
                    startActivity(intent);
                }
            });

        }
    }

    private class EventStreamListAdapter extends ArrayAdapter<BroadcastEventStream> {
        public EventStreamListAdapter(Context context, List<BroadcastEventStream> objects) {
            super(context, 0, objects);
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

                final String streamInfo = "Stream created by \n" + eventStream.getCreator() +
                        "\nat " + new Date(eventStream.getTimestampMs()) + "\n";
                final String streamStatus = "Checking status..";
                viewHolder.streamInfo.setTextColor(Color.WHITE);
                viewHolder.streamInfo.setText(streamInfo + streamStatus);
                viewHolder.streamVideo.setVideoURI(Uri.parse(URLDecoder.decode(eventStream.getEncodedUrl(), "UTF-8")));
                viewHolder.streamVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        mp.setVolume(0f, 0f);
                        Log.i(TAG, "Track duration = " + mp.getDuration());
                        if (mp.getDuration() <= 0) {
                            viewHolder.streamInfo.setTextColor(Color.RED);
                            viewHolder.streamInfo.setText(streamInfo + "LIVE!");
                            viewHolder.streamVideo.start();
                        } else {
                            viewHolder.streamInfo.setText(streamInfo);
                        }
                    }
                });

            } catch (Exception e) {
                Log.w(TAG, "Failed to load view for position = " + position);
            }

            return convertView;
        }

        private ViewHolder createViewHolder(final View view) {
            ViewHolder viewHolder = new ViewHolder();
            viewHolder.streamInfo = (TextView) view.findViewById(R.id.list_item_event_stream_textview);
            viewHolder.streamVideo = (VideoView) view.findViewById(R.id.list_item_event_stream_videoview);
            return viewHolder;
        }
    }

    // Not using getter/setter or Lombok for optimization
    private static class ViewHolder {
        TextView streamInfo;
        VideoView streamVideo;
    }

}
