package com.geoguard.landslide;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends AppCompatActivity implements NativeDashboardView.Actions {
    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int SOS_SMS_PERMISSION_REQUEST_CODE = 2002;
    private static final int VOICE_PERMISSION_REQUEST_CODE = 2003;
    private static final String ALERT_CHANNEL_ID = "landslide_alerts_channel";
    private static final String SETTINGS_PREFS = "geoguard_secure_settings";
    private static final String SETTINGS_KEY = "provider_settings";
    private static final String MEDICAL_SETTINGS_KEY = "medical_profile";
    private static final String KEYSTORE_ALIAS = "GeoGuardProviderSettings";
    private static final String RUNTIME_PREFS = "geoguard_runtime";
    private static final String BACKEND_PORT_KEY = "backend_port";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final AtomicBoolean backendStartupActive = new AtomicBoolean(false);
    private final AtomicBoolean refreshActive = new AtomicBoolean(false);
    private NativeDashboardView dashboard;
    private ActivityResultLauncher<String> pdfExportLauncher;
    private ActivityResultLauncher<String> csvExportLauncher;
    private ActivityResultLauncher<Intent> voiceInputLauncher;
    private String pendingReportText = "";
    private String pendingCsvText = "";
    private volatile String backendUrl = "";
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastPhoneLocation;
    private boolean locationUpdatesActive;
    private long lastHistoryRefresh;
    private boolean sosSending;
    private boolean whatsappSosComplete;
    private boolean smsPermissionPending;
    private String pendingSosRecipient = "";
    private String pendingSosMessage = "";
    private String pendingSosMedicalContext = "";
    private String pendingSosRisk = "";
    private String pendingSosProviderSettings = "";
    private double pendingSosLatitude = Double.NaN;
    private double pendingSosLongitude = Double.NaN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(185, 225, 246));
        getWindow().setNavigationBarColor(Color.rgb(255, 245, 233));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUiVisibility |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(systemUiVisibility);
        }
        pdfExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> { if (uri != null) writeReportPdf(uri, pendingReportText); });
        csvExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) writeCsv(uri, pendingCsvText); });
        voiceInputLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    ArrayList<String> matches = result.getData()
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    if (matches != null && !matches.isEmpty()) dashboard.setVoiceInput(matches.get(0));
                });
        dashboard = new NativeDashboardView(this, this);
        refreshMedicalSummary();
        setContentView(dashboard);
        createNotificationChannel();
        startEmbeddedBackend();
        requestRequiredPermissions();
    }

    private void startEmbeddedBackend() {
        if (!backendStartupActive.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                PyObject runtime = Python.getInstance().getModule("backend.android_runtime");
                String settings = readProviderSettings(this);
                if (!settings.isEmpty()) runtime.callAttr("set_provider_config", settings);
                runtime.callAttr("warm_model");
                int port = runtime.callAttr("start_server", getFilesDir().getAbsolutePath(), "127.0.0.1", 0).toInt();
                getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putInt(BACKEND_PORT_KEY, port).apply();
                backendUrl = "http://127.0.0.1:" + port;
                runOnUiThread(() -> {
                    startSensorMonitorService(port);
                    dashboard.setTelemetry(new JSONObject(), false, false, false);
                    handler.removeCallbacks(refreshRunnable);
                    handler.post(refreshRunnable);
                });
            } catch (Exception error) {
                android.util.Log.e("DrishtiSetu", "Embedded API startup failed.", error);
                runOnUiThread(() -> Toast.makeText(this,
                        "Local sensor service could not start: " + safeError(error), Toast.LENGTH_LONG).show());
            } finally {
                backendStartupActive.set(false);
            }
        });
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!backendUrl.isEmpty()) refreshBackendData();
            handler.postDelayed(this, 3000L);
        }
    };

    private void refreshBackendData() {
        if (!refreshActive.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                JSONObject latest = getJson(backendUrl + "/api/telemetry/latest");
                boolean available = latest.optBoolean("telemetry_available", false);
                boolean stale = latest.optBoolean("stale_data", false);
                JSONArray history = null;
                JSONArray alerts = null;
                if (System.currentTimeMillis() - lastHistoryRefresh >= 15000L) {
                    try {
                        history = getArray(backendUrl + "/api/telemetry/history?limit=100");
                    } catch (Exception error) {
                        android.util.Log.w("DrishtiSetu", "Saved sensor history could not be refreshed.", error);
                    }
                    try {
                        alerts = getArray(backendUrl + "/api/alerts");
                    } catch (Exception error) {
                        android.util.Log.w("DrishtiSetu", "Risk alerts could not be refreshed.", error);
                    }
                    lastHistoryRefresh = System.currentTimeMillis();
                }
                JSONArray finalHistory = history;
                JSONArray finalAlerts = alerts;
                runOnUiThread(() -> {
                    dashboard.setTelemetry(latest, available, stale, false);
                    if (finalHistory != null) dashboard.setHistory(finalHistory);
                    if (finalAlerts != null) dashboard.setAlerts(finalAlerts);
                });
            } catch (Exception error) {
                android.util.Log.i("DrishtiSetu", "Local API refresh unavailable.", error);
                runOnUiThread(() -> dashboard.setTelemetry(new JSONObject(), false, false, false));
            } finally {
                refreshActive.set(false);
            }
        });
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnectionWithBody response = request(address, "GET", null);
        if (response.status < 200 || response.status >= 300) throw new IOException("Local API returned HTTP " + response.status);
        return new JSONObject(response.body);
    }

    private JSONArray getArray(String address) throws Exception {
        HttpURLConnectionWithBody response = request(address, "GET", null);
        if (response.status < 200 || response.status >= 300) throw new IOException("History API returned HTTP " + response.status);
        return new JSONArray(response.body);
    }

    private HttpURLConnectionWithBody request(String address, String method, JSONObject json) throws Exception {
        return request(address, method, json, 5000);
    }

    private HttpURLConnectionWithBody request(String address, String method, JSONObject json, int readTimeoutMs) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(readTimeoutMs);
            connection.setUseCaches(false);
            if (json != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = input == null ? "" : readFully(input);
            return new HttpURLConnectionWithBody(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static final class HttpURLConnectionWithBody {
        final int status;
        final String body;
        HttpURLConnectionWithBody(int status, String body) { this.status = status; this.body = body; }
    }

    private String readFully(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = stream.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void startSensorMonitorService(int port) {
        Intent service = new Intent(this, SensorMonitorService.class);
        service.putExtra(SensorMonitorService.EXTRA_BACKEND_PORT, port);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, service);
            else startService(service);
        } catch (RuntimeException error) {
            android.util.Log.e("DrishtiSetu", "Could not start ESP32 monitoring service.", error);
            Toast.makeText(this, "Background sensor monitoring could not start.", Toast.LENGTH_LONG).show();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL_ID,
                "Landslide risk alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Risk escalation notifications from the connected ESP32 sensor.");
        alerts.enableVibration(true);
        manager.createNotificationChannel(alerts);
        NotificationChannel monitoring = new NotificationChannel(SensorMonitorService.CHANNEL_ID,
                "Drishti Setu sensor monitoring", NotificationManager.IMPORTANCE_LOW);
        monitoring.setDescription("Background connection status for the ESP32 sensor station.");
        manager.createNotificationChannel(monitoring);
    }

    @Override
    public void requestPermissions() {
        requestRequiredPermissions();
        if (hasLocationPermission() && !isLocationProviderEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on device location")
                    .setMessage("Phone location is the fallback when the ESP32 GPS has no fix. Enable Location on your device to use the fallback.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open location settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (android.content.ActivityNotFoundException error) {
                            Toast.makeText(this, "Could not open device location settings.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
        }
    }

    private boolean isLocationProviderEnabled() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return false;
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (IllegalArgumentException error) {
            android.util.Log.w("DrishtiSetu", "Device location providers are unavailable.", error);
            return false;
        }
    }

    @Override
    public void openVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, VOICE_PERMISSION_REQUEST_CODE);
            return;
        }
        launchVoiceRecognition();
    }

    @Override
    public void editMedicalHistory() {
        showMedicalHistoryEditor();
    }

    @Override
    public void openNearbyFacilities(String facility, double latitude, double longitude) {
        double[] coordinates = resolveCoordinates(latitude, longitude);
        if (coordinates == null) return;
        String query = Uri.encode(facility + " near "
                + String.format(Locale.US, "%.6f,%.6f", coordinates[0], coordinates[1]));
        Uri uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + query);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Could not open a map search. Check that a browser or Maps app is installed.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void searchNearbyFacilities(String facility, double latitude, double longitude,
                                       NativeDashboardView.NearbyFacilitiesCallback callback) {
        double[] coordinates = resolveCoordinates(latitude, longitude);
        if (coordinates == null) {
            callback.onResult(null, "A current GPS fix is needed to search nearby services.");
            return;
        }
        boolean police = "police".equals(facility);
        String amenityPattern = police ? "^police$" : "^(hospital|clinic|doctors)$";
        String query = String.format(Locale.US,
                "[out:json][timeout:20];nwr[\"amenity\"~\"%s\"](around:10000,%.6f,%.6f);out center tags;",
                amenityPattern, coordinates[0], coordinates[1]);
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new java.net.URL(
                        "https://overpass-api.de/api/interpreter").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty("User-Agent",
                        "DrishtiSetu/1.6 (+https://github.com/atharvshish13/LandSlide_AI)");
                connection.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setDoOutput(true);
                String body = "data=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String responseBody = stream == null ? "" : readLimited(stream, 2 * 1024 * 1024);
                if (status < 200 || status >= 300) {
                    throw new IOException("Nearby map search returned HTTP " + status + ".");
                }
                JSONArray elements = new JSONObject(responseBody).optJSONArray("elements");
                if (elements == null) throw new IOException("Nearby map search returned an invalid response.");
                JSONArray results = new JSONArray();
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element != null) results.put(element);
                }
                runOnUiThread(() -> callback.onResult(results, null));
            } catch (Exception error) {
                android.util.Log.w("DrishtiSetu", "Nearby OpenStreetMap search failed.", error);
                String message = "Nearby search failed: " + safeError(error)
                        + " Check your internet connection and try again.";
                runOnUiThread(() -> callback.onResult(null, message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    @Override
    public void openFacilityOnMap(double latitude, double longitude, String name) {
        double[] coordinates = resolveCoordinates(latitude, longitude);
        if (coordinates == null) return;
        String query = Uri.encode(name + " " + String.format(Locale.US, "%.6f,%.6f",
                coordinates[0], coordinates[1]));
        Uri uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + query);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Could not open Google Maps or a browser.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void callFacility(String phone) {
        String number = phone == null ? "" : phone.trim();
        if (!number.matches("[+0-9() .-]{3,40}")) {
            Toast.makeText(this, "This map listing does not contain a valid phone number.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No phone dialer is available on this device.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String readLimited(InputStream input, int maxBytes) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int length;
            while ((length = stream.read(buffer)) != -1) {
                total += length;
                if (total > maxBytes) throw new IOException("Nearby map response was too large.");
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void launchVoiceRecognition() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            Toast.makeText(this, "This device has no microphone.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about your sensor readings");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            voiceInputLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No speech recognition service is available on this device.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        List<String> requested = new ArrayList<>();
        if (!hasLocationPermission()) {
            requested.add(Manifest.permission.ACCESS_FINE_LOCATION);
            requested.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requested.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requested.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!requested.isEmpty()) {
            ActivityCompat.requestPermissions(this, requested.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == SOS_SMS_PERMISSION_REQUEST_CODE) {
            smsPermissionPending = false;
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                sendSmsNow(pendingSosRecipient, pendingSosMessage);
            } else {
                Toast.makeText(this, "SMS permission was denied. WhatsApp SOS will continue.", Toast.LENGTH_LONG).show();
            }
            continueWhatsAppSos(pendingSosRecipient, pendingSosMessage, pendingSosRisk,
                    pendingSosLatitude, pendingSosLongitude, pendingSosProviderSettings,
                    pendingSosMedicalContext);
            finishSosIfComplete();
            return;
        }
        if (requestCode == VOICE_PERMISSION_REQUEST_CODE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                launchVoiceRecognition();
            } else {
                Toast.makeText(this, "Microphone permission is needed for voice questions. You can still type.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        if (hasLocationPermission()) startLocationUpdates();
        else Toast.makeText(this, "Location permission is off. Phone GPS fallback is unavailable.",
                Toast.LENGTH_LONG).show();
    }

    private void startLocationUpdates() {
        if (locationUpdatesActive || !hasLocationPermission()) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        if (locationListener == null) {
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(@NonNull Location location) {
                    lastPhoneLocation = location;
                    dashboard.setPhoneLocation(location.getLatitude(), location.getLongitude(),
                            location.getAccuracy(), location.getElapsedRealtimeNanos());
                }
                @Override public void onProviderDisabled(@NonNull String provider) {}
            };
        }
        boolean started = false;
        try {
            boolean fineLocation = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (fineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 10f, locationListener);
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (isRecentLocation(last)) locationListener.onLocationChanged(last);
                started = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 25f, locationListener);
                started = true;
            }
        } catch (SecurityException error) {
            android.util.Log.w("DrishtiSetu", "Location permission is not available.", error);
        } catch (IllegalArgumentException error) {
            android.util.Log.w("DrishtiSetu", "No usable phone location provider is available.", error);
        }
        locationUpdatesActive = started;
    }

    private void stopLocationUpdates() {
        if (locationManager == null || locationListener == null) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException error) {
            android.util.Log.w("DrishtiSetu", "Could not stop location updates.", error);
        }
        locationUpdatesActive = false;
    }

    @Override
    public void sendSos(double latitude, double longitude, String risk) {
        if (sosSending) {
            Toast.makeText(this, "SOS is already being sent.", Toast.LENGTH_SHORT).show();
            return;
        }
        if ((!Double.isFinite(latitude) || !Double.isFinite(longitude))
                && isRecentLocation(lastPhoneLocation)) {
            latitude = lastPhoneLocation.getLatitude();
            longitude = lastPhoneLocation.getLongitude();
        }
        final String recipient;
        final String providerSettings;
        try {
            providerSettings = readProviderSettings(this);
            JSONObject settings = new JSONObject(providerSettings.isEmpty() ? "{}" : providerSettings);
            String configuredRecipient = settings.optString("whatsapp_to", "").trim();
            if (!configuredRecipient.matches("\\+?[1-9][0-9]{7,14}")) {
                Toast.makeText(this, "Set a valid SOS recipient in More → Settings before sending.", Toast.LENGTH_LONG).show();
                return;
            }
            recipient = configuredRecipient.startsWith("+") ? configuredRecipient : "+" + configuredRecipient;
        } catch (Exception error) {
            android.util.Log.e("DrishtiSetu", "Could not read the configured SOS recipient.", error);
            Toast.makeText(this, "SOS recipient could not be read. Check provider settings.", Toast.LENGTH_LONG).show();
            return;
        }

        String coordinates = Double.isFinite(latitude) && Double.isFinite(longitude)
                ? String.format(Locale.US, "%.5f,%.5f", latitude, longitude) : "";
        String medicalContext = "";
        try {
            medicalContext = medicalContextForSos(readMedicalProfile());
        } catch (Exception error) {
            android.util.Log.e("DrishtiSetu", "Could not read the encrypted medical card.", error);
            Toast.makeText(this, "SOS will continue without medical details because the private card could not be read.",
                    Toast.LENGTH_LONG).show();
        }
        final String approvedMedicalContext = medicalContext;
        String message = "Drishti Setu SOS: Emergency assistance requested. Status: " + risk
                + (coordinates.isEmpty() ? ". Phone GPS location is unavailable."
                : ". Location: " + coordinates + " https://maps.google.com/?q=" + coordinates)
                + approvedMedicalContext;

        sosSending = true;
        whatsappSosComplete = false;
        pendingSosRecipient = recipient;
        pendingSosMessage = message;
        pendingSosMedicalContext = approvedMedicalContext;
        pendingSosRisk = risk;
        pendingSosLatitude = latitude;
        pendingSosLongitude = longitude;
        pendingSosProviderSettings = providerSettings;
        smsPermissionPending = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED;
        if (smsPermissionPending) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS},
                    SOS_SMS_PERMISSION_REQUEST_CODE);
            return;
        }
        sendSmsNow(recipient, message);
        continueWhatsAppSos(recipient, message, risk, latitude, longitude, providerSettings,
                approvedMedicalContext);
        finishSosIfComplete();
    }

    private boolean hasWhatsAppCloudConfiguration(JSONObject settings) {
        return !settings.optString("whatsapp_access_token", "").trim().isEmpty()
                && !settings.optString("whatsapp_phone_number_id", "").trim().isEmpty()
                && !settings.optString("whatsapp_template_name", "").trim().isEmpty()
                && !settings.optString("whatsapp_graph_api_version", "").trim().isEmpty();
    }

    private void continueWhatsAppSos(String recipient, String message, String risk,
                                      double latitude, double longitude, String providerSettings,
                                      String medicalContext) {
        JSONObject settings;
        try {
            settings = new JSONObject(providerSettings.isEmpty() ? "{}" : providerSettings);
        } catch (JSONException error) {
            android.util.Log.e("DrishtiSetu", "Saved WhatsApp settings are invalid JSON.", error);
            Toast.makeText(this, "WhatsApp settings could not be read.", Toast.LENGTH_LONG).show();
            whatsappSosComplete = true;
            return;
        }

        if (!hasWhatsAppCloudConfiguration(settings) || backendUrl.isEmpty()) {
            openWhatsAppChat(recipient, message);
            whatsappSosComplete = true;
            return;
        }

        io.execute(() -> {
            try {
                Python.getInstance().getModule("backend.android_runtime")
                        .callAttr("set_provider_config", providerSettings);
                JSONObject payload = new JSONObject();
                payload.put("location_name", "Drishti Setu");
                payload.put("risk_level", risk);
                if (Double.isFinite(latitude) && Double.isFinite(longitude)) {
                    payload.put("latitude", latitude);
                    payload.put("longitude", longitude);
                }
                if (!medicalContext.isEmpty()) payload.put("medical_context", medicalContext);
                HttpURLConnectionWithBody response = request(backendUrl + "/api/alerts/sos", "POST", payload, 10000);
                if (response.status != 202) {
                    JSONObject errorBody = new JSONObject(response.body);
                    throw new IOException(errorBody.optString("error", "WhatsApp did not confirm the SOS."));
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "WhatsApp SOS accepted for " + recipient + ".", Toast.LENGTH_LONG).show();
                    whatsappSosComplete = true;
                    finishSosIfComplete();
                });
            } catch (Exception error) {
                android.util.Log.e("DrishtiSetu", "WhatsApp SOS delivery failed.", error);
                String detail = safeError(error);
                runOnUiThread(() -> {
                    Toast.makeText(this, "WhatsApp SOS failed: " + detail, Toast.LENGTH_LONG).show();
                    whatsappSosComplete = true;
                    finishSosIfComplete();
                });
            }
        });
    }

    private void openWhatsAppChat(String recipient, String message) {
        String digits = recipient.replace("+", "");
        Uri uri = Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(message));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            Toast.makeText(this, "WhatsApp opened with the SOS prepared. Tap Send in WhatsApp to deliver it.", Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException | IllegalArgumentException error) {
            android.util.Log.e("DrishtiSetu", "Could not open WhatsApp click-to-chat.", error);
            Toast.makeText(this, "Could not open WhatsApp. The SMS send was attempted.", Toast.LENGTH_LONG).show();
        }
    }

    private void sendSmsNow(String recipient, String message) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS permission is unavailable. WhatsApp SOS will continue if configured.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            if (smsManager == null) throw new IllegalStateException("SMS service is unavailable on this device.");
            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() == 1) {
                smsManager.sendTextMessage(recipient, null, message, null, null);
            } else {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null);
            }
            Toast.makeText(this, "SMS submitted to Android for " + recipient + "; delivery is not confirmed.", Toast.LENGTH_LONG).show();
        } catch (SecurityException | IllegalArgumentException | IllegalStateException error) {
            android.util.Log.e("DrishtiSetu", "Could not submit SOS SMS.", error);
            Toast.makeText(this, "SMS could not be sent: " + safeError(error), Toast.LENGTH_LONG).show();
        }
    }

    private void finishSosIfComplete() {
        if (!smsPermissionPending && whatsappSosComplete) {
            sosSending = false;
            pendingSosRecipient = "";
            pendingSosMessage = "";
            pendingSosMedicalContext = "";
            pendingSosRisk = "";
            pendingSosProviderSettings = "";
            pendingSosLatitude = Double.NaN;
            pendingSosLongitude = Double.NaN;
        }
    }

    @Override
    public void openMap(double latitude, double longitude) {
        double[] coordinates = resolveCoordinates(latitude, longitude);
        if (coordinates == null) return;
        Uri uri = Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/@?api=1&map_action=map&center=%.6f,%.6f&zoom=19&basemap=satellite&tilt=45",
                coordinates[0], coordinates[1]));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Could not open a satellite map. Check that a browser or Maps app is installed.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void openEarth(double latitude, double longitude) {
        double[] coordinates = resolveCoordinates(latitude, longitude);
        if (coordinates == null) return;
        Uri uri = Uri.parse(String.format(Locale.US,
                "https://earth.google.com/web/@%.6f,%.6f,2000a,0d,45y,0h,0t,0r",
                coordinates[0], coordinates[1]));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Could not open Google Earth. Check that a browser is installed.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private double[] resolveCoordinates(double latitude, double longitude) {
        if ((!Double.isFinite(latitude) || !Double.isFinite(longitude))
                && isRecentLocation(lastPhoneLocation)) {
            latitude = lastPhoneLocation.getLatitude();
            longitude = lastPhoneLocation.getLongitude();
        }
        if (Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
            return new double[]{latitude, longitude};
        }
        Toast.makeText(this, "Waiting for a phone or ESP32 GPS fix. Enable Location and try again.",
                Toast.LENGTH_LONG).show();
        requestRequiredPermissions();
        return null;
    }

    private boolean isRecentLocation(Location location) {
        if (location == null) return false;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return ageNanos >= 0 && ageNanos <= 2L * 60L * 1_000_000_000L;
    }

    @Override
    public void openSettings() {
        showProviderSettings();
    }

    @Override
    public void savePdf(String report) {
        pendingReportText = report;
        String fileName = report.contains("PRIVATE MEDICAL HISTORY")
                ? "drishti-setu-medical-history.pdf"
                : report.contains("AI HISTORICAL ANALYSIS")
                        ? "drishti-setu-ai-history-analysis.pdf"
                        : "drishti-setu-sensor-report.pdf";
        pdfExportLauncher.launch(fileName);
    }

    @Override
    public void saveCsv(String csv) {
        pendingCsvText = csv;
        csvExportLauncher.launch("drishti-setu-sensor-history.csv");
    }

    @Override
    public void sendGemini(JSONObject request, NativeDashboardView.ResultCallback callback) {
        if (backendUrl.isEmpty()) {
            callback.onResult(null, "The on-device service is still starting. Try again shortly.");
            return;
        }
        io.execute(() -> {
            try {
                HttpURLConnectionWithBody response = request(backendUrl + "/api/ai/chat", "POST", request, 35000);
                JSONObject result = new JSONObject(response.body);
                if (response.status < 200 || response.status >= 300) {
                    throw new IOException(result.optString("error", "Gemini request failed."));
                }
                String answer = result.optString("response", "");
                if (answer.trim().isEmpty()) throw new IOException("Gemini returned an empty response.");
                runOnUiThread(() -> callback.onResult(answer, null));
            } catch (Exception error) {
                android.util.Log.w("DrishtiSetu", "Gemini request failed.", error);
                String message = safeError(error);
                runOnUiThread(() -> callback.onResult(null, message));
            }
        });
    }

    private JSONObject readMedicalProfile() throws Exception {
        String plaintext = readEncryptedValue(this, MEDICAL_SETTINGS_KEY);
        return plaintext.isEmpty() ? new JSONObject() : new JSONObject(plaintext);
    }

    private void refreshMedicalSummary() {
        try {
            JSONObject profile = readMedicalProfile();
            dashboard.setMedicalSummary(formatMedicalSummary(profile),
                    profile.optBoolean("share_medical_on_sos", false));
        } catch (Exception error) {
            android.util.Log.e("DrishtiSetu", "Could not read the encrypted medical card.", error);
            Toast.makeText(this, "Saved medical history could not be decrypted.", Toast.LENGTH_LONG).show();
        }
    }

    private String formatMedicalSummary(JSONObject profile) {
        StringBuilder summary = new StringBuilder();
        appendMedicalField(summary, "Blood group", profile.optString("blood_group", ""));
        appendMedicalField(summary, "Allergies", profile.optString("allergies", ""));
        appendMedicalField(summary, "Conditions", profile.optString("medical_conditions", ""));
        appendMedicalField(summary, "Medications", profile.optString("medications", ""));
        appendMedicalField(summary, "Emergency notes", profile.optString("emergency_notes", ""));
        return summary.length() == 0 ? "No medical history saved." : summary.toString().trim();
    }

    private String medicalContextForSos(JSONObject profile) {
        if (!profile.optBoolean("share_medical_on_sos", false)) return "";
        String summary = formatMedicalSummary(profile);
        if ("No medical history saved.".equals(summary)) return "";
        String flattened = summary.replace('\n', ';').trim();
        if (flattened.length() > 650) flattened = flattened.substring(0, 650);
        return " User-approved medical card: " + flattened;
    }

    private void appendMedicalField(StringBuilder summary, String label, String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return;
        if (summary.length() > 0) summary.append('\n');
        summary.append(label).append(": ").append(normalized);
    }

    private void showMedicalHistoryEditor() {
        final JSONObject saved;
        try {
            saved = readMedicalProfile();
        } catch (Exception error) {
            android.util.Log.e("DrishtiSetu", "Could not open the encrypted medical card.", error);
            Toast.makeText(this, "Medical history could not be opened.", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 8, padding, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        EditText bloodGroup = addMedicalField(fields, "Blood group (optional)",
                saved.optString("blood_group", ""), 40);
        EditText allergies = addMedicalField(fields, "Allergies (optional)",
                saved.optString("allergies", ""), 160);
        EditText conditions = addMedicalField(fields, "Medical conditions (optional)",
                saved.optString("medical_conditions", ""), 160);
        EditText medications = addMedicalField(fields, "Medications (optional)",
                saved.optString("medications", ""), 160);
        EditText emergencyNotes = addMedicalField(fields, "Emergency notes (optional)",
                saved.optString("emergency_notes", ""), 220);
        CheckBox share = new CheckBox(this);
        share.setText("Include these medical details in SOS SMS and WhatsApp messages to my configured contact");
        share.setTextSize(14);
        share.setChecked(saved.optBoolean("share_medical_on_sos", false));
        fields.addView(share, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView privacy = new TextView(this);
        privacy.setText("The card is encrypted on this phone. Details are excluded from SOS by default. If opted in, they are sent only to your configured recipient. Nearby hospital and police searches open Maps; they do not send your medical record or contact those services.");
        privacy.setTextSize(12);
        fields.addView(privacy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Private emergency medical card")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                bloodGroup.setText("");
                allergies.setText("");
                conditions.setText("");
                medications.setText("");
                emergencyNotes.setText("");
                share.setChecked(false);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                JSONObject profile = new JSONObject();
                try {
                    profile.put("blood_group", fieldValue(bloodGroup));
                    profile.put("allergies", fieldValue(allergies));
                    profile.put("medical_conditions", fieldValue(conditions));
                    profile.put("medications", fieldValue(medications));
                    profile.put("emergency_notes", fieldValue(emergencyNotes));
                    profile.put("share_medical_on_sos", share.isChecked());
                    writeEncryptedValue(MEDICAL_SETTINGS_KEY, profile.toString());
                    dashboard.setMedicalSummary(formatMedicalSummary(profile), share.isChecked());
                    Toast.makeText(this, "Medical card encrypted and saved on this device.",
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } catch (Exception error) {
                    android.util.Log.e("DrishtiSetu", "Could not save the encrypted medical card.", error);
                    Toast.makeText(this, "Medical card could not be encrypted or saved.",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private EditText addMedicalField(LinearLayout parent, String hint, String value, int maxLength) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(14);
        field.setSingleLine(false);
        field.setMinLines(1);
        field.setMaxLines(3);
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void showProviderSettings() {
        JSONObject saved = new JSONObject();
        try {
            String stored = readProviderSettings(this);
            if (!stored.isEmpty()) saved = new JSONObject(stored);
        } catch (Exception error) {
            android.util.Log.w("DrishtiSetu", "Could not read saved provider settings.", error);
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 8, padding, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        EditText token = addSettingsField(fields, "WhatsApp Business API token (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, saved.optString("whatsapp_access_token"));
        EditText phoneId = addSettingsField(fields, "Meta business phone-number ID (optional)", InputType.TYPE_CLASS_NUMBER, saved.optString("whatsapp_phone_number_id"));
        EditText recipient = addSettingsField(fields, "SOS recipient for WhatsApp and SMS (E.164)", InputType.TYPE_CLASS_PHONE, saved.optString("whatsapp_to"));
        EditText template = addSettingsField(fields, "Approved WhatsApp template (optional)", InputType.TYPE_CLASS_TEXT, saved.optString("whatsapp_template_name"));
        EditText language = addSettingsField(fields, "Template language", InputType.TYPE_CLASS_TEXT, saved.optString("whatsapp_template_language", "en"));
        EditText graphVersion = addSettingsField(fields, "Meta Graph API version (optional)", InputType.TYPE_CLASS_TEXT, saved.optString("whatsapp_graph_api_version"));
        EditText weather = addSettingsField(fields, "Weatherstack API key (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, saved.optString("weatherstack_api_key"));
        EditText gemini = addSettingsField(fields, "Gemini API key (enter a rotated key)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, saved.optString("gemini_api_key"));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Secure provider settings")
                .setMessage("Provider settings are encrypted on this phone. Without WhatsApp Business API credentials, SOS opens a prefilled WhatsApp chat; you must tap Send. SMS is sent directly after Android permission. SMS charges may apply.")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            JSONObject config = new JSONObject();
            try {
                config.put("whatsapp_access_token", fieldValue(token));
                config.put("whatsapp_phone_number_id", fieldValue(phoneId));
                config.put("whatsapp_to", fieldValue(recipient));
                config.put("whatsapp_template_name", fieldValue(template));
                config.put("whatsapp_template_language", fieldValue(language));
                config.put("whatsapp_graph_api_version", fieldValue(graphVersion));
                config.put("weatherstack_api_key", fieldValue(weather));
                config.put("gemini_api_key", fieldValue(gemini));
            } catch (JSONException error) {
                Toast.makeText(this, "Could not prepare provider settings.", Toast.LENGTH_LONG).show();
                return;
            }
            String[] whatsappApi = {fieldValue(token), fieldValue(phoneId), fieldValue(template), fieldValue(graphVersion)};
            boolean anyApi = false, allApi = true;
            for (String value : whatsappApi) { anyApi |= !value.isEmpty(); allApi &= !value.isEmpty(); }
            if (anyApi && !allApi) {
                Toast.makeText(this, "Enter all WhatsApp Business API settings or leave them blank to use click-to-chat.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!fieldValue(recipient).isEmpty()
                    && !fieldValue(recipient).matches("\\+?[1-9][0-9]{7,14}")) {
                recipient.setError("Use an international phone number, such as +91…");
                return;
            }
            if (anyApi && !fieldValue(graphVersion).matches("v[0-9]+\\.[0-9]+")) {
                graphVersion.setError("Use a version such as v23.0");
                return;
            }
            try {
                writeEncryptedSettings(config.toString());
                applyProviderSettings(config.toString());
                Toast.makeText(this, "Provider settings saved securely on this device.", Toast.LENGTH_LONG).show();
                dialog.dismiss();
            } catch (Exception error) {
                android.util.Log.e("DrishtiSetu", "Could not save encrypted provider settings.", error);
                Toast.makeText(this, "Settings could not be secured on this device.", Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private EditText addSettingsField(LinearLayout parent, String hint, int inputType, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setText(value);
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private String fieldValue(EditText field) {
        return field.getText().toString().trim();
    }

    private static SecretKey getOrCreateSettingsKey(Context context) throws Exception {
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private void writeEncryptedSettings(String plaintext) throws Exception {
        writeEncryptedValue(SETTINGS_KEY, plaintext);
    }

    private void writeEncryptedValue(String preferenceKey, String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSettingsKey(this));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] encoded = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encoded, 0, iv.length);
        System.arraycopy(ciphertext, 0, encoded, iv.length, ciphertext.length);
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit()
                .putString(preferenceKey, Base64.encodeToString(encoded, Base64.NO_WRAP)).apply();
    }

    public static String readProviderSettings(Context context) throws Exception {
        return readEncryptedValue(context, SETTINGS_KEY);
    }

    private static String readEncryptedValue(Context context, String preferenceKey) throws Exception {
        String stored = context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getString(preferenceKey, "");
        if (stored == null || stored.isEmpty()) return "";
        byte[] encoded = Base64.decode(stored, Base64.NO_WRAP);
        int ivLength = 12;
        if (encoded.length <= ivLength) throw new IllegalStateException("Stored settings are incomplete.");
        byte[] iv = new byte[ivLength];
        byte[] ciphertext = new byte[encoded.length - ivLength];
        System.arraycopy(encoded, 0, iv, 0, ivLength);
        System.arraycopy(encoded, ivLength, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSettingsKey(context), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private void applyProviderSettings(String settings) {
        io.execute(() -> {
            try {
                if (Python.isStarted()) Python.getInstance().getModule("backend.android_runtime")
                        .callAttr("set_provider_config", settings);
            } catch (Exception error) {
                android.util.Log.e("DrishtiSetu", "Could not apply provider settings.", error);
            }
        });
    }

    private void writeReportPdf(Uri uri, String report) {
        PdfDocument document = new PdfDocument();
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.rgb(27, 43, 61));
        body.setTextSize(10.5f);
        int width = 595, height = 842, margin = 42, lineHeight = 16;
        List<String> lines = wrapText(report, body, width - margin * 2);
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Could not open the selected document.");
            int index = 0, pageNumber = 1;
            while (index < lines.size()) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(width, height, pageNumber).create());
                Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
                header.setColor(Color.rgb(8, 126, 131));
                header.setTextSize(9);
                boolean medicalReport = report.contains("PRIVATE MEDICAL HISTORY");
                String heading = medicalReport ? "DRISHTI SETU  |  PRIVATE MEDICAL HISTORY"
                        : report.contains("AI HISTORICAL ANALYSIS")
                                ? "DRISHTI SETU  |  AI SENSOR HISTORY"
                                : "DRISHTI SETU  |  SENSOR SAFETY REPORT";
                android.graphics.drawable.Drawable logo =
                        ContextCompat.getDrawable(this, R.drawable.drishti_launcher);
                if (logo != null) {
                    logo.setBounds(margin, 8, margin + 24, 32);
                    logo.draw(page.getCanvas());
                }
                page.getCanvas().drawText(heading, margin + 32, 24, header);
                int y = 52;
                while (index < lines.size() && y < height - margin) {
                    page.getCanvas().drawText(lines.get(index++), margin, y, body);
                    y += lineHeight;
                }
                header.setColor(Color.GRAY);
                page.getCanvas().drawText("Page " + pageNumber, width - margin - 40, height - 20, header);
                document.finishPage(page);
                pageNumber++;
            }
            document.writeTo(output);
            Toast.makeText(this, "PDF report saved.", Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException error) {
            android.util.Log.e("DrishtiSetu", "PDF report export failed.", error);
            Toast.makeText(this, "Could not save PDF: " + safeError(error), Toast.LENGTH_LONG).show();
        } finally {
            document.close();
        }
    }

    private List<String> wrapText(String text, Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        for (String source : text.split("\\R", -1)) {
            if (source.isEmpty()) { lines.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : source.split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(candidate) <= width || line.length() == 0) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private void writeCsv(Uri uri, String csv) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Could not open the selected document.");
            output.write(csv.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Sensor history CSV saved.", Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException error) {
            android.util.Log.e("DrishtiSetu", "Sensor history CSV export failed.", error);
            Toast.makeText(this, "Could not save CSV: " + safeError(error), Toast.LENGTH_LONG).show();
        }
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    protected void onResume() {
        super.onResume();
        SensorMonitorService.setDashboardVisible(true);
        int port = getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).getInt(BACKEND_PORT_KEY, 0);
        if (port > 0) startSensorMonitorService(port);
        startLocationUpdates();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        SensorMonitorService.setDashboardVisible(false);
        stopLocationUpdates();
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (dashboard != null && dashboard.handleBack()) return;
        super.onBackPressed();
    }
}
