package com.example.johny.bioscopetvnew;

import android.app.Application;

import com.example.johny.bioscopetvnew.metrics.MetricsHelper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Created by rohitraghunathan on 1/9/16.
 */

@Slf4j
public class BioscopeTVApplication extends Application {

    @Getter
    private static MetricsHelper metrics;

    @Override
    public void onCreate() {
        super.onCreate();
        log.info("Starting application");

        metrics = new MetricsHelper(this);
    }
}
