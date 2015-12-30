package com.example.johny.bioscopetvnew;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.BroadcastListener;
import io.kickflip.sdk.exception.KickflipException;
import io.kickflip.sdk.fragment.BroadcastFragment;

public class StartBroadcastActivity extends AppCompatActivity implements BroadcastListener {

    private static final String TAG = "StartBroadcastActivity";
    private static final String BROADCAST_FRAGMENT_TAG = "BroadcastFragment";
    private long latestUserInteractionTimestampMs;

    private BroadcastFragment mFragment;
    private BroadcastListener mMainBroadcastListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_broadcast);

        mMainBroadcastListener = Kickflip.getBroadcastListener();
        Kickflip.setBroadcastListener(this);

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

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Log.i(TAG, "onBackPressed invoked!");
        stopBroadcast();
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
            Log.i(TAG, "Stopping broadcast..");
        } else {
            Log.i(TAG, "Fragment is null");
        }
    }

    @Override
    public void onBroadcastStart() {
        mMainBroadcastListener.onBroadcastStart();
    }

    @Override
    public void onBroadcastLive(Stream stream) {
        mMainBroadcastListener.onBroadcastLive(stream);
    }

    @Override
    public void onBroadcastStop() {
        finish();
        mMainBroadcastListener.onBroadcastStop();
    }

    @Override
    public void onBroadcastError(KickflipException error) {
        mMainBroadcastListener.onBroadcastError(error);
    }

}
