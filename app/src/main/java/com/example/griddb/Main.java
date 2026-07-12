package com.example.griddb;

import com.toshiba.mwcloud.gs.GridStore;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        GridStore store = GridDbConnection.connect();

        int dashboardPort = Integer.parseInt(envOrDefault("DASHBOARD_PORT", "8080"));
        int analysisWindowHours = Integer.parseInt(envOrDefault("GRIDDB_ANALYSIS_WINDOW_HOURS", "24"));

        List<String> devices = List.of(
                "factory_floor_sensor_01",
                "factory_floor_sensor_02",
                "warehouse_sensor_01"
        );

        // Seed some initial history so the dashboard has data to show immediately.
        SensorIngestor.ingest(store, "factory_floor_sensor_01", 100, 24.0);
        SensorIngestor.ingest(store, "factory_floor_sensor_02", 100, 20.0);
        SensorIngestor.ingest(store, "warehouse_sensor_01", 100, 15.0);

        // Keep ingesting in the background so the dashboard visibly updates over time.
        List<SensorIngestor.IngestionHandle> ingestionHandles = new ArrayList<>();
        ingestionHandles.add(SensorIngestor.startContinuousIngestion(store, "factory_floor_sensor_01", 24.0));
        ingestionHandles.add(SensorIngestor.startContinuousIngestion(store, "factory_floor_sensor_02", 20.0));
        ingestionHandles.add(SensorIngestor.startContinuousIngestion(store, "warehouse_sensor_01", 15.0));

        DashboardServer server = new DashboardServer(store, devices, analysisWindowHours);
        server.start(dashboardPort);

        // Ensure a clean shutdown on Ctrl+C or `docker stop` (SIGTERM), instead of
        // leaving ingestion threads and the GridDB connection to die abruptly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            for (SensorIngestor.IngestionHandle handle : ingestionHandles) {
                handle.stop();
            }
            server.stop(2);
            try {
                store.close();
            } catch (Exception e) {
                System.out.println("Error closing GridDB connection: " + e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
