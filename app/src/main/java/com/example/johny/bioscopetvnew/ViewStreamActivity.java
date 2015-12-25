package com.example.johny.bioscopetvnew;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.VideoView;

import com.example.johny.bioscopetvnew.com.example.johny.biscopetvnew.types.BroadcastEventStream;
import com.google.gson.Gson;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

public class ViewStreamActivity extends AppCompatActivity {
    public static final String STREAM_INFO_KEY = "STREAM_INFO";
    private Gson gson = new Gson();
    private static final String TAG = "ViewStreamActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_stream);

        Intent intent = getIntent();
        BroadcastEventStream eventStream = gson.fromJson(intent.getStringExtra(STREAM_INFO_KEY),
                BroadcastEventStream.class);
        VideoView videoView = (VideoView) findViewById(R.id.videoview_view_stream);
        try {
            videoView.setVideoURI(Uri.parse(URLDecoder.decode(eventStream.getEncodedUrl(), "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        videoView.start();
    }

}
