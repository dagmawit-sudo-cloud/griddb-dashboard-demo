package com.example.griddb;

import com.toshiba.mwcloud.gs.GSException;
import com.toshiba.mwcloud.gs.GridStore;
import com.toshiba.mwcloud.gs.TimeSeries;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class SensorIngestor {

    private static final double ALERT_THRESHOLD =
            Double.parseDouble(envOrDefault("GRIDDB_ALERT_THRESHOLD", "27.5"));

    /** One-shot batch ingestion, e.g. to seed initial history. */
    public static void ingest(GridStore store, String deviceId, int readingCount, double baseTemp) throws GSException {
        TimeSeries<SensorReading> ts = store.putTimeSeries(deviceId, SensorReading.class);
        Random random = new Random();

        for (int i = 0; i < readingCount; i++) {
            ts.append(buildReading(deviceId, baseTemp, random));
        }
        System.out.println("Ingested " + readingCount + " readings for " + deviceId +
                " (base temp " + baseTemp + "\u00B0C, alert threshold " + ALERT_THRESHOLD + "\u00B0C)");
    }

    /**
     * Starts a background thread that keeps appending a new reading every couple
     * of seconds, so the dashboard has live-changing data to poll instead of a
     * static snapshot. Call stop() on the returned handle for a clean shutdown.
     */
    public static IngestionHandle startContinuousIngestion(GridStore store, String deviceId, double baseTemp) {
        AtomicBoolean running = new AtomicBoolean(true);

        Thread thread = new Thread(() -> {
            Random random = new Random();
            try {
                TimeSeries<SensorReading> ts = store.putTimeSeries(deviceId, SensorReading.class);
                while (running.get()) {
                    ts.append(buildReading(deviceId, baseTemp, random));
                    Thread.sleep(3000);
                }
            } catch (GSException e) {
                System.out.println("Ingestion error for " + deviceId + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Ingestion stopped for " + deviceId);
        });
        thread.setDaemon(true);
        thread.setName("ingest-" + deviceId);
        thread.start();

        return new IngestionHandle(thread, running);
    }

    private static SensorReading buildReading(String deviceId, double baseTemp, Random random) {
        SensorReading reading = new SensorReading();
        reading.deviceId = deviceId;
        reading.temperature = baseTemp + random.nextDouble() * 8;
        reading.humidity = 40 + random.nextDouble() * 20;
        reading.alertTriggered = reading.temperature > ALERT_THRESHOLD;
        return reading;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /** Handle for a background ingestion thread, allowing a graceful stop request. */
    public static class IngestionHandle {
        private final Thread thread;
        private final AtomicBoolean running;

        IngestionHandle(Thread thread, AtomicBoolean running) {
            this.thread = thread;
            this.running = running;
        }

        public void stop() {
            running.set(false);
            thread.interrupt();
        }
    }
}
