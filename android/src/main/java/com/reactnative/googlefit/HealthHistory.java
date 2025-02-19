/**
 * Copyright (c) 2017-present, Stanislav Doskalenko - doskalenko.s@gmail.com
 * All rights reserved.
 *
 * This source code is licensed under the MIT-style license found in the
 * LICENSE file in the root directory of this source tree.
 *
 **/

package com.reactnative.googlefit;

import android.os.AsyncTask;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataDeleteRequest;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;
import com.google.android.gms.fitness.data.HealthDataTypes;
import com.google.android.gms.fitness.data.HealthFields;

import java.text.DateFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.android.gms.fitness.data.Field.FIELD_MEAL_TYPE;
import static com.google.android.gms.fitness.data.Field.FIELD_BPM;
import static com.google.android.gms.fitness.data.HealthFields.FIELD_BLOOD_GLUCOSE_LEVEL;
import static com.google.android.gms.fitness.data.HealthFields.FIELD_BLOOD_GLUCOSE_SPECIMEN_SOURCE;
import static com.google.android.gms.fitness.data.HealthFields.FIELD_TEMPORAL_RELATION_TO_MEAL;
import static com.google.android.gms.fitness.data.HealthFields.FIELD_TEMPORAL_RELATION_TO_SLEEP;


public class HealthHistory {

    private ReactContext mReactContext;
    private GoogleFitManager googleFitManager;
    private DataSet Dataset;
    private DataType dataType;

    private static final String TAG = "Health History";

    public HealthHistory(ReactContext reactContext, GoogleFitManager googleFitManager, DataType dataType){
        this.mReactContext = reactContext;
        this.googleFitManager = googleFitManager;
        this.dataType = dataType;
    }

    public HealthHistory(ReactContext reactContext, GoogleFitManager googleFitManager){
        this(reactContext, googleFitManager, DataType.TYPE_HEART_RATE_BPM);
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public ReadableArray getHistory(long startTime, long endTime, int bucketInterval, String bucketUnit) {
        DataReadRequest.Builder readRequestBuilder = new DataReadRequest.Builder()
                .read(this.dataType)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS);
        if (this.dataType == HealthDataTypes.TYPE_BLOOD_PRESSURE) {
            readRequestBuilder.bucketByTime(bucketInterval, HelperUtil.processBucketUnit(bucketUnit));
        }

        DataReadRequest readRequest = readRequestBuilder.build();

        DataReadResult dataReadResult = Fitness.HistoryApi.readData(googleFitManager.getGoogleApiClient(), readRequest).await(1, TimeUnit.MINUTES);

        WritableArray map = Arguments.createArray();

        //Used for aggregated data
        if (dataReadResult.getBuckets().size() > 0) {
            for (Bucket bucket : dataReadResult.getBuckets()) {
                List<DataSet> dataSets = bucket.getDataSets();
                for (DataSet dataSet : dataSets) {
                    processDataSet(dataSet, map);
                }
            }
        }
        //Used for non-aggregated data
        else if (dataReadResult.getDataSets().size() > 0) {
            for (DataSet dataSet : dataReadResult.getDataSets()) {
                processDataSet(dataSet, map);
            }
        }
        return map;
    }

    public boolean saveHeartRateSample(ReadableMap sample) {
        this.Dataset = createHeartRateDataForRequest(
                this.dataType,
                DataSource.TYPE_RAW,
                sample.getDouble("value"),
                (long)sample.getDouble("date"),
                TimeUnit.MILLISECONDS
        );
        new InsertAndVerifyDataTask(this.Dataset).execute();

        return true;
    }

    public boolean saveBloodPressureSample(ReadableMap sample) {
        this.Dataset = createBloodPressureDataForRequest(
                this.dataType,
                DataSource.TYPE_RAW,
                (float)sample.getDouble("systolic"),
                (float)sample.getDouble("diastolic"),
                (long)sample.getDouble("date"),
                TimeUnit.MILLISECONDS
        );
        new InsertAndVerifyDataTask(this.Dataset).execute();

        return true;
    }

    public boolean saveBloodGlucose(ReadableMap sample) {
        this.Dataset = createDataForRequest(
                this.dataType,
                DataSource.TYPE_RAW,
                sample.getDouble("value"),
                (long)sample.getDouble("date"),
                TimeUnit.MILLISECONDS
        );
        new InsertAndVerifyDataTask(this.Dataset).execute();

        return true;
    }

    public boolean delete(ReadableMap sample) {
        long endTime = (long) sample.getDouble("endTime");
        long startTime = (long) sample.getDouble("startTime");
        new DeleteDataHelper(startTime, endTime, this.dataType, googleFitManager).execute();
        return true;
    }

    //Async fit data insert
    private class InsertAndVerifyDataTask extends AsyncTask<Void, Void, Void> {

        private DataSet Dataset;

        InsertAndVerifyDataTask(DataSet dataset) {
            this.Dataset = dataset;
        }

