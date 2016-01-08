package com.example.johny.bioscopetvnew;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
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
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEvent;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.kickflip.sdk.Kickflip;


public class MainActivity extends AppCompatActivity {
    private static final String CLIENT_ID = "qXKng;GQRIjCHE3@nKvviEjcI31_8lkRDc0-?ci6";
    private static final String CLIENT_SECRET = "@E=FJIv8Tkmp8AG@Vh;e1QE!Msku-uVh?=hmguvStuVKW59sRx_HIJrDla=eDx8GsWBav=l8_sZ31hPz6qjKFRTdpKD@3SoX?;cqUn8trkxnnD;A6gIuuvD:0C466Gwx";
    private static final String TAG = "MainActivity";
    public static final String ROOT_URL = "https://bioscope-b2074.appspot.com/_ah/api";
    public static final String EVENT_KEY = "EVENT";
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
    Button createEventButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Kickflip.setup(this, CLIENT_ID, CLIENT_SECRET);

        initializeClient();

        progressBarLoadingEvents = (ProgressBar) findViewById(R.id.progressbar_loading_events);

        Button settingsButton = (Button) findViewById(R.id.button_settings);

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PreferencesActivity.class));
            }
        });

        createEventButton = (Button) findViewById(R.id.button_create_event);

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

        if(!allEventCreation()) {
            createEventButton.setVisibility(View.INVISIBLE);
        } else {
            createEventButton.setVisibility(View.VISIBLE);
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



                    AlertDialog.Builder builder =
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Select action")
                            .setPositiveButton("View", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent intent = new Intent(MainActivity.this, ListEventStreamsActivity.class);
                                    intent.putExtra(ListEventStreamsActivity.EVENT_ID_KEY, selectedEvent.getEventId());
                                    startActivity(intent);
                                }
                            });

                    //Show broadcast only when allowEventCreation settings is on
                    if(allEventCreation()) {
                        builder .setNegativeButton("Broadcast", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {

                                Intent intent = new Intent(MainActivity.this, StartBroadcastActivity.class);
                                intent.putExtra(EVENT_KEY, gson.toJson(selectedEvent));
                                startActivity(intent);
                            }
                        });
                    }

                    builder.show();

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

                viewHolder.eventText.setText(event.getEventName() + "\ncreated by " + event.getCreator() +
                        "\nat " + new Date(event.getTimestampMs()));

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

    private boolean allEventCreation() {

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean allowEventCreation = sharedPref.getBoolean(getString(R.string.pref_alloweventcreation_key), false);

        Log.i(TAG, "Allow Event Creation is " + allowEventCreation);
        return allowEventCreation;
    }
}
