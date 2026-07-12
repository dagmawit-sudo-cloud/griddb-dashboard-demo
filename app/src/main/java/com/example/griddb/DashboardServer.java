package com.example.griddb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.toshiba.mwcloud.gs.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public class DashboardServer {

    private static final int MAX_ALERTS_RETURNED = 200;

    private final GridStore store;
    private final List<String> deviceIds;
    private final int analysisWindowHours;
    private HttpServer server;

    public DashboardServer(GridStore store, List<String> deviceIds, int analysisWindowHours) {
        this.store = store;
        this.deviceIds = deviceIds;
        this.analysisWindowHours = analysisWindowHours;
    }

    public void start(int port) throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/health", exchange ->
                sendJson(exchange, "{\"status\":\"ok\",\"time\":\"" + Instant.now() + "\"}"));

        server.createContext("/api/stats", exchange -> {
            try {
                sendJson(exchange, buildStatsJson());
            } catch (Exception e) {
                log("Error building /api/stats response: " + e.getMessage());
                sendError(exchange, e);
            }
        });

        server.createContext("/api/alerts", exchange -> {
            try {
                sendJson(exchange, buildAlertsJson());
            } catch (Exception e) {
                log("Error building /api/alerts response: " + e.getMessage());
                sendError(exchange, e);
            }
        });

        server.createContext("/", exchange -> {
            try {
                byte[] html = DashboardServer.class
                        .getResourceAsStream("/dashboard.html")
                        .readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(html);
                }
            } catch (Exception e) {
                log("Error serving dashboard.html: " + e.getMessage());
                sendError(exchange, e);
            }
        });

        server.setExecutor(null);
        server.start();
        log("Dashboard server running at http://localhost:" + port);
    }

    /** Stops the HTTP server, allowing in-flight requests up to `delaySeconds` to complete. */
    public void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
            log("Dashboard server stopped.");
        }
    }

    private String buildStatsJson() throws GSException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < deviceIds.size(); i++) {
            String deviceId = deviceIds.get(i);
            TimeSeries<SensorReading> ts = store.putTimeSeries(deviceId, SensorReading.class);
            Date now = TimestampUtils.current();
            Date windowStart = TimestampUtils.add(now, -analysisWindowHours, TimeUnit.HOUR);

            AggregationResult avgResult = ts.aggregate(windowStart, now, "temperature", Aggregation.AVERAGE);
            AggregationResult minResult = ts.aggregate(windowStart, now, "temperature", Aggregation.MINIMUM);
            AggregationResult maxResult = ts.aggregate(windowStart, now, "temperature", Aggregation.MAXIMUM);
            AggregationResult countResult = ts.aggregate(windowStart, now, "temperature", Aggregation.COUNT);

            // Aggregation results can be null if the container has no rows yet in this window.
            double avg = avgResult != null ? avgResult.getDouble() : 0.0;
            double min = minResult != null ? minResult.getDouble() : 0.0;
            double max = maxResult != null ? maxResult.getDouble() : 0.0;
            long count = countResult != null ? countResult.getDouble().longValue() : 0L;

            sb.append(String.format(
                    "{\"deviceId\":\"%s\",\"avg\":%.2f,\"min\":%.2f,\"max\":%.2f,\"count\":%d}",
                    escapeJson(deviceId), avg, min, max, count));
            if (i < deviceIds.size() - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private String buildAlertsJson() throws GSException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String deviceId : deviceIds) {
            TimeSeries<SensorReading> ts = store.putTimeSeries(deviceId, SensorReading.class);
            // LIMIT keeps this bounded as the container grows over a long-running demo,
            // instead of pulling every alert ever recorded on every poll.
            String tql = "SELECT * WHERE alertTriggered ORDER BY timestamp DESC LIMIT " + MAX_ALERTS_RETURNED;
            Query<SensorReading> query = ts.query(tql, SensorReading.class);
            RowSet<SensorReading> rows = query.fetch();
            while (rows.hasNext()) {
                SensorReading r = rows.next();
                if (!first) sb.append(",");
                sb.append(String.format(
                        "{\"deviceId\":\"%s\",\"timestamp\":\"%s\",\"temperature\":%.2f}",
                        escapeJson(r.deviceId), TimestampUtils.format(r.timestamp), r.temperature));
                first = false;
            }
        }
        return sb.append("]").toString();
    }

    private void sendJson(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, Exception e) throws java.io.IOException {
        String safeMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String message = "{\"error\":\"" + escapeJson(safeMessage) + "\"}";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(500, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Minimal JSON string escaping - handles the characters that would otherwise break the payload. */
    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void log(String message) {
        System.out.println("[" + Instant.now() + "] " + message);
    }
}
