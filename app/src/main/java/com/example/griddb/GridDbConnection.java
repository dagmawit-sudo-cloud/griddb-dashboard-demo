package com.example.griddb;

import com.toshiba.mwcloud.gs.GSException;
import com.toshiba.mwcloud.gs.GridStore;
import com.toshiba.mwcloud.gs.GridStoreFactory;

import java.util.Properties;

public class GridDbConnection {

    public static GridStore connect() throws GSException, InterruptedException {
        String notificationMember = envOrDefault("GRIDDB_NOTIFICATION_MEMBER", "127.0.0.1:10001");
        String clusterName = envOrDefault("GRIDDB_CLUSTER_NAME", "dockerGridDB");
        String user = envOrDefault("GRIDDB_USER", "admin");
        String password = envOrDefault("GRIDDB_PASSWORD", "admin");

        Properties props = new Properties();
        props.setProperty("notificationMember", notificationMember);
        props.setProperty("clusterName", clusterName);
        props.setProperty("user", user);
        props.setProperty("password", password);

        int maxAttempts = 20;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                GridStore store = GridStoreFactory.getInstance().getGridStore(props);
                store.getContainerInfo("connection_check_container");
                System.out.println("Connected to GridDB at " + notificationMember);
                return store;
            } catch (GSException e) {
                System.out.println("Attempt " + attempt + "/" + maxAttempts +
                        ": GridDB not ready yet (" + e.getMessage() + "), retrying in 5s...");
                Thread.sleep(5000);
            }
        }
        throw new GSException("Could not connect to GridDB after " + maxAttempts + " attempts");
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