        protected Void doInBackground(Void... params) {
            // Create a new dataset and insertion request.
            DataSet dataSet = this.Dataset;

            // [START insert_dataset]
            // Then, invoke the History API to insert the data and await the result, which is
            // possible here because of the {@link AsyncTask}. Always include a timeout when calling
            // await() to prevent hanging that can occur from the service being shutdown because
            // of low memory or other conditions.
            //Log.i(TAG, "Inserting the dataset in the History API.");
            com.google.android.gms.common.api.Status insertStatus =
                    Fitness.HistoryApi.insertData(googleFitManager.getGoogleApiClient(), dataSet)
                            .await(1, TimeUnit.MINUTES);

            // Before querying the data, check to see if the insertion succeeded.
            if (!insertStatus.isSuccess()) {
                //Log.i(TAG, "There was a problem inserting the dataset.");
                return null;
            }

            //Log.i(TAG, "Data insert was successful!");

            return null;
        }
    }

    /**
     * This method creates a dataset object to be able to insert data in google fit
     * @param dataType DataType Fitness Data Type object
     * @param dataSourceType int Data Source Id. For example, DataSource.TYPE_RAW
     * @param value Object Values for the fitness data. They must be int or float
     * @param date long Time when the activity started
     * @param timeUnit TimeUnit Time unit in which period is expressed
     * @return
     */
    private DataSet createHeartRateDataForRequest(DataType dataType, int dataSourceType, double value,
                                            long date, TimeUnit timeUnit) {
        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(GoogleFitPackage.PACKAGE_NAME)
                .setDataType(dataType)
                .setType(dataSourceType)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint();

        dataPoint.setTimestamp(date, timeUnit);
        dataPoint.getValue(FIELD_BPM).setFloat((float) value);

        dataSet.add(dataPoint);

        return dataSet;
    }

     /**
     * This method creates a dataset object to be able to insert data in google fit
     * @param dataType DataType Fitness Data Type object
     * @param dataSourceType int Data Source Id. For example, DataSource.TYPE_RAW
     * @param systolic Object Values for the fitness data. They must be int or float
     * @param diastolic Object Values for the fitness data. They must be int or float
     * @param date long Time when the activity started
     * @param timeUnit TimeUnit Time unit in which period is expressed
     * @return
     */
    private DataSet createBloodPressureDataForRequest(DataType dataType, int dataSourceType, float systolic,
                                                        float diastolic, long date, TimeUnit timeUnit) {
        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(GoogleFitPackage.PACKAGE_NAME)
                .setDataType(dataType)
                .setType(dataSourceType)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint bloodPressure = DataPoint.create(dataSource);
        
        bloodPressure.setTimestamp(date, timeUnit);
        bloodPressure.getValue(HealthFields.FIELD_BLOOD_PRESSURE_SYSTOLIC).setFloat(systolic);
        bloodPressure.getValue(HealthFields.FIELD_BLOOD_PRESSURE_DIASTOLIC).setFloat(diastolic);

        dataSet.add(bloodPressure);

        return dataSet;
    }

    /**
     * This method creates a dataset object to be able to insert data in google fit
     * @param dataType DataType Fitness Data Type object
     * @param dataSourceType int Data Source Id. For example, DataSource.TYPE_RAW
     * @param value Object Values for the fitness data. They must be int or float
     * @param date long Time when the activity started
     * @param timeUnit TimeUnit Time unit in which period is expressed
     * @return
     */
    private DataSet createDataForRequest(DataType dataType, int dataSourceType, double value,
                                         long date, TimeUnit timeUnit) {
        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(GoogleFitPackage.PACKAGE_NAME)
                .setDataType(dataType)
                .setType(dataSourceType)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint();

        dataPoint.setTimestamp(date, timeUnit);
        dataPoint.getValue(FIELD_BLOOD_GLUCOSE_LEVEL).setFloat((float) value);

        dataSet.add(dataPoint);

        return dataSet;
    }

    private void processDataSet(DataSet dataSet, WritableArray map) {
        Format formatter = new SimpleDateFormat("EEE");

        for (DataPoint dp : dataSet.getDataPoints()) {
            WritableMap stepMap = Arguments.createMap();
            String day = formatter.format(new Date(dp.getStartTime(TimeUnit.MILLISECONDS)));
            int i = 0;

            for(Field field : dp.getDataType().getFields()) {
                i++;
                if (i > 1) continue;
                stepMap.putString("day", day);
                stepMap.putDouble("startDate", dp.getStartTime(TimeUnit.MILLISECONDS));
                stepMap.putDouble("endDate", dp.getEndTime(TimeUnit.MILLISECONDS));
                if (this.dataType == HealthDataTypes.TYPE_BLOOD_PRESSURE) {
                    stepMap.putDouble("diastolic", dp.getValue(HealthFields.FIELD_BLOOD_PRESSURE_DIASTOLIC).asFloat());
                    stepMap.putDouble("systolic", dp.getValue(HealthFields.FIELD_BLOOD_PRESSURE_SYSTOLIC).asFloat());
                } else {
                  stepMap.putDouble("value", dp.getValue(field).asFloat());
                }


                map.pushMap(stepMap);
            }
        }
    }

}
