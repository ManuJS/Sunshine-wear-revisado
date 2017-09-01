package com.example.android.sunshine.app.watchface_sync;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;


public class WatchfaceSyncService implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String LOG_TAG = WatchfaceSyncService.class.getSimpleName();

    private static final String WEATHER_PATH = "/update-weather";

    private static final String[] WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    static WatchfaceSyncService watchfaceSyncService;
    static GoogleApiClient mGoogleApiClient;
    static Context mContext;


    private WatchfaceSyncService(Context context) {

        mContext = context;

        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        mGoogleApiClient.connect();
    }

    public static WatchfaceSyncService getInstance(Context context) {
        if (watchfaceSyncService == null) {
            watchfaceSyncService = new WatchfaceSyncService(context);
        }
        return watchfaceSyncService;
    }

    public void updateWatchface() {

        Uri weatherUri = WeatherContract.WeatherEntry.CONTENT_URI;

        Cursor cursor = mContext.getContentResolver()
                .query(weatherUri, WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {

            int weatherId = cursor.getInt(INDEX_WEATHER_ID);

            double max = cursor.getDouble(INDEX_MAX_TEMP);
            int maxTemp = (int) Math.round(max);

            double min = cursor.getDouble(INDEX_MIN_TEMP);
            int minTemp = (int) Math.round(min);

            PutDataMapRequest mapRequest = PutDataMapRequest.create(WEATHER_PATH).setUrgent();
            mapRequest.getDataMap().putString("max_temp", maxTemp + "°");
            mapRequest.getDataMap().putString("min_temp", minTemp + "°");
            mapRequest.getDataMap().putLong("weather_id", weatherId);
            mapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());

            PutDataRequest request = mapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallbacks<DataApi.DataItemResult>() {
                @Override
                public void onSuccess(DataApi.DataItemResult dataItemResult) {

                }

                @Override
                public void onFailure(Status status) {

                }
            });
        }

    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}
