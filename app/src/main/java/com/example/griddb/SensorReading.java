package com.example.griddb;

import com.toshiba.mwcloud.gs.RowKey;

import java.util.Date;

public class SensorReading {
    @RowKey Date timestamp;
    String deviceId;
    double temperature;
    double humidity;
    boolean alertTriggered;
}
