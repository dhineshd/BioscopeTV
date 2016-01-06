package com.example.johny.bioscopetvnew;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.BroadcastListener;
import io.kickflip.sdk.av.SessionConfig;
import io.kickflip.sdk.exception.KickflipException;


public class MainActivity extends AppCompatActivity {
    private static final String CLIENT_ID = "qXKng;GQRIjCHE3@nKvviEjcI31_8lkRDc0-?ci6";
    private static final String CLIENT_SECRET = "@E=FJIv8Tkmp8AG@Vh;e1QE!Msku-uVh?=hmguvStuVKW59sRx_HIJrDla=eDx8GsWBav=l8_sZ31hPz6qjKFRTdpKD@3SoX?;cqUn8trkxnnD;A6gIuuvD:0C466Gwx";
    private static final String TAG = "MainActivity";
    public static final String ROOT_URL = "https://bioscope-b2074.appspot.com/_ah/api";
    private static final long REFRESH_INTERVAL_MS = 60000;
    private static final long REFRESH_CHECK_INTERVAL_MS = 20000;
    private long latestUserInteractionTimestampMs;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private Gson gson = new Gson();
    private BioscopeBroadcastService serviceClient;
    private AsyncTask listEventsTask;
    private Set<BroadcastEvent> events = new HashSet<>();
    ProgressBar progressBarLoadingEvents;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Kickflip.setup(this, CLIENT_ID, CLIENT_SECRET);

        initializeClient();

        progressBarLoadingEvents = (ProgressBar) findViewById(R.id.progressbar_loading_events);

