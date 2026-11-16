package com.geoguard.landslide;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

final class NativeDashboardView extends LinearLayout {
    interface Actions {
        void requestPermissions();
        void openVoiceInput();
        void openSettings();
        void editMedicalHistory();
        void sendSos(double latitude, double longitude, String risk);
        void openMap(double latitude, double longitude);
        void openEarth(double latitude, double longitude);
        void openNearbyFacilities(String facility, double latitude, double longitude);
        void savePdf(String report);
        void saveCsv(String csv);
        void sendGemini(JSONObject request, ResultCallback callback);
    }

    interface ResultCallback {
        void onResult(String response, String error);
    }

    private static final int INK = 0xff172d40;
    private static final int MUTED = 0xff718194;
    private static final int SURFACE = 0xffffffff;
    private static final int BACKGROUND = 0xfffff5e9;
    private static final int HEADER_BLUE = 0xffb9e1f6;
    private static final int BORDER = 0xffeee5d9;
    private static final int TEAL = 0xff087e9d;
    private static final int GREEN = 0xff198754;
    private static final int AMBER = 0xffd18a16;
    private static final int RED = 0xffc33c37;
    private static final String[] TAB_IDS = {"home", "sensors", "ai", "reports", "more"};
    private final Actions actions;
    private final LinearLayout content;
    private final LinearLayout navigation;
    private final ScrollView scroll;
    private final TextView connectionLabel;
    private final List<LinearLayout> tabItems = new ArrayList<>();
    private final List<TextView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final Deque<String> screenBackStack = new ArrayDeque<>();
    private JSONObject latest = new JSONObject();
    private JSONArray history = new JSONArray();
    private JSONArray alerts = new JSONArray();
    private String screen = "";
    private String chartKey = "";
    private String locationText = "Location not available";
    private TextView visibleLocationLabel;
    private TextView visibleGpsSourceLabel;
    private LiveMapView liveMapView;
    private EditText chatInput;
    private String pendingVoiceText = "";
    private String medicalSummary = "No medical history saved.";
    private boolean shareMedicalWithSos;
    private double phoneLatitude = Double.NaN;
    private double phoneLongitude = Double.NaN;
    private float phoneAccuracyMeters;
    private long phoneLocationElapsedNanos;
    private boolean telemetryAvailable;
    private boolean stale;
    private boolean demo;
    private boolean loadingChat;
    private boolean reportAnalysisLoading;
    private String reportAnalysis = "";
    private String reportAnalysisError = "";
    NativeDashboardView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(12), dp(16), dp(12));
        header.setBackgroundColor(HEADER_BLUE);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.drishti_launcher);
        mark.setContentDescription("Drishti Setu");
        header.addView(mark, new LayoutParams(dp(36), dp(36)));
        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(VERTICAL);
        titleBlock.setPadding(dp(10), 0, 0, 0);
        titleBlock.addView(text("Drishti Setu", 17, INK, true));
        titleBlock.addView(text("Slope safety monitoring", 11, MUTED, false));
        header.addView(titleBlock, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        connectionLabel = text("WAITING", 10, MUTED, true);
        connectionLabel.setGravity(Gravity.CENTER);
        connectionLabel.setPadding(dp(10), dp(7), dp(10), dp(7));
        connectionLabel.setBackground(pill(BACKGROUND, BORDER));
        header.addView(connectionLabel);
        Button sos = button(context, "SOS", RED, 0xffffffff);
        sos.setOnClickListener(view -> actions.sendSos(latitude(), longitude(), risk()));
        LayoutParams sosParams = new LayoutParams(dp(58), dp(42));
        sosParams.leftMargin = dp(10);
        header.addView(sos, sosParams);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(line(context, BORDER), new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));

        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(22));
        scroll.addView(content);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        navigation = new LinearLayout(context);
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackgroundColor(BACKGROUND);
        navigation.setPadding(dp(8), dp(6), dp(8), dp(6));
        addView(line(context, BORDER), new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
        addView(navigation, new LayoutParams(LayoutParams.MATCH_PARENT, dp(76)));
        buildNavigation();
        navigateTab("home");
    }

    void setTelemetry(JSONObject data, boolean available, boolean staleData, boolean simulated) {
        latest = data == null ? new JSONObject() : data;
        telemetryAvailable = available;
        stale = staleData;
        demo = simulated;
        updateConnection();
        if ("map".equals(screen)) {
            if (visibleGpsSourceLabel != null) visibleGpsSourceLabel.setText(gpsLocationText());
            updateLiveMapLocation();
        }
        redrawCurrentDataScreen();
    }

    void setHistory(JSONArray readings) {
        history = readings == null ? new JSONArray() : readings;
        redrawCurrentDataScreen();
    }

    void setAlerts(JSONArray recentAlerts) {
        alerts = recentAlerts == null ? new JSONArray() : recentAlerts;
        if ("alerts".equals(screen)) renderAlerts();
    }

    private void redrawCurrentDataScreen() {
        if (!"home".equals(screen) && !"sensors".equals(screen) && !"reports".equals(screen)) return;
        int scrollY = scroll.getScrollY();
        if ("home".equals(screen)) renderHome();
        else if ("sensors".equals(screen)) renderSensors();
        else renderReports();
        final int savedScrollY = scrollY;
        scroll.post(() -> scroll.scrollTo(0, savedScrollY));
    }

    void setPhoneLocation(double latitude, double longitude, float accuracy, long elapsedRealtimeNanos) {
        if (Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
            phoneLatitude = latitude;
            phoneLongitude = longitude;
            phoneAccuracyMeters = accuracy;
            phoneLocationElapsedNanos = elapsedRealtimeNanos;
            locationText = String.format(Locale.US, "%.5f, %.5f  ·  ±%.0fm",
                    latitude, longitude, accuracy);
            if (visibleLocationLabel != null) visibleLocationLabel.setText(locationText);
            if (visibleGpsSourceLabel != null) visibleGpsSourceLabel.setText(gpsLocationText());
            if ("map".equals(screen)) updateLiveMapLocation();
        }
    }

    void setVoiceInput(String recognizedText) {
        if (recognizedText == null || recognizedText.trim().isEmpty()) return;
        pendingVoiceText = recognizedText.trim();
        if (chatInput != null && "ai".equals(screen)) {
            chatInput.setText(pendingVoiceText);
            chatInput.setSelection(chatInput.length());
            pendingVoiceText = "";
        }
    }

    void setMedicalSummary(String summary, boolean shareWithSos) {
        medicalSummary = summary == null || summary.trim().isEmpty()
                ? "No medical history saved." : summary;
        shareMedicalWithSos = shareWithSos;
        if ("medical".equals(screen)) renderMedicalHistory();
    }

    boolean handleBack() {
        if ("home".equals(screen)) return false;
        String previous = screenBackStack.isEmpty() ? "home" : screenBackStack.pop();
        displayScreen(previous);
        return true;
    }

    private void updateConnection() {
        String label = demo ? "DEMO · SIMULATED" : stale ? "STALE DATA"
                : telemetryAvailable ? "LIVE · ESP32" : "WAITING";
        int color = demo ? AMBER : stale ? RED : telemetryAvailable ? GREEN : MUTED;
        connectionLabel.setText(label);
        connectionLabel.setTextColor(color);
    }

    private void showScreen(String target) {
        if (target.equals(screen)) return;
        screenBackStack.push(screen);
        if (screenBackStack.size() > 12) screenBackStack.removeLast();
        displayScreen(target);
    }

    private void navigateTab(String target) {
        screenBackStack.clear();
        displayScreen(target);
    }

    private void displayScreen(String target) {
        screen = target;
        content.removeAllViews();
        updateSelectedTab(target);
        switch (target) {
            case "sensors": renderSensors(); break;
            case "ai": renderChat(); break;
            case "reports": renderReports(); break;
            case "alerts": renderAlerts(); break;
            case "twin": renderTwin(); break;
            case "map": renderMap(); break;
            case "medical": renderMedicalHistory(); break;
            case "settings": renderSettings(); break;
            case "more": renderMore(); break;
            default: renderHome(); break;
        }
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void buildNavigation() {
        String[] ids = TAB_IDS;
        String[] labels = {"Dashboard", "Sensors", "Assistant", "Reports", "More"};
        String[] icons = {"▦", "◉", "✦", "▤", "•••"};
        navigation.removeAllViews();
        tabItems.clear();
        tabIcons.clear();
        tabLabels.clear();
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setFocusable(true);
            item.setClickable(true);
            item.setContentDescription(labels[i] + " tab");
            item.setBackground(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x22087e83), rounded(0x00000000, 0x00000000, 18), null));
            TextView icon = text(icons[i], 24, MUTED, true);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));
            TextView label = text(labels[i], 12, MUTED, true);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            item.setOnClickListener(view -> navigateTab(id));
            navigation.addView(item, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
            tabItems.add(item);
            tabIcons.add(icon);
            tabLabels.add(label);
        }
    }

    private void updateSelectedTab(String target) {
        String selectedTab = bottomTabFor(target);
        for (int i = 0; i < tabItems.size(); i++) {
            boolean selected = selectedTab.equals(TAB_IDS[i]);
            LinearLayout item = tabItems.get(i);
            item.setSelected(selected);
            item.setBackground(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x22087e83),
                    rounded(selected ? 0xffe7f3f1 : 0x00000000,
                            selected ? 0xffe7f3f1 : 0x00000000, 18), null));
            tabIcons.get(i).setTextColor(selected ? TEAL : MUTED);
            tabLabels.get(i).setTextColor(selected ? TEAL : MUTED);
        }
    }

    private String bottomTabFor(String target) {
        switch (target) {
            case "sensors": return "sensors";
            case "ai": return "ai";
            case "reports": return "reports";
            case "home": return "home";
            default: return "more";
        }
    }

    private void renderHome() {
        content.removeAllViews();
        content.addView(text("Dashboard", 25, INK, true));
        content.addView(text("Hello, User · your slope safety overview", 14, MUTED, false));
        space(content, 16);

        int riskColor = riskColor(risk());
        LinearLayout riskCard = card();
        riskCard.setBackground(rounded(riskTint(riskColor), riskTint(riskColor), 18));
        TextView caption = text("CURRENT SLOPE STATUS", 11, MUTED, true);
        riskCard.addView(caption);
        LinearLayout riskRow = new LinearLayout(getContext());
        riskRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(statusTitle(), 23, riskColor, true);
        riskRow.addView(badge, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView shield = text(telemetryAvailable || demo ? "✓" : "…", 24, 0xffffffff, true);
        shield.setGravity(Gravity.CENTER);
        shield.setBackground(circle(riskColor));
        riskRow.addView(shield, new LayoutParams(dp(48), dp(48)));
        riskCard.addView(riskRow);
        TextView advisory = text(advisory(), 13, INK, false);
        advisory.setPadding(0, dp(12), 0, 0);
        riskCard.addView(advisory);
        content.addView(riskCard);

        section("Latest readings");
        LinearLayout readings = card();
        readings.setOrientation(HORIZONTAL);
        addMetric(readings, "Soil moisture", soilMoistureValue(), TEAL);
        addMetric(readings, "Rain sensor", value("rain_intensity", "%", 1), 0xff2879a5);
        addMetric(readings, "Temperature", sensorValue("temperature", "dht", "°C", 1), AMBER);
        content.addView(readings);

        section("Risk model");
        LinearLayout model = card();
        addKeyValue(model, "Factor of safety", value("factor_of_safety", "", 2));
        addKeyValue(model, "Maximum tilt", value("max_tilt", "°", 1));
        addKeyValue(model, "Risk score", value("risk_score", "%", 1));
        addKeyValue(model, "30-minute outlook", forecast());
        content.addView(model);

        section("Your location");
        LinearLayout location = card();
        addGpsSourceLabel(location);
        addLocationLabel(location);
        Button mapButton = button(getContext(), "Open map", 0xffe8f4f3, TEAL);
        mapButton.setOnClickListener(view -> showScreen("map"));
        LinearLayout.LayoutParams mapParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(42));
        mapParams.topMargin = dp(10);
        location.addView(mapButton, mapParams);
        content.addView(location);

        section("Quick access");
        LinearLayout quickAccess = new LinearLayout(getContext());
        quickAccess.setGravity(Gravity.CENTER);
        addQuickAccess(quickAccess, "◉", "View\nSensors", "sensors");
        addQuickAccess(quickAccess, "◷", "History", "sensors");
        addQuickAccess(quickAccess, "✦", "Gemini AI", "ai");
        content.addView(quickAccess);
    }

    private void renderSensors() {
        content.removeAllViews();
        content.addView(text("Sensor History", 25, INK, true));
        content.addView(text("Last 24 hours · saved ESP32 readings", 14, MUTED, false));
        space(content, 12);
        addHistoryChart("soil", "Soil Moisture (%)");
        addHistoryChart("rain", "Rainfall sensor (%)");

        section("Latest sample");
        LinearLayout latestCard = card();
        latestCard.addView(text("Time: " + latestSampleTime(), 12, MUTED, false));
        latestCard.addView(text("Soil: " + soilMoistureValue(),
                13, GREEN, true));
        latestCard.addView(text("Rain sensor: " + sensorValue("rain_intensity", "rain", "%", 1)
                + "  |  Status: " + (demo ? "Demo" : stale ? "Stale" : telemetryAvailable ? risk() : "Waiting"),
                13, INK, false));
        content.addView(latestCard);

        section("Other sensor trends");
        LinearLayout otherSensors = new LinearLayout(getContext());
        otherSensors.setGravity(Gravity.CENTER_VERTICAL);
        addSensorChoice(otherSensors, "temperature", "Temperature");
        addSensorChoice(otherSensors, "gps", "GPS altitude");
        content.addView(otherSensors);
        if ("temperature".equals(chartKey)) addHistoryChart("temperature", "Temperature (°C)");
        else if ("gps".equals(chartKey)) addHistoryChart("gps", "GPS altitude (m)");

        section("Recent samples");
        JSONArray shown = history;
        if (shown.length() == 0) {
            LinearLayout empty = card();
            empty.addView(text("No saved readings yet. Connect the phone to the ESP32 station and keep the app running.", 13, MUTED, false));
            content.addView(empty);
        } else {
            int start = Math.max(0, shown.length() - 8);
            for (int i = shown.length() - 1; i >= start; i--) {
                JSONObject row = shown.optJSONObject(i);
                if (row != null) addReadingRow(row);
            }
        }

        section("Sensor location");
        LinearLayout gps = card();
        addGpsSourceLabel(gps);
        addLocationLabel(gps);
        Button request = button(getContext(), "Enable phone location", 0xffe6f4f8, TEAL);
        request.setOnClickListener(view -> actions.requestPermissions());
        gps.addView(request);
    }

    private void renderChat() {
        content.removeAllViews();
        content.addView(text("Gemini AI", 25, INK, true));
        content.addView(text("Ask about the readings or recent trends.", 14, MUTED, false));
        space(content, 14);
        LinearLayout notice = card();
        notice.addView(text("AI guidance is informational, not an official warning. Follow local authorities during an emergency.", 12, MUTED, false));
        content.addView(notice);
        LinearLayout thread = new LinearLayout(getContext());
        thread.setOrientation(VERTICAL);
        thread.setPadding(0, dp(8), 0, dp(8));
        for (ChatMessage message : chatMessages) addChatBubble(thread, message);
        if (chatMessages.isEmpty()) addChatBubble(thread, new ChatMessage(false,
                "Hello! I can summarize recent saved readings. Add your Gemini key in Settings and connect to the internet to begin."));
        content.addView(thread);

        LinearLayout suggestions = new LinearLayout(getContext());
        suggestions.setGravity(Gravity.CENTER_VERTICAL);
        Button trends = button(getContext(), "Analyze Trends", SURFACE, INK);
        trends.setBackground(rounded(SURFACE, BORDER, 18));
        trends.setOnClickListener(view -> submitQuestion(
                "Analyze the recent saved sensor trends in timestamp order. Identify changes and missing readings."));
        Button summary = button(getContext(), "Request Summary", SURFACE, INK);
        summary.setBackground(rounded(SURFACE, BORDER, 18));
        summary.setOnClickListener(view -> submitQuestion(
                "Summarize the latest confirmed sensor readings and current risk status."));
        suggestions.addView(trends, new LayoutParams(0, dp(38), 1));
        LayoutParams summaryParams = new LayoutParams(0, dp(38), 1);
        summaryParams.leftMargin = dp(7);
        suggestions.addView(summary, summaryParams);
        content.addView(suggestions);

        LinearLayout inputRow = new LinearLayout(getContext());
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(getContext());
        chatInput = input;
        input.setSingleLine(true);
        input.setHint("Ask about your sensor data…");
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        if (!pendingVoiceText.isEmpty()) {
            input.setText(pendingVoiceText);
            input.setSelection(input.length());
            pendingVoiceText = "";
        }
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(rounded(0xffffead0, BORDER, 22));
        input.setPadding(dp(15), 0, dp(15), 0);
        inputRow.addView(input, new LayoutParams(0, dp(48), 1));
        Button mic = button(getContext(), "Mic", SURFACE, TEAL);
        mic.setContentDescription("Speak a question about sensor data");
        mic.setOnClickListener(view -> actions.openVoiceInput());
        LayoutParams micParams = new LayoutParams(dp(52), dp(46));
        micParams.leftMargin = dp(6);
        inputRow.addView(mic, micParams);
        Button send = button(getContext(), loadingChat ? "…" : "Send", TEAL, 0xffffffff);
        LinearLayout.LayoutParams sendParams = new LayoutParams(dp(64), dp(46));
        sendParams.leftMargin = dp(8);
        inputRow.addView(send, sendParams);
        Runnable submit = () -> {
            String question = input.getText().toString().trim();
            if (!question.isEmpty()) submitQuestion(question);
        };
        send.setOnClickListener(view -> submit.run());
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit.run(); return true; }
            return false;
        });
        content.addView(inputRow);
        TextView settings = text("Configure Gemini key", 12, TEAL, true);
        settings.setPadding(dp(2), dp(14), 0, dp(8));
        settings.setOnClickListener(view -> actions.openSettings());
        content.addView(settings);
    }

    private void submitQuestion(String question) {
        if (loadingChat) return;
        loadingChat = true;
        chatMessages.add(new ChatMessage(true, question));
        chatMessages.add(new ChatMessage(false, "Connecting to Gemini…"));
        renderChat();
        try {
            JSONObject request = new JSONObject();
            request.put("message", question);
            JSONArray prior = new JSONArray();
            int firstPriorMessage = Math.max(0, chatMessages.size() - 14);
            for (int i = firstPriorMessage; i < chatMessages.size() - 2; i++) {
                ChatMessage item = chatMessages.get(i);
                JSONObject entry = new JSONObject();
                entry.put("role", item.user ? "user" : "model");
                entry.put("text", item.text);
                prior.put(entry);
            }
            request.put("history", prior);
            request.put("telemetry", buildTelemetryContext());
            actions.sendGemini(request, (response, error) -> {
                loadingChat = false;
                if (!chatMessages.isEmpty()) chatMessages.remove(chatMessages.size() - 1);
                chatMessages.add(new ChatMessage(false, error == null ? response : error));
                if (chatMessages.size() > 30) chatMessages.subList(0, chatMessages.size() - 30).clear();
                if ("ai".equals(screen)) renderChat();
            });
        } catch (JSONException error) {
            loadingChat = false;
            chatMessages.remove(chatMessages.size() - 1);
            chatMessages.add(new ChatMessage(false, "Could not prepare the analysis request."));
            renderChat();
        }
    }

    private JSONObject buildTelemetryContext() throws JSONException {
        JSONObject telemetry = new JSONObject();
        boolean has = telemetryAvailable || demo;
        telemetry.put("risk_level", has ? risk() : "UNAVAILABLE");
        telemetry.put("risk_score", latest.optDouble("risk_score", 0));
        telemetry.put("factor_of_safety", latest.optDouble("factor_of_safety", 0));
        telemetry.put("predicted_level", latest.optString("predicted_level_30m", "UNKNOWN"));
        telemetry.put("predicted_score", latest.optDouble("predicted_score_30m", 0));
        telemetry.put("creep_status", latest.optString("creep_status", "UNKNOWN"));
        telemetry.put("trend", latest.optString("risk_trend", "UNKNOWN"));
        telemetry.put("rain_intensity", latest.optDouble("rain_intensity", 0));
        telemetry.put("soil_moisture", latest.optDouble("soil_moisture_avg", 0));
        telemetry.put("tilt", latest.optDouble("max_tilt", 0));
        telemetry.put("tilt_velocity", latest.optDouble("tilt_velocity", 0));
        telemetry.put("data_status", demo ? "DEMO" : stale ? "STALE" : has ? "LIVE" : "UNAVAILABLE");
        JSONArray recent = new JSONArray();
        int start = Math.max(0, history.length() - 20);
        for (int i = start; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row == null) continue;
            JSONObject sample = new JSONObject();
            sample.put("timestamp", row.optString("timestamp", ""));
            sample.put("source", demo ? "DEMO" : "LIVE");
            sample.put("risk_level", row.optString("risk_level", "UNKNOWN"));
            sample.put("risk_score", row.optDouble("risk_score", 0));
            sample.put("factor_of_safety", row.optDouble("factor_of_safety", 0));
            JSONObject status = row.optJSONObject("sensor_status");
            if (status != null && status.optBoolean("rain", false))
                sample.put("rain_intensity", row.optDouble("rain_intensity", 0));
            if (status != null && status.optBoolean("soil_1", false) && status.optBoolean("soil_2", false))
                sample.put("soil_moisture", row.optDouble("soil_moisture_avg", 0));
            if (status != null && (status.optBoolean("mpu1", false) || status.optBoolean("mpu2", false))) {
                sample.put("tilt", row.optDouble("max_tilt", 0));
                sample.put("tilt_velocity", row.optDouble("tilt_velocity", 0));
            }
            if (status != null && status.optBoolean("dht", false)) {
                sample.put("temperature", row.optDouble("temperature", 0));
                sample.put("humidity", row.optDouble("humidity", 0));
            }
            recent.put(sample);
        }
        telemetry.put("recent_history", recent);
        return telemetry;
    }

    private void generateHistoryAnalysis(boolean downloadPdf) {
        if (reportAnalysisLoading) return;
        if (history.length() == 0) {
            Toast.makeText(getContext(), "No saved sensor readings are available to analyze.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            reportAnalysisLoading = true;
            reportAnalysis = "";
            reportAnalysisError = "";
            renderReports();
            JSONObject request = new JSONObject();
            request.put("message",
                    "Write a concise sensor-history analysis for a downloadable safety report. " +
                    "Analyze the supplied recent readings in timestamp order. Describe observed changes " +
                    "in risk score and confirmed soil moisture, rainfall, tilt, and temperature readings; " +
                    "include the time span and sample count when available; identify missing or unverified " +
                    "sensor data. Distinguish observed measurements from interpretation. Do not invent " +
                    "readings, claim a landslide is certain, or say the site is safe. End with a clear " +
                    "statement that this screening is not a geotechnical assessment and advise following " +
                    "local emergency authorities.");
            request.put("history", new JSONArray());
            request.put("telemetry", buildTelemetryContext());
            actions.sendGemini(request, (response, error) -> {
                reportAnalysisLoading = false;
                reportAnalysis = error == null ? response : "";
                reportAnalysisError = error == null ? "" : error;
                if ("reports".equals(screen)) renderReports();
                if (downloadPdf && error == null) {
                    actions.savePdf(buildReport() + "\n\nAI HISTORICAL ANALYSIS\n\n" + response
                            + "\n\nGenerated with Gemini from up to 20 recent saved readings.");
                }
            });
        } catch (JSONException error) {
            reportAnalysisLoading = false;
            reportAnalysisError = "Could not prepare the history analysis.";
            renderReports();
        }
    }

    private void renderReports() {
        content.removeAllViews();
        content.addView(text("Reports", 25, INK, true));
        content.addView(text("Analyze readings, create a PDF, or export the raw history.", 14, MUTED, false));
        space(content, 14);
        LinearLayout summary = card();
        summary.addView(text("CURRENT SUMMARY", 11, MUTED, true));
        addKeyValue(summary, "Data source", demo ? "DEMO · simulated" : stale ? "Stale" : telemetryAvailable ? "ESP32 live" : "No live data");
        addKeyValue(summary, "Current risk", telemetryAvailable || demo ? risk() : "Unavailable");
        addKeyValue(summary, "Saved readings", String.valueOf(history.length()));
        addKeyValue(summary, "Location", gpsLocationText());
        content.addView(summary);
        Button pdf = button(getContext(), "Save PDF report", TEAL, 0xffffffff);
        pdf.setOnClickListener(view -> actions.savePdf(buildReport()));
        addButton(content, pdf);
        Button csv = button(getContext(), "Export sensor history (CSV)", 0xffe8f4f3, TEAL);
        csv.setOnClickListener(view -> {
            if (history.length() == 0) {
                Toast.makeText(getContext(), "No saved sensor history to export.", Toast.LENGTH_LONG).show();
                return;
            }
            actions.saveCsv(buildCsv());
        });
        addButton(content, csv);
        section("AI history analysis");
        LinearLayout analysis = card();
        analysis.addView(text("Analyze up to 20 recent saved readings using Gemini. The report includes observed trends and data-quality limits.", 13, MUTED, false));
        Button analyze = button(getContext(), reportAnalysisLoading ? "Analyzing…" : "Analyze sensor history", TEAL, 0xffffffff);
        analyze.setEnabled(!reportAnalysisLoading);
        analyze.setOnClickListener(view -> generateHistoryAnalysis(false));
        addButton(analysis, analyze);
        Button downloadAnalysis = button(getContext(),
                reportAnalysisLoading ? "Preparing fresh AI report…" : "Generate fresh AI report and download PDF",
                0xffe8f4f3, TEAL);
        downloadAnalysis.setEnabled(!reportAnalysisLoading);
        downloadAnalysis.setOnClickListener(view -> generateHistoryAnalysis(true));
        addButton(analysis, downloadAnalysis);
        if (reportAnalysisLoading) {
            analysis.addView(text("Preparing the historical analysis…", 13, MUTED, false));
        } else if (!reportAnalysisError.isEmpty()) {
            analysis.addView(text(reportAnalysisError, 13, RED, false));
        } else if (!reportAnalysis.isEmpty()) {
            TextView result = text(reportAnalysis, 13, INK, false);
            result.setPadding(0, dp(10), 0, dp(8));
            analysis.addView(result);
        }
        content.addView(analysis);
        Button whatsapp = button(getContext(), "Configure alert delivery", SURFACE, INK);
        whatsapp.setOnClickListener(view -> actions.openSettings());
        addButton(content, whatsapp);
    }

    private String buildReport() {
        StringBuilder report = new StringBuilder("DRISHTI SETU — SENSOR SAFETY REPORT\n")
                .append("Generated: ").append(DateFormat.getDateTimeInstance().format(new Date())).append('\n')
                .append("Data source: ").append(demo ? "DEMO (simulated)" : stale ? "STALE" : telemetryAvailable ? "LIVE ESP32" : "NO LIVE DATA").append('\n')
                .append("Current risk: ").append(telemetryAvailable || demo ? risk() : "Unavailable").append('\n')
                .append("Risk score: ").append(numberText("risk_score", "%", 1)).append('\n')
                .append("Factor of safety: ").append(numberText("factor_of_safety", "", 2)).append('\n')
                .append("Soil moisture: ").append(numberText("soil_moisture_avg", "%", 1)).append('\n')
                .append("Rain sensor output: ").append(numberText("rain_intensity", "%", 1)).append('\n')
                .append("Max tilt: ").append(numberText("max_tilt", "°", 1)).append('\n')
                .append("Forecast: ").append(forecast()).append('\n')
                .append("GPS: ").append(gpsLocationText()).append('\n')
                .append("Samples in local history: ").append(history.length()).append('\n')
                .append("\nSensor screening is not a geotechnical assessment. Follow local emergency guidance.");
        return report.toString();
    }

    private String buildMedicalReport() {
        return "DRISHTI SETU — PRIVATE MEDICAL HISTORY\n"
                + "Generated: " + DateFormat.getDateTimeInstance().format(new Date()) + '\n'
                + "Patient: User\n\n"
                + medicalSummary
                + "\n\nThis document contains private health information. Store and share it carefully. "
                + "It is a user-provided emergency reference, not a medical diagnosis.";
    }

    private String buildCsv() {
        String[] headers = {"timestamp", "risk_level", "risk_score", "soil_1_percent", "soil_2_percent",
                "rain_percent", "temperature_c", "humidity_percent", "max_tilt_degrees", "factor_of_safety",
                "latitude", "longitude", "altitude_m", "satellites", "sensor_status"};
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(csv(headers[i]));
        }
        csv.append("\r\n");
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row == null) continue;
            JSONObject status = row.optJSONObject("sensor_status");
            String[] values = {
                    row.optString("timestamp", ""), row.optString("risk_level", ""), value(row, "risk_score"),
                    status != null && status.optBoolean("soil_1", false) ? value(row, "soil_moisture_1") : "",
                    status != null && status.optBoolean("soil_2", false) ? value(row, "soil_moisture_2") : "",
                    status != null && status.optBoolean("rain", false) ? value(row, "rain_intensity") : "",
                    status != null && status.optBoolean("dht", false) ? value(row, "temperature") : "",
                    status != null && status.optBoolean("dht", false) ? value(row, "humidity") : "",
                    value(row, "max_tilt"), value(row, "factor_of_safety"),
                    status != null && status.optBoolean("gps", false) ? value(row, "latitude") : "",
                    status != null && status.optBoolean("gps", false) ? value(row, "longitude") : "",
                    status != null && status.optBoolean("gps", false) ? value(row, "altitude") : "",
                    status != null && status.optBoolean("gps", false) ? value(row, "satellites") : "",
                    status == null ? "" : status.toString()
            };
            for (int j = 0; j < values.length; j++) {
                if (j > 0) csv.append(',');
                csv.append(csv(values[j]));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private void renderMore() {
        content.removeAllViews();
        content.addView(text("More", 25, INK, true));
        content.addView(text("Tools and app settings", 14, MUTED, false));
        space(content, 12);
        addActionCard("Slope illustration", "Conceptual profile, not surveyed 3D terrain", TEAL, () -> showScreen("twin"));
        addActionCard("Map and coordinates", "Phone GPS fallback · Google Earth 3D satellite", 0xff2879a5, () -> showScreen("map"));
        addActionCard("Risk alerts", "Review recent sensor risk events", RED, () -> showScreen("alerts"));
        addActionCard("Medical history", "Private medical card and nearby emergency-service search", RED, () -> showScreen("medical"));
        addActionCard("Reports and exports", "AI history analysis, downloadable PDF and CSV", GREEN, () -> showScreen("reports"));
        addActionCard("Settings and permissions", "Gemini, WhatsApp, location, notifications", 0xff6d55a5, () -> showScreen("settings"));
    }

    private void renderAlerts() {
        content.removeAllViews();
        content.addView(text("Risk alerts", 25, INK, true));
        content.addView(text("Recent events recorded by the local sensor service.", 14, MUTED, false));
        space(content, 14);
        if (alerts.length() == 0) {
            LinearLayout empty = card();
            empty.addView(text("No risk alerts have been recorded yet.", 13, MUTED, false));
            content.addView(empty);
            return;
        }
        for (int i = alerts.length() - 1; i >= 0; i--) {
            JSONObject item = alerts.optJSONObject(i);
            if (item == null) continue;
            String level = item.optString("level", "ALERT").toUpperCase(Locale.ROOT);
            int color = riskColor(level);
            LinearLayout alert = card();
            alert.addView(text(level + " RISK", 14, color, true));
            TextView message = text(item.optString("message", "Risk event recorded."), 13, INK, false);
            message.setPadding(0, dp(6), 0, dp(5));
            alert.addView(message);
            alert.addView(text(item.optString("timestamp", "Time unavailable"), 11, MUTED, false));
            content.addView(alert);
        }
    }

    private void renderSettings() {
        content.removeAllViews();
        content.addView(text("Settings", 25, INK, true));
        content.addView(text("Secure service configuration", 14, MUTED, false));
        space(content, 14);
        LinearLayout keyCard = card();
        keyCard.addView(text("AI and alert providers", 16, INK, true));
        keyCard.addView(text("Gemini requires an API key from Google AI Studio and an internet connection. Provider credentials are encrypted on this device.", 13, MUTED, false));
        Button providers = button(getContext(), "Configure API keys", TEAL, 0xffffffff);
        addButton(keyCard, providers);
        providers.setOnClickListener(view -> actions.openSettings());
        content.addView(keyCard);
        LinearLayout permissions = card();
        permissions.addView(text("Android permissions", 16, INK, true));
        permissions.addView(text("Location lets the app use ESP32 GPS or fall back to the phone's GPS/network fix. Microphone access enables voice questions to Gemini; speech recognition is provided by Android. Notifications show risk alerts. SMS permission is requested only when you send an SOS.", 13, MUTED, false));
        Button grant = button(getContext(), "Review permissions", 0xffe8f4f3, TEAL);
        addButton(permissions, grant);
        grant.setOnClickListener(view -> actions.requestPermissions());
        content.addView(permissions);
        LinearLayout reports = card();
        reports.addView(text("AI analysis and report options", 16, INK, true));
        reports.addView(text("Historical analysis uses the 20 most recent saved readings and requires a Gemini API key. PDF and CSV files are saved using Android's document picker.", 13, MUTED, false));
        Button openReports = button(getContext(), "Open reports and analysis", SURFACE, TEAL);
        addButton(reports, openReports);
        openReports.setOnClickListener(view -> showScreen("reports"));
        content.addView(reports);
    }

    private void renderMap() {
        content.removeAllViews();
        content.addView(text("Map and location", 25, INK, true));
        content.addView(text("Live location on an in-app street map. Drag to explore, pinch or use zoom controls.", 14, MUTED, false));
        space(content, 14);
        LinearLayout map = card();
        map.setPadding(dp(8), dp(8), dp(8), dp(8));
        liveMapView = new LiveMapView(getContext());
        map.addView(liveMapView, new LayoutParams(LayoutParams.MATCH_PARENT, dp(300)));
        content.addView(map);
        updateLiveMapLocation();
        LinearLayout controls = new LinearLayout(getContext());
        controls.setGravity(Gravity.CENTER);
        Button center = button(getContext(), "My location", TEAL, 0xffffffff);
        center.setOnClickListener(view -> {
            if (!Double.isFinite(latitude()) || !Double.isFinite(longitude())) {
                actions.requestPermissions();
                return;
            }
            liveMapView.centerOnLocation();
        });
        controls.addView(center, new LayoutParams(0, dp(46), 2));
        Button zoomIn = button(getContext(), "Zoom +", 0xffe6f4f8, TEAL);
        zoomIn.setOnClickListener(view -> liveMapView.zoomIn());
        LayoutParams zoomParams = new LayoutParams(0, dp(46), 1);
        zoomParams.leftMargin = dp(6);
        controls.addView(zoomIn, zoomParams);
        Button zoomOut = button(getContext(), "Zoom -", 0xffe6f4f8, TEAL);
        zoomOut.setOnClickListener(view -> liveMapView.zoomOut());
        LayoutParams zoomOutParams = new LayoutParams(0, dp(46), 1);
        zoomOutParams.leftMargin = dp(6);
        controls.addView(zoomOut, zoomOutParams);
        content.addView(controls);
        Button retry = button(getContext(), "Retry map tiles", SURFACE, INK);
        retry.setOnClickListener(view -> liveMapView.retryFailedTiles());
        addButton(content, retry);
        addGpsSourceLabel(content);
        addLocationLabel(content);
        content.addView(text("Map tiles © OpenStreetMap contributors; an internet connection is required. Location follows the ESP32 GPS fix when available, otherwise the phone GPS/network location. Satellite and Google Earth views open external apps.", 12, MUTED, false));
        Button earth = button(getContext(), "Open Google Earth 3D satellite", TEAL, 0xffffffff);
        earth.setOnClickListener(view -> actions.openEarth(latitude(), longitude()));
        addButton(content, earth);
        Button satellite = button(getContext(), "Open satellite map", 0xffe6f4f8, TEAL);
        satellite.setOnClickListener(view -> actions.openMap(latitude(), longitude()));
        addButton(content, satellite);
        section("Location permissions");
        Button request = button(getContext(), "Enable phone location", 0xffe8f4f3, TEAL);
        request.setOnClickListener(view -> actions.requestPermissions());
        addButton(content, request);
    }

    private void updateLiveMapLocation() {
        if (liveMapView == null) return;
        double lat = latitude();
        double lon = longitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            liveMapView.clearLocation();
            return;
        }
        float accuracy = phoneAccuracyMeters;
        JSONObject status = latest.optJSONObject("sensor_status");
        if (telemetryAvailable && status != null && status.optBoolean("gps", false)
                && latest.optInt("satellites", 0) > 0) {
            accuracy = (float) latest.optDouble("gps_accuracy", 0);
        }
        liveMapView.setLocation(lat, lon, accuracy);
    }

    private void renderMedicalHistory() {
        content.removeAllViews();
        content.addView(text("Medical history", 25, INK, true));
        content.addView(text("A private emergency medical card stored on this device.", 14, MUTED, false));
        space(content, 14);
        LinearLayout medical = card();
        medical.addView(text(medicalSummary, 14, INK, false));
        content.addView(medical);
        TextView sharing = text(shareMedicalWithSos
                ? "Medical details are opted in for SOS messages to your configured contact."
                : "Medical details are private and will not be included in SOS unless you opt in.",
                12, shareMedicalWithSos ? AMBER : MUTED, true);
        content.addView(sharing);
        Button edit = button(getContext(), "Edit medical history", TEAL, 0xffffffff);
        edit.setOnClickListener(view -> actions.editMedicalHistory());
        addButton(content, edit);
        Button download = button(getContext(), "Download medical history as PDF", 0xffe8f4f3, TEAL);
        download.setOnClickListener(view -> actions.savePdf(buildMedicalReport()));
        addButton(content, download);
        content.addView(text("Find nearby services", 17, INK, true));
        content.addView(text("These buttons open a map search. The app cannot identify or automatically message nearby hospitals or police without a verified emergency-service directory.", 12, MUTED, false));
        Button hospitals = button(getContext(), "Find nearby hospitals", 0xffe6f4f8, TEAL);
        hospitals.setOnClickListener(view ->
                actions.openNearbyFacilities("hospitals", latitude(), longitude()));
        addButton(content, hospitals);
        Button police = button(getContext(), "Find nearby police stations", 0xffe6f4f8, TEAL);
        police.setOnClickListener(view ->
                actions.openNearbyFacilities("police stations", latitude(), longitude()));
        addButton(content, police);
        content.addView(text("SOS goes only to the phone number configured in Settings. SMS requires Android permission; WhatsApp click-to-chat still needs you to tap Send unless an approved WhatsApp Business API is configured.", 12, MUTED, false));
    }

    private void renderTwin() {
        content.removeAllViews();
        content.addView(text("Slope illustration", 25, INK, true));
        content.addView(text("Conceptual profile · not surveyed 3D terrain", 14, MUTED, false));
        space(content, 14);
        LinearLayout slope = card();
        TwinView twin = new TwinView(getContext());
        slope.addView(twin, new LayoutParams(LayoutParams.MATCH_PARENT, dp(220)));
        content.addView(slope);
        section("Station summary");
        LinearLayout values = card();
        addKeyValue(values, "Active station", "ESP32");
        addKeyValue(values, "Risk level", telemetryAvailable || demo ? risk() : "No fresh data");
        addKeyValue(values, "Soil moisture", value("soil_moisture_avg", "%", 1));
        addKeyValue(values, "Maximum tilt", value("max_tilt", "°", 1));
        content.addView(values);
    }

    private void addReadingRow(JSONObject row) {
        LinearLayout card = card();
        String timestamp = row.optString("timestamp", "Time unavailable").replace('T', ' ');
        if (timestamp.endsWith("Z")) timestamp = timestamp.substring(0, timestamp.length() - 1);
        card.addView(text(timestamp, 12, MUTED, true));
        JSONObject status = row.optJSONObject("sensor_status");
        String soil1 = status != null && status.optBoolean("soil_1", false) ? value(row, "soil_moisture_1") + "%" : "—";
        String rain = status != null && status.optBoolean("rain", false) ? value(row, "rain_intensity") + "%" : "—";
        card.addView(text("Soil " + soil1 + "   ·   Rain " + rain + "   ·   Risk " + row.optString("risk_level", "—"), 13, INK, false));
        boolean gps = status != null && status.optBoolean("gps", false) && row.optInt("satellites", 0) > 0;
        card.addView(text(gps ? String.format(Locale.US, "GPS %.5f, %.5f", row.optDouble("latitude"), row.optDouble("longitude"))
                : "GPS fix unavailable", 11, gps ? TEAL : MUTED, false));
        content.addView(card);
    }

    private void addChatBubble(LinearLayout parent, ChatMessage message) {
        TextView bubble = text(message.text, 14, INK, false);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        int bubbleColor = message.user ? 0xffffe2b8 : 0xffd8eaf5;
        bubble.setBackground(rounded(bubbleColor, bubbleColor, 17));
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(message.user ? Gravity.RIGHT : Gravity.LEFT);
        row.addView(bubble, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        parent.addView(row, params);
    }

    private void addMetric(LinearLayout parent, String title, String value, int color) {
        LinearLayout metric = new LinearLayout(getContext());
        metric.setOrientation(VERTICAL);
        metric.setGravity(Gravity.CENTER);
        metric.setPadding(dp(3), dp(5), dp(3), dp(5));
        String icon = title.startsWith("Soil") ? "◉" : title.startsWith("Rain") ? "☂" : "♨";
        TextView symbol = text(icon, 19, color, true);
        symbol.setGravity(Gravity.CENTER);
        metric.addView(symbol);
        metric.addView(text(title, 10, MUTED, false));
        TextView number = text(value, 16, color, true);
        number.setGravity(Gravity.CENTER);
        metric.addView(number);
        parent.addView(metric, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
    }

    private void addQuickAccess(LinearLayout parent, String icon, String label, String target) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(10), dp(4), dp(10));
        item.setBackground(rounded(TEAL, TEAL, 16));
        item.setFocusable(true);
        item.setClickable(true);
        item.setContentDescription(label.replace('\n', ' ') + " shortcut");
        TextView symbol = text(icon, 23, 0xffffffff, true);
        symbol.setGravity(Gravity.CENTER);
        item.addView(symbol);
        TextView title = text(label, 12, 0xffffffff, true);
        title.setGravity(Gravity.CENTER);
        item.addView(title);
        item.setOnClickListener(view -> showScreen(target));
        LayoutParams params = new LayoutParams(0, dp(94), 1);
        params.rightMargin = dp(7);
        parent.addView(item, params);
    }

    private void addHistoryChart(String seriesKey, String title) {
        LinearLayout chartCard = card();
        chartCard.addView(text(title, 15, INK, true));
        TextView note = text(demo ? "DEMO values are simulated" : "Confirmed readings · actual sample times", 11,
                demo ? AMBER : MUTED, false);
        chartCard.addView(note);
        ReadingChart readingChart = new ReadingChart(getContext(), seriesKey);
        LayoutParams chartParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(174));
        chartParams.topMargin = dp(8);
        chartCard.addView(readingChart, chartParams);
        content.addView(chartCard);
    }

    private void addSensorChoice(LinearLayout parent, String key, String label) {
        boolean selected = chartKey.equals(key);
        Button choice = button(getContext(), label, selected ? TEAL : SURFACE,
                selected ? 0xffffffff : INK);
        choice.setOnClickListener(view -> {
            int scrollY = scroll.getScrollY();
            chartKey = selected ? "" : key;
            renderSensors();
            scroll.post(() -> scroll.scrollTo(0, scrollY));
        });
        LayoutParams params = new LayoutParams(0, dp(40), 1);
        params.rightMargin = dp(7);
        parent.addView(choice, params);
    }

    private void addActionCard(String title, String subtitle, int color, Runnable action) {
        LinearLayout item = card();
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x22087e83), rounded(SURFACE, BORDER, 18), null));
        TextView heading = text(title, 16, color, true);
        item.addView(heading);
        TextView detail = text(subtitle, 12, MUTED, false);
        detail.setPadding(0, dp(4), 0, dp(8));
        item.addView(detail);
        item.setOnClickListener(view -> action.run());
        content.addView(item);
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(key, 13, MUTED, false);
        TextView right = text(value, 13, INK, true);
        right.setGravity(Gravity.RIGHT);
        row.addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        row.addView(right);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(9);
        parent.addView(row, params);
    }

    private void addLocationLabel(LinearLayout parent) {
        visibleLocationLabel = text(locationText, 12, MUTED, false);
        parent.addView(visibleLocationLabel);
    }

    private void addGpsSourceLabel(LinearLayout parent) {
        visibleGpsSourceLabel = text(gpsLocationText(), 13, INK, false);
        parent.addView(visibleGpsSourceLabel);
    }

    private void addButton(LinearLayout parent, Button button) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(12);
        parent.addView(button, params);
    }

    private void section(String title) {
        TextView heading = text(title, 16, INK, true);
        heading.setPadding(dp(2), dp(17), dp(2), dp(8));
        content.addView(heading);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(rounded(SURFACE, BORDER, 18));
        card.setElevation(dp(1));
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private String statusTitle() {
        if (demo) return "DEMO · " + risk();
        if (stale) return "DATA IS STALE";
        if (!telemetryAvailable) return "WAITING FOR SENSOR";
        return risk() + " RISK";
    }

    private String advisory() {
        if (demo) return "This is simulated data, not a physical sensor measurement.";
        if (stale) return "The connection is stale. Wait for fresh ESP32 readings.";
        if (!telemetryAvailable) return "Connect to the sensor station to begin live monitoring.";
        if (latest.optInt("risk_code", 0) >= 2)
            return "Elevated risk detected. Move away from the slope and follow local emergency guidance.";
        return "Continue monitoring. This sensor screening is not an official safety assessment.";
    }

    private String forecast() {
        if (!telemetryAvailable && !demo) return "Unavailable";
        return latest.optString("predicted_level_30m", "LOW") + " · "
                + format(latest.optDouble("predicted_score_30m", 0), 0) + "%";
    }

    private String latestSampleTime() {
        String timestamp = latest.optString("timestamp", "");
        return timestamp.isEmpty() || "null".equalsIgnoreCase(timestamp)
                ? "Unavailable" : timestamp.replace('T', ' ').replace("Z", " UTC");
    }

    private int riskTint(int riskColor) {
        if (riskColor == RED) return 0xffffe6df;
        if (riskColor == AMBER) return 0xfffff0d7;
        if (riskColor == MUTED) return 0xffeff0ef;
        return 0xffdff2e3;
    }

    private String gpsLocationText() {
        JSONObject row = latest;
        if (telemetryAvailable && row.optJSONObject("sensor_status") != null
                && row.optJSONObject("sensor_status").optBoolean("gps", false)
                && row.optInt("satellites", 0) > 0) {
            return String.format(Locale.US, "ESP32 satellite fix  %.5f, %.5f",
                    row.optDouble("latitude"), row.optDouble("longitude"));
        }
        if (hasRecentPhoneLocation()) {
            return String.format(Locale.US, "Phone GPS fallback · ESP32 fix unavailable  %.5f, %.5f",
                    phoneLatitude, phoneLongitude);
        }
        return "Waiting for a phone or ESP32 satellite fix";
    }

    private boolean hasRecentPhoneLocation() {
        long ageNanos = SystemClock.elapsedRealtimeNanos() - phoneLocationElapsedNanos;
        return Double.isFinite(phoneLatitude) && Double.isFinite(phoneLongitude)
                && phoneLocationElapsedNanos > 0 && ageNanos >= 0
                && ageNanos <= 2L * 60L * 1_000_000_000L;
    }

    private String sensorValue(String key, String sensor, String unit, int decimals) {
        JSONObject status = latest.optJSONObject("sensor_status");
        if (!demo && (status == null || !status.optBoolean(sensor, false))) return "—";
        return value(key, unit, decimals);
    }

    private String soilMoistureValue() {
        JSONObject status = latest.optJSONObject("sensor_status");
        if (!demo && (status == null || !status.optBoolean("soil_1", false)
                || !status.optBoolean("soil_2", false))) return "—";
        return value("soil_moisture_avg", "%", 1);
    }

    private String value(String key, String unit, int decimals) {
        if (!demo && !telemetryAvailable) return "—";
        if (!latest.has(key) || latest.isNull(key)) return "—";
        return format(latest.optDouble(key, Double.NaN), decimals) + unit;
    }

    private String numberText(String key, String unit, int decimals) {
        return value(key, unit, decimals);
    }

    private String risk() {
        if (!telemetryAvailable && !demo) return "UNKNOWN";
        if (demo && !latest.has("risk_level")) return "LOW";
        String level = latest.optString("risk_level", "UNKNOWN").toUpperCase(Locale.ROOT);
        if (!level.equals("LOW") && !level.equals("MEDIUM") && !level.equals("HIGH") && !level.equals("CRITICAL"))
            return "UNKNOWN";
        return level;
    }

    private double latitude() {
        JSONObject status = latest.optJSONObject("sensor_status");
        if (status != null && status.optBoolean("gps", false) && latest.optInt("satellites", 0) > 0)
            return latest.optDouble("latitude", Double.NaN);
        return hasRecentPhoneLocation() ? phoneLatitude : Double.NaN;
    }

    private double longitude() {
        JSONObject status = latest.optJSONObject("sensor_status");
        if (status != null && status.optBoolean("gps", false) && latest.optInt("satellites", 0) > 0)
            return latest.optDouble("longitude", Double.NaN);
        return hasRecentPhoneLocation() ? phoneLongitude : Double.NaN;
    }

    private int riskColor(String level) {
        switch (level) {
            case "LOW": return GREEN;
            case "MEDIUM": return AMBER;
            case "HIGH":
            case "CRITICAL":
            case "WARNING":
            case "SOS": return RED;
            default: return MUTED;
        }
    }

    private String format(double value, int decimals) {
        if (!Double.isFinite(value)) return "—";
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private String value(JSONObject object, String key) {
        return object.has(key) && !object.isNull(key) ? String.valueOf(object.opt(key)) : "";
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private Button button(Context context, String label, int background, int foreground) {
        Button view = new Button(context);
        view.setText(label);
        view.setTextSize(12);
        view.setTextColor(foreground);
        view.setAllCaps(false);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x22000000),
                rounded(background, background == SURFACE ? BORDER : background, 22), null));
        view.setStateListAnimator(null);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable circle(int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private GradientDrawable pill(int fill, int stroke) {
        return rounded(fill, stroke, 20);
    }

    private View line(Context context, int color) {
        View line = new View(context);
        line.setBackgroundColor(color);
        return line;
    }

    private void space(LinearLayout parent, int height) {
        parent.addView(new View(getContext()), new LayoutParams(1, dp(height)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ReadingChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String seriesKey;
        private final Path linePath = new Path();
        private final Path areaPath = new Path();
        private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
        private final Date firstTime = new Date(0);
        private final Date lastTime = new Date(0);

        ReadingChart(Context context, String seriesKey) {
            super(context);
            this.seriesKey = seriesKey;
            setContentDescription("Timestamp-based sensor history chart");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(38), right = getWidth() - dp(12), top = dp(16), bottom = getHeight() - dp(28);
            paint.setColor(BORDER);
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i <= 3; i++) {
                float y = top + (bottom - top) * i / 3f;
                canvas.drawLine(left, y, right, y, paint);
            }
            List<Sample> samples = chartSamples();
            if (samples.size() < 2) {
                paint.setColor(MUTED);
                paint.setTextSize(dp(12));
                canvas.drawText(samples.isEmpty() ? "Waiting for confirmed readings" : "One sample · trend needs two readings",
                        left, top + dp(48), paint);
                return;
            }
            long first = samples.get(0).time, last = samples.get(samples.size() - 1).time;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (Sample sample : samples) { min = Math.min(min, sample.value); max = Math.max(max, sample.value); }
            if ("soil".equals(seriesKey) || "rain".equals(seriesKey)) {
                min = 0;
                max = 100;
            } else {
                if ("gps".equals(seriesKey) && min >= 0) min = 0;
                if (max == min) max = min + 1;
                else {
                    double padding = (max - min) * .12;
                    min -= padding;
                    max += padding;
                }
            }
            paint.setTextSize(dp(9));
            paint.setColor(MUTED);
            canvas.drawText(String.format(Locale.US, "%.1f", max), dp(2), top + dp(4), paint);
            canvas.drawText(String.format(Locale.US, "%.1f", min), dp(2), bottom, paint);
            linePath.reset();
            for (int i = 0; i < samples.size(); i++) {
                Sample sample = samples.get(i);
                float x = left + (last == first ? .5f : (sample.time - first) / (float) (last - first)) * (right - left);
                float y = bottom - (float) ((sample.value - min) / (max - min)) * (bottom - top);
                if (i == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
            }
            areaPath.set(linePath);
            areaPath.lineTo(right, bottom);
            areaPath.lineTo(left, bottom);
            areaPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33308fa5);
            canvas.drawPath(areaPath, paint);
            paint.setColor(TEAL);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(linePath, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TEAL);
            Sample latestSample = samples.get(samples.size() - 1);
            float x = right;
            float y = bottom - (float) ((latestSample.value - min) / (max - min)) * (bottom - top);
            canvas.drawCircle(x, y, dp(4), paint);
            paint.setTextSize(dp(9));
            paint.setColor(MUTED);
            firstTime.setTime(first);
            lastTime.setTime(last);
            String start = timeFormat.format(firstTime);
            String end = timeFormat.format(lastTime);
            canvas.drawText(start, left, getHeight() - dp(5), paint);
            float endWidth = paint.measureText(end);
            canvas.drawText(end, right - endWidth, getHeight() - dp(5), paint);
        }

        private List<Sample> chartSamples() {
            List<Sample> result = new ArrayList<>();
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row == null) continue;
                JSONObject status = row.optJSONObject("sensor_status");
                String key;
                switch (seriesKey) {
                    case "rain": key = "rain_intensity"; if (status == null || !status.optBoolean("rain", false)) continue; break;
                    case "temperature": key = "temperature"; if (status == null || !status.optBoolean("dht", false)) continue; break;
                    case "gps": key = "altitude"; if (status == null || !status.optBoolean("gps", false) || row.optInt("satellites", 0) < 1) continue; break;
                    default:
                        if (status == null || !status.optBoolean("soil_1", false) || !status.optBoolean("soil_2", false)) continue;
                        key = "soil_moisture_avg";
                }
                double value = row.optDouble(key, Double.NaN);
                String timestamp = row.optString("timestamp", "");
                long time = parseTimestamp(timestamp);
                if (time < 0) continue;
                if (Double.isFinite(value)) result.add(new Sample(time, value));
            }
            if (!result.isEmpty()) {
                long newest = result.get(0).time;
                for (Sample sample : result) newest = Math.max(newest, sample.time);
                long cutoff = newest - 24 * 60 * 60 * 1000L;
                result.removeIf(sample -> sample.time < cutoff);
            }
            return result;
        }

        private long parseTimestamp(String timestamp) {
            if (timestamp == null || timestamp.length() < 20) return -1;
            String normalized = timestamp;
            int zone = normalized.endsWith("Z") ? normalized.length() - 1 : -1;
            int dot = normalized.indexOf('.', 19);
            if (zone >= 0) {
                if (dot < 0) {
                    normalized = normalized.substring(0, zone) + ".000Z";
                } else {
                    String fraction = normalized.substring(dot + 1, zone);
                    String millis = (fraction + "000").substring(0, 3);
                    normalized = normalized.substring(0, dot) + "." + millis + "Z";
                }
            } else {
                return -1;
            }
            try {
                SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                parser.setLenient(false);
                parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date parsed = parser.parse(normalized);
                return parsed == null ? -1 : parsed.getTime();
            } catch (ParseException error) {
                return -1;
            }
        }
    }

    private static final class Sample {
        final long time;
        final double value;
        Sample(long time, double value) { this.time = time; this.value = value; }
    }

    private static final class ChatMessage {
        final boolean user;
        final String text;
        ChatMessage(boolean user, String text) { this.user = user; this.text = text; }
    }

    private final class TwinView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TwinView(Context context) { super(context); setContentDescription("Illustration of slope and sensor stations"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setColor(0xffe9f4f2);
            canvas.drawRoundRect(0, 0, w, h, dp(15), dp(15), paint);
            Path ground = new Path();
            ground.moveTo(0, h * .65f);
            ground.cubicTo(w * .25f, h * .55f, w * .4f, h * .25f, w * .62f, h * .35f);
            ground.cubicTo(w * .78f, h * .42f, w * .87f, h * .65f, w, h * .56f);
            ground.lineTo(w, h); ground.lineTo(0, h); ground.close();
            paint.setColor(0xffbddbc9);
            canvas.drawPath(ground, paint);
            Path surface = new Path();
            surface.moveTo(0, h * .65f);
            surface.cubicTo(w * .25f, h * .55f, w * .4f, h * .25f, w * .62f, h * .35f);
            surface.cubicTo(w * .78f, h * .42f, w * .87f, h * .65f, w, h * .56f);
            paint.setColor(GREEN); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
            canvas.drawPath(surface, paint); paint.setStyle(Paint.Style.FILL);
            float[] positions = {w * .33f, w * .6f, w * .82f};
            String[] labels = {"RIDGE", "ESP32", "VALLEY"};
            for (int i = 0; i < positions.length; i++) {
                float y = i == 0 ? h * .48f : i == 1 ? h * .39f : h * .55f;
                paint.setColor(i == 1 ? riskColor(risk()) : TEAL);
                canvas.drawCircle(positions[i], y, dp(7), paint);
                paint.setTextSize(dp(9)); paint.setColor(INK);
                canvas.drawText(labels[i], positions[i] - dp(16), y - dp(12), paint);
            }
        }
    }
}
