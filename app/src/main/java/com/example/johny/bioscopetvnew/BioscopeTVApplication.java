package com.example.johny.bioscopetvnew;

import android.app.Application;

import com.example.johny.bioscopetvnew.metrics.MetricsHelper;

import java.util.UUID;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Created by rohitraghunathan on 1/9/16.
 */

@Slf4j
public class BioscopeTVApplication extends Application {

    public static final String KONOTOR_APP_ID = "4a550f3b-6391-4854-8f36-8aacaa2928c2";

    public static final String KONOTOR_APP_KEY = "76bad5cf-d107-4ad6-8f82-7f153b556b87";

    @Getter
    private static MetricsHelper metrics;

    @Getter
    private String userId;

    @Override
    public void onCreate() {
        super.onCreate();
        log.info("Starting application");

        metrics = new MetricsHelper(this);

        userId = UUID.randomUUID().toString();
    }
}