        Button createEventButton = (Button) findViewById(R.id.button_create_event);
        createEventButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                final EditText input = new EditText(MainActivity.this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT);
                input.setLayoutParams(lp);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Enter event name")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                String eventName = input.getText().toString();
                                new CreateEventTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, eventName);
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {

                            }
                        })
                        .setView(input)
                        .show();
            }
        });

        // Refresh list periodically
        refreshHandler = new Handler();
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshListOfEvents();
                refreshHandler.postDelayed(this, REFRESH_CHECK_INTERVAL_MS);
            }
        };
        refreshHandler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            cleanup();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        cleanup();
    }

    private void cleanup() {
        Log.i(TAG, "Performing cleanup..");

        if (listEventsTask != null) {
            listEventsTask.cancel(true);
        }
        if (refreshHandler != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
    }

    private void refreshListOfEvents() {
        if (listEventsTask != null) {
            listEventsTask.cancel(true);
        }
        listEventsTask = new ListEventsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void initializeClient() {
        if(serviceClient == null) {  // Only do this once
            BioscopeBroadcastService.Builder builder = new BioscopeBroadcastService.Builder(AndroidHttp.newCompatibleTransport(),
                    new AndroidJsonFactory(), null)
                    .setRootUrl(ROOT_URL)
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

    class ListEventsTask extends AsyncTask<Void, Void, List<BroadcastEvent>> {

        @Override
        protected List<BroadcastEvent> doInBackground(Void... params) {

            try {
                String response = serviceClient.listEvents().execute().getData();

                Log.i(TAG, "Received ListEvents response = " + response);

                return gson.fromJson(response,
                        new TypeToken<List<BroadcastEvent>>(){}.getType());
            } catch (Exception e) {
                Log.e(TAG, "Failed to list events", e);
                return Collections.EMPTY_LIST;
            }
        }

        @Override
        protected void onPostExecute(final List<BroadcastEvent> events) {

            progressBarLoadingEvents.setVisibility(View.INVISIBLE);

            ListView listViewEvents = (ListView) findViewById(R.id.listview_events);
            EventListAdapter adapter = new EventListAdapter(getApplicationContext(), events);

            listViewEvents.setAdapter(adapter);
            listViewEvents.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    final BroadcastEvent selectedEvent = events.get(position);

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Select action")
                            .setPositiveButton("View", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent intent = new Intent(MainActivity.this, ListEventStreamsActivity.class);
                                    intent.putExtra(ListEventStreamsActivity.EVENT_ID_KEY, selectedEvent.eventId);
                                    startActivity(intent);
                                }
                            })
                            .setNegativeButton("Broadcast", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    startBroadcast(selectedEvent);
                                }
                            })
                            .show();
                }
            });


        }
    }

    private class EventListAdapter extends ArrayAdapter<BroadcastEvent> {
        public EventListAdapter(Context context, List<BroadcastEvent> objects) {
            super(context, 0, objects);
        }

        @Override
        public void add(BroadcastEvent object) {
            if (!events.contains(object)) {
                events.add(object);
                ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressbar_loading_events);
                progressBar.setVisibility(View.GONE);
                super.add(object);
            } else {
                notifyDataSetChanged();
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            try {
                // Get the data item for this position
                final BroadcastEvent event = getItem(position);

                ViewHolder viewHolder;
                // Check if an existing view is being reused, otherwise inflate the view
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_event, parent, false);
                    viewHolder = createViewHolder(convertView);
                    convertView.setTag(viewHolder);
                } else {
                    viewHolder = (ViewHolder) convertView.getTag();
                }

                viewHolder.eventText.setText(event.eventName + "\ncreated by " + event.creator +
                        "\nat " + new Date(event.timestampMs));

            } catch (Exception e) {
                Log.w(TAG, "Failed to load view for position = " + position);
            }

            return convertView;
        }

        private ViewHolder createViewHolder(final View view) {
            ViewHolder viewHolder = new ViewHolder();
            viewHolder.eventText = (TextView) view.findViewById(R.id.list_item_event_textview);
            return viewHolder;
        }
    }

    // Not using getter/setter or Lombok for optimization
    private static class ViewHolder {
        TextView eventText;
    }

    class CreateEventTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            try {
                String eventName = params[0];
                return serviceClient.createEvent(eventName, Build.MODEL).execute().getData();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create event", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            String notificationText = "Event created!";
            if (result == null) {
                notificationText = "Event creation failed.";
            }
            refreshListOfEvents();
            Toast.makeText(getApplicationContext(), notificationText, Toast.LENGTH_LONG).show();
        }
    }


    private void startBroadcast(final BroadcastEvent event) {
        String outputLocation = new File(getApplicationContext().getFilesDir(), "index.m3u8").getAbsolutePath();
        Kickflip.setSessionConfig(new SessionConfig.Builder(outputLocation)
                .withVideoBitrate(300 * 1000)
                //.withAudioBitrate()
                .withPrivateVisibility(false)
                .withLocation(true)
                .withTitle(event.eventName)
                .withVideoResolution(640, 360)
                .withAdaptiveStreaming(true)
                .build());

        BroadcastListener broadcastListener = new BroadcastListener() {
            @Override
            public void onBroadcastStart() {

            }

            @Override
            public void onBroadcastLive(Stream stream) {
                Log.i(TAG, "BroadcastLive @ " + stream.getKickflipUrl());
                Log.i(TAG, "Stream URL :" + stream.getStreamUrl());
                new CreateEventStreamTask().execute(event.eventId, stream.getStreamUrl());
            }

            @Override
            public void onBroadcastStop() {

            }

            @Override
            public void onBroadcastError(KickflipException e) {

            }
        };
        Kickflip.setBroadcastListener(broadcastListener);
        startActivity(new Intent(MainActivity.this, StartBroadcastActivity.class));
    }

    class CreateEventStreamTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            try {
                String eventId = params[0];
                String url = URLEncoder.encode(params[1], "UTF-8");
                return serviceClient.createEventStream(eventId, url, Build.MODEL).execute().getData();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create eventStream", e);
                return e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            Toast.makeText(getApplicationContext(), "Event stream created!", Toast.LENGTH_LONG).show();
        }
    }

    static class BroadcastEvent {
        String eventId;
        String eventName;
        String creator;
        long timestampMs;
    }
}
