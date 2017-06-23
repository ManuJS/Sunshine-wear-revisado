/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String WEATHER_PATH = "/update-weather";

    private static final String LOG_TAG = SunshineWatchFaceService.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {

        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }


        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        // handler to update the time once a second in interactive mode
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;
        boolean isRoundWatchface = false;

        Paint mBackgroundPaint;

        Paint mTimePaint;
        Paint mDatePaint;
        Paint mMinTempPaint;
        Paint mMaxTempPaint;
        Paint mWeatherIconPaint;

        boolean mAmbient;
        Calendar mCalendar;

        // receiver to update the time zone
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        String maxTempText = getString(R.string.default_max_temp);
        String minTempText = getString(R.string.default_min_temp);
        long weatherID;
        Bitmap mWeatherIconBitmap;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            // Load the background image
            Resources resources = SunshineWatchFaceService.this.getResources();

            // Initialize the background paint
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this,
                    R.color.primary));

            // Initialize date text and time text paint for hour, colon, and minutes
            mDatePaint = createTextPaint(ContextCompat.getColor(SunshineWatchFaceService.this,
                    R.color.primary_text));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mTimePaint = createTextPaint(ContextCompat.getColor(SunshineWatchFaceService.this,
                    R.color.primary_text));
            mTimePaint.setTextSize(resources.getDimension(R.dimen.time_text_size));

//             Initialize temperature text paint for max and min temps
            mMinTempPaint = createTextPaint(ContextCompat.getColor(SunshineWatchFaceService.this,
                    R.color.primary_text));
            mMinTempPaint.setTextSize(resources.getDimension(R.dimen.min_temp_text_size));
            mMaxTempPaint = createTextPaint(ContextCompat.getColor(SunshineWatchFaceService.this,
                    R.color.primary_text));
            mMaxTempPaint.setTextSize(resources.getDimension(R.dimen.max_temp_text_size));

            // Allocate a Calendar to calculate local time using the UTC time and time zone
            mCalendar = Calendar.getInstance();

            mWeatherIconPaint = new Paint();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }

            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }

            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
                    false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;

                if (mLowBitAmbient) {
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }
        Paint mLine = new Paint();
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {


            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }



            // Draw HH:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            float margin = getResources().getDimension(R.dimen.margin);
            float centerXOfWatchface = bounds.centerX();
            float centerYOfWatchface = bounds.centerY();
            float startLine = bounds.left;
            float endLine = bounds.right;




            String timeText = mAmbient ?
                    String.format("%02d:%02d",
                            mCalendar.get(Calendar.HOUR_OF_DAY),
                            mCalendar.get(Calendar.MINUTE)) :
                    String.format("%02d:%02d:%02d",
                            mCalendar.get(Calendar.HOUR_OF_DAY),
                            mCalendar.get(Calendar.MINUTE),
                            mCalendar.get(Calendar.SECOND));

            float timeTextWidth = mTimePaint.measureText(timeText);

            canvas.drawText(timeText, centerXOfWatchface - (timeTextWidth / 2), centerYOfWatchface - 2 * margin, mTimePaint);

            mLine.setColor(getResources().getColor(R.color.primary_text));

            canvas.drawLine(startLine, centerYOfWatchface + 20, endLine, centerYOfWatchface + 20, mLine);
            // Display date only if not in ambient mode
            if (!mAmbient) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d");
                String dateText = dateFormat.format(new Date(now));
                float dateTextWidth = mDatePaint.measureText(dateText);
                canvas.drawText(dateText, centerXOfWatchface - dateTextWidth / 2,
                        centerYOfWatchface - (margin / 2), mDatePaint);

            }

            if (!mAmbient) {//where the max and min temperature are displaying
                canvas.drawText(maxTempText, centerXOfWatchface - margin,
                        40 + centerYOfWatchface + margin, mMaxTempPaint);
                canvas.drawText(minTempText, centerXOfWatchface + margin,
                        40 + centerYOfWatchface + margin, mMinTempPaint);
            } else {
                canvas.drawText(maxTempText, centerXOfWatchface - margin,
                        40 + centerYOfWatchface + margin, mMaxTempPaint);
                canvas.drawText(minTempText, centerXOfWatchface + margin,
                        40 + centerYOfWatchface + margin, mMinTempPaint);
            }

            if (!mAmbient && mWeatherIconBitmap != null) {
                canvas.drawBitmap(mWeatherIconBitmap,
                        centerXOfWatchface - mWeatherIconBitmap.getWidth() - (2 * margin),
                        40 + centerYOfWatchface, mWeatherIconPaint);
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED
                        && dataEvent.getDataItem().getUri().getPath().equals(WEATHER_PATH)) {
                    extractInfoFromDataItem(dataEvent.getDataItem());
                }
            }
        }

        private void extractInfoFromDataItem(DataItem dataItem) {
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
            maxTempText = dataMapItem.getDataMap().getString(getString(R.string.max_temp_key));
            minTempText = dataMapItem.getDataMap().getString(getString(R.string.min_temp_key));
            weatherID = dataMapItem.getDataMap().getLong(getString(R.string.weather_id_key));
            getBitmapFromDrawable(weatherID);
        }


        private void getBitmapFromDrawable(long weatherID) {
            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(),
                    getWeatherIconForWeatherCondition(weatherID));

            invalidate();
        }

        private int getWeatherIconForWeatherCondition(long weatherId) {

        /*
         * Based on weather code data for Open Weather Map.
         */
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 771 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            } else if (weatherId >= 900 && weatherId <= 906) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 958 && weatherId <= 962) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 951 && weatherId <= 957) {
                return R.drawable.ic_clear;
            }

            Log.e(LOG_TAG, getString(R.string.unknown_weather) + weatherId);
            return R.drawable.ic_default;
        }

    }
}