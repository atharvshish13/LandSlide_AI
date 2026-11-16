package com.geoguard.landslide;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SensorMonitorService extends Service {
    public static final String CHANNEL_ID = "sensor_monitoring_channel";
    private static final String ALERT_CHANNEL_ID = "landslide_alerts_channel";
    public static final String EXTRA_BACKEND_PORT = "backend_port";
    private static final String ACTION_STOP = "com.geoguard.landslide.STOP_MONITOR";
    private static final String RUNTIME_PREFS = "geoguard_runtime";
    private static final String BACKEND_PORT_KEY = "backend_port";
    private static final String LAST_RISK_CODE_KEY = "last_risk_code";
    private static final int MONITOR_NOTIFICATION_ID = 4100;
    private static final int RISK_NOTIFICATION_BASE_ID = 4200;
    private static final long SENSOR_POLL_INTERVAL_MS = 2000L;
    private static final long INITIAL_RETRY_INTERVAL_MS = 15000L;
    private static final long MAX_RETRY_INTERVAL_MS = 60000L;
    private static volatile boolean dashboardVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int backendPort;
    private int lastRiskCode;
    private int failureCount;
    private boolean pollActive;
    private boolean hasPolled;
    private String lastMonitorStatus;

    public static void setDashboardVisible(boolean visible) {
        dashboardVisible = visible;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lastRiskCode = getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).getInt(LAST_RISK_CODE_KEY, 0);
        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification("Waiting for the sensor connection"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) backendPort = intent.getIntExtra(EXTRA_BACKEND_PORT, backendPort);
        if (!pollActive) {
            pollActive = true;
            handler.post(pollRunnable);
        }
        return START_STICKY;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollActive) return;
            worker.execute(() -> {
                boolean connected = false;
                try {
                    ensureBackendStarted();
                    JSONObject sensor = readSensorTelemetry();
                    if (sensor != null) {
                        JSONObject result = postTelemetry(sensor);
                        connected = true;
                        int riskCode = result.optInt("risk_code", 0);
                        if (riskCode < 0 || riskCode > 3) riskCode = 0;
                        handleRiskTransition(riskCode, result.optString("risk_level", riskName(riskCode)), result);
                        publishMonitorStatus("ESP32 connected • " + riskName(riskCode));
                    }
                } catch (Exception error) {
                    if (!hasPolled || System.currentTimeMillis() % 60000L < SENSOR_POLL_INTERVAL_MS) {
                        Log.i("GeoGuard", "Background sensor poll failed; waiting for ESP32 Wi-Fi.", error);
                    }
                } finally {
                    hasPolled = true;
                    if (!connected) publishMonitorStatus("Waiting for ESP32 Wi-Fi at 192.168.4.1");
                    long retryDelay = connected
                            ? SENSOR_POLL_INTERVAL_MS
                            : Math.min(MAX_RETRY_INTERVAL_MS, INITIAL_RETRY_INTERVAL_MS * (1L << Math.min(failureCount++, 2)));
                    if (connected) failureCount = 0;
                    handler.postDelayed(this, retryDelay);
                }
            });
        }
    };

    private void ensureBackendStarted() {
        if (backendPort > 0) return;
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            PyObject runtime = Python.getInstance().getModule("backend.android_runtime");
            String settings = MainActivity.readProviderSettings(this);
            if (!settings.isEmpty()) runtime.callAttr("set_provider_config", settings);
            backendPort = runtime.callAttr("start_server", getFilesDir().getAbsolutePath(), "127.0.0.1", 0).toInt();
            getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putInt(BACKEND_PORT_KEY, backendPort).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Embedded risk API could not start.", error);
        }
    }

    @Nullable
    private JSONObject readSensorTelemetry() throws Exception {
        HttpURLConnection connection = openConnection("http://192.168.4.1/api/telemetry", "GET");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            JSONObject result = new JSONObject(readFully(connection.getInputStream()));
            if (!result.has("soil_moisture_1") || !result.has("rain_intensity")) return null;
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject postTelemetry(JSONObject telemetry) throws Exception {
        HttpURLConnection connection = openConnection(
                "http://127.0.0.1:" + backendPort + "/api/telemetry", "POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] request = telemetry.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(request);
        }
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readFully(stream);
            if (status < 200 || status >= 300) throw new IllegalStateException("Local risk API returned HTTP " + status);
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String address, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(900);
        connection.setReadTimeout(900);
        connection.setUseCaches(false);
        return connection;
    }

    private String readFully(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = stream.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void handleRiskTransition(int riskCode, String riskLevel, JSONObject telemetry) {
        boolean escalated = riskCode > lastRiskCode;
        if (escalated && !dashboardVisible) {
            String title = riskCode >= 3 ? "CRITICAL LANDSLIDE DANGER"
                    : riskCode == 2 ? "High landslide probability" : "Landslide risk advisory";
            String message = riskCode >= 3 ? "Critical slope movement detected. Evacuate immediately to a safe area."
                    : riskCode == 2 ? "Slope displacement is active. Prepare to evacuate."
                    : "Elevated slope risk detected. Monitor conditions and follow local safety guidance.";
            message += " " + riskLevel + " • score " + telemetry.optDouble("risk_score", 0.0) + "%";
            showRiskNotification(title, message, riskCode);
        }
        if (riskCode != lastRiskCode) {
            lastRiskCode = riskCode;
            getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putInt(LAST_RISK_CODE_KEY, riskCode).apply();
        }
    }

    private void showRiskNotification(String title, String message, int riskCode) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, riskCode, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(riskCode >= 3 ? android.R.drawable.ic_dialog_alert : android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();
        manager.notify(RISK_NOTIFICATION_BASE_ID + riskCode, notification);
    }

    private Notification buildMonitorNotification(String status) {
        Intent stopIntent = new Intent(this, SensorMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Drishti Setu monitoring active")
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop monitoring", stopPendingIntent)
                .build();
    }

    private void publishMonitorStatus(String status) {
        if (status.equals(lastMonitorStatus)) return;
        lastMonitorStatus = status;
        handler.post(() -> {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(status));
        });
    }

    private String riskName(int code) {
        switch (code) {
            case 1: return "MEDIUM";
            case 2: return "HIGH";
            case 3: return "CRITICAL";
            default: return "LOW";
        }
    }

    @Override
    public void onDestroy() {
        pollActive = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
