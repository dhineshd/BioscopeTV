package com.example.johny.bioscopetvnew;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.bioscope.tv.backend.bioscopeBroadcastService.BioscopeBroadcastService;
import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEvent;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;

import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.BroadcastListener;
import io.kickflip.sdk.av.SessionConfig;
import io.kickflip.sdk.exception.KickflipException;
import io.kickflip.sdk.fragment.BroadcastFragment;

public class StartBroadcastActivity extends AppCompatActivity implements BroadcastListener {

    private static final String TAG = "StartBroadcastActivity";
    private static final String BROADCAST_FRAGMENT_TAG = "BroadcastFragment";

    private long latestUserInteractionTimestampMs;

    private BroadcastFragment mFragment;
    private BioscopeBroadcastService serviceClient;
    private BroadcastEvent event;
    private Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_broadcast);

        initializeClient();

        Intent intent = getIntent();
        event = gson.fromJson(intent.getStringExtra(MainActivity.EVENT_KEY), BroadcastEvent.class);

        setupBroadcast(event);

        if (savedInstanceState == null) {
            mFragment = BroadcastFragment.getInstance();
            getFragmentManager().beginTransaction()
                    .replace(R.id.broadcast_container, mFragment, BROADCAST_FRAGMENT_TAG)
                    .commit();
        }

        Log.i(TAG, "Fragment = " + mFragment);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
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
    }

    private void stopBroadcast() {
        if (mFragment != null) {
            mFragment.stopBroadcasting();
            Log.i(TAG, "STOP_BROADCAST : Stopping broadcast..");
        } else {
            Log.i(TAG, "STOP_BROADCAST : Fragment is null");
        }
        finish();
    }

    @Override
    public void onBroadcastStart() {
        Log.i(TAG, "Broadcast started!");
    }

    @Override
    public void onBroadcastLive(Stream stream) {
        Log.i(TAG, "BroadcastLive @ " + stream.getKickflipUrl());
        Log.i(TAG, "Stream URL :" + stream.getStreamUrl());
        new CreateEventStreamTask().execute(event.getEventId(), stream.getStreamUrl());
    }

    @Override
    public void onBroadcastStop() {
        Log.i(TAG, "Broadcast stopped!");
        finish();
    }

    @Override
    public void onBroadcastError(KickflipException error) {
        Log.i(TAG, "Broadcast error = " + error.getMessage());
    }

    private void setupBroadcast(final BroadcastEvent event) {
        String outputLocation = new File(getApplicationContext().getFilesDir(), "index.m3u8").getAbsolutePath();
        Kickflip.setSessionConfig(new SessionConfig.Builder(outputLocation)
                .withVideoBitrate(300 * 1000)
                        //.withAudioBitrate()
                .withPrivateVisibility(false)
                .withLocation(false)
                .withTitle(event.getEventName())
                .withVideoResolution(640, 360)
                .withAdaptiveStreaming(true)
                .build());
        Kickflip.setBroadcastListener(this);
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

}
