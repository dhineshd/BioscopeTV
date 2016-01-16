package com.example.johny.bioscopetvnew;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEvent;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.BroadcastListener;
import io.kickflip.sdk.av.SessionConfig;
import io.kickflip.sdk.exception.KickflipException;
import io.kickflip.sdk.fragment.BroadcastFragment;

public class StartBroadcastActivity extends AppCompatActivity implements BroadcastListener {

    private static final String TAG = "StartBroadcastActivity";
    private static final String BROADCAST_FRAGMENT_TAG = "BroadcastFragment";
    private static final String STREAM_ID_KEY = "STREAM_ID";
    private static final String SHOULD_START_BROADCAST_ON_ACTIVITY_START = "SHOULD_START_BROADCAST_ON_START";
    private static final long UPDATE_STREAM_STATUS_INTERVAL_MS = 5000;

    // If there is no change in output directory for this timeout period, we
    // will declare the broadcast as dead and restart.
    private static final long STREAM_IDLE_TIMEOUT_MS = 20000;

    private long latestUserInteractionTimestampMs;

    private BroadcastFragment mFragment;
    private BioscopeBroadcastService serviceClient;
    private BroadcastEvent event;
    private Gson gson = new Gson();
    private String streamId;
    private String streamName;
    private Handler streamStatusUpdateHandler;
    private Runnable streamStatusUpdateRunnable;
    private String streamLocalOutputLocation;
    private boolean deadStreamDetected;
    private boolean shouldStartBroadcastOnActivityStart;
    private boolean activityJustCreated = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_broadcast);

        initializeClient();

        Intent intent = getIntent();
        event = gson.fromJson(intent.getStringExtra(MainActivity.EVENT_KEY), BroadcastEvent.class);
        streamName = intent.getStringExtra(MainActivity.STREAM_NAME_KEY);
        shouldStartBroadcastOnActivityStart = intent.getBooleanExtra(SHOULD_START_BROADCAST_ON_ACTIVITY_START, false);

        setTitle(streamName);

        setupBroadcast(event);

        if (savedInstanceState == null) {
            mFragment = BroadcastFragment.getInstance();
            getFragmentManager().beginTransaction()
                    .replace(R.id.broadcast_container, mFragment, BROADCAST_FRAGMENT_TAG)
                    .commit();
        }

        Log.i(TAG, "Fragment = " + mFragment);

        streamStatusUpdateHandler = new Handler();
        streamStatusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                Date lastModifiedTimeOfOutputDir = null;
                if (streamLocalOutputLocation != null) {
                    File file = new File(streamLocalOutputLocation);
                    // Watch parent dir of output file
                    if (file.getParentFile().isDirectory()) {

                        File outputDir = file.getParentFile();

                        lastModifiedTimeOfOutputDir = getLastModified(outputDir);

                        Log.i(TAG, "Last modified time = " + getLastModified(outputDir));

                        File [] subDirs = file.getParentFile().listFiles(new FileFilter() {

                            @Override
                            public boolean accept(File pathname) {
                                return pathname.isDirectory();
                            }
                        });
                        for (File dir : subDirs) {
                            File[] filesInDir = dir.listFiles(new FileFilter() {
                                @Override
                                public boolean accept(File pathname) {
                                    //Log.i(TAG, "file = " + pathname.getName());
                                    return true;
                                }
                            });
                            //Log.i(TAG, "For dir = " + dir + ", children count = " +  (filesInDir == null? 0 : filesInDir.length));
                        }
                    }
                }

                if (lastModifiedTimeOfOutputDir != null &&
                        (System.currentTimeMillis() - lastModifiedTimeOfOutputDir.getTime() > STREAM_IDLE_TIMEOUT_MS)) {
                    // We have not seen any new data written to output dir.
                    // Stop and re-start the broadcast
                    //stopBroadcast();
                    //setupBroadcast(event);

                    Log.i(TAG, "Stream is dead! We should restart broadcast..");

                    deadStreamDetected = true;

                    stopBroadcast();


                    //try { Thread.sleep(1000); } catch (Exception e) { }

                    //start recording
                    //recordButton.performClick();

                } else {
                    // Notify server that stream is healthy and live
                    new UpdateEventStreamStatusTask().execute(streamId, String.valueOf(true));
                    streamStatusUpdateHandler.postDelayed(this, UPDATE_STREAM_STATUS_INTERVAL_MS);
                }
            }
        };

        activityJustCreated = true;
    }

    public static Date getLastModified(File directory) {
        File[] files = directory.listFiles();
        if (files.length == 0) return new Date(directory.lastModified());
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File o1, File o2) {
                return new Long(o2.lastModified()).compareTo(o1.lastModified()); //latest 1st
            }
        });
        return new Date(files[0].lastModified());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (streamId != null) {
            outState.putString(STREAM_ID_KEY, streamId);
        }
        Log.i(TAG, "onSaveInstanceState!");
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState.getString(STREAM_ID_KEY) != null) {
            streamId = savedInstanceState.getString(STREAM_ID_KEY);
        }
        Log.i(TAG, "onRestoreInstanceState!");
    }

    boolean doubleBackToExitPressedOnce = false;

    @Override
    public void onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed();
            Log.i(TAG, "onBackPressed invoked!");
            stopBroadcast();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();

        new Handler().postDelayed(new Runnable() {

            @Override
            public void run() {
                doubleBackToExitPressedOnce = false;
            }
        }, 2000);
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
            Log.i(TAG, "Detected that user is leaving");
            stopBroadcast();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause invoked!");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFragment = (BroadcastFragment) getFragmentManager().findFragmentByTag(BROADCAST_FRAGMENT_TAG);

        if (activityJustCreated) {
            activityJustCreated = false;

            if (shouldStartBroadcastOnActivityStart) {
                // Press the record button to start recording right away
                Button recordButton = (Button) findViewById(io.kickflip.sdk.R.id.recordButton);
                recordButton.performClick();
            }
        }
    }

    class UpdateEventStreamStatusTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String streamId = params[0];
            boolean isLive = Boolean.valueOf(params[1]);

            try {
                if (streamId != null) {
                    serviceClient.updateEventStream(streamId, isLive).execute();
                    Log.i(TAG, "Successfully updated event stream live status to " + isLive);
                }

                // TODO : Handle invalid streamId case which should throw server exception
            } catch (Exception e) {
                Log.e(TAG, "Failed to update event stream for streamId = " + streamId);
            }
            return null;
        }
    }

    @Override
    public void onBroadcastStart() {
        Log.i(TAG, "Broadcast started!");
        deadStreamDetected = false;
    }

    @Override
    public void onBroadcastLive(Stream stream) {
        Log.i(TAG, "BroadcastLive @ " + stream.getKickflipUrl());
        Log.i(TAG, "Stream URL :" + stream.getStreamUrl());
        if (isFinishing()) {
            Log.i(TAG, "Not creating stream since we are finishing..");
        } else {
            new CreateEventStreamTask().execute(event.getEventId(), streamName, stream.getStreamUrl());
        }
    }

    @Override
    public void onBroadcastStop() {
        Log.i(TAG, "Broadcast stopped!");
        streamStatusUpdateHandler.removeCallbacks(streamStatusUpdateRunnable);
        new UpdateEventStreamStatusTask().execute(streamId, String.valueOf(false));
        finish();
        if (deadStreamDetected) {
            Intent intent = getIntent();
            intent.putExtra(SHOULD_START_BROADCAST_ON_ACTIVITY_START, true);
            startActivity(intent);
        }
    }

    @Override
    public void onBroadcastError(KickflipException error) {
        Log.i(TAG, "Broadcast error = " + error.getMessage());
    }

    private void setupBroadcast(final BroadcastEvent event) {
        streamLocalOutputLocation = new File(getApplicationContext().getFilesDir(), "index.m3u8").getAbsolutePath();
        Log.i(TAG, "Output location = " + streamLocalOutputLocation);
        Kickflip.setSessionConfig(new SessionConfig.Builder(streamLocalOutputLocation)
                .withVideoBitrate(300 * 1000)
                .withPrivateVisibility(false)
                .withLocation(false)
                .withTitle(event.getEventName())
                .withVideoResolution(640, 360)
                .withAdaptiveStreaming(true)
                .build());
        Kickflip.setBroadcastListener(this);
    }

    private void stopBroadcast() {
        if (mFragment != null) {
            mFragment.stopBroadcasting();
            Log.i(TAG, "STOP_BROADCAST : Stopping broadcast..");
        } else {
            Log.i(TAG, "STOP_BROADCAST : Fragment is null");
        }
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

    class CreateEventStreamTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            try {
                String eventId = params[0];
                String streamName = params[1];
                String url = URLEncoder.encode(params[2], "UTF-8");
                return serviceClient.createEventStream(eventId, streamName,
                        url, Build.MODEL).execute().getData();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create eventStream", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            streamId = result;
            Log.i(TAG, "Created event stream : streamId = " + streamId);

            streamStatusUpdateHandler.post(streamStatusUpdateRunnable);
        }
    }

}
