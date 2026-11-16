/*
 ==============================================================================
  GEO-GUARD AI: STANDALONE ESP32 ACCESS POINT & EMBEDDED EMERGENCY SERVER
 ==============================================================================
  Target Board:  ESP32 Dev Module (ESP32-WROOM-32)
  Wi-Fi Mode:    Standalone Access Point (SoftAP)
                 SSID:     GeoGuard_Landslide_AP
                 Password: 12345678
                 IP:       192.168.4.1

  Any phone, tablet, or laptop can connect to the ESP32's Wi-Fi network and open
  http://192.168.4.1 to view the live dashboard with zero external router or internet!

  Sensors:
    - FC-37 Raindrop Sensor:   AO -> GPIO 34 (ADC1)
    - Soil Moisture Sensor 1:  AO -> GPIO 35 (ADC1)
    - Soil Moisture Sensor 2:  AO -> GPIO 32 (ADC1)
    - MPU-6050 Inclinometer 1: SDA -> GPIO 21, SCL -> GPIO 22 (Address: 0x68)
    - MPU-6050 Inclinometer 2: SDA -> GPIO 21, SCL -> GPIO 22 (Address: 0x69, AD0 -> 3.3V)
    - DHT22 Temp & Humidity:   DATA -> GPIO 13
    - NEO-6M GPS Module:       TX -> GPIO 16 (RX2), RX -> GPIO 17 (TX2)
    - 0.96" SSD1306 OLED:      SDA -> GPIO 21, SCL -> GPIO 22 (Address: 0x3C)
  Actuators:
    - RGB LED (R/G/B):         GPIO 25 / 26 / 14 (220-ohm resistors, common cathode -> GND)
    - Active Buzzer (Siren):   GPIO 27
    - Water Pump Relay:        GPIO 33
 ==============================================================================
*/

#include <Wire.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <DHT.h>
#include <TinyGPSPlus.h>

// ==========================================
// 1. SOFT AP WI-FI CONFIGURATION
// ==========================================
const char* AP_SSID     = "GeoGuard_Landslide_AP";
const char* AP_PASSWORD = "12345678"; // At least 8 chars, or set to NULL for open

IPAddress local_IP(192, 168, 4, 1);
IPAddress gateway(192, 168, 4, 1);
IPAddress subnet(255, 255, 255, 0);

WebServer server(80);

// ==========================================
// 2. PIN DEFINITIONS
// ==========================================
#define PIN_SOIL_1       35
#define PIN_SOIL_2       32
#define PIN_RAIN         34
#define PIN_DHT          13
#define PIN_LED_RED      25
#define PIN_LED_GREEN    26
#define PIN_LED_BLUE     14
#define PIN_BUZZER       27
#define PIN_RELAY_PUMP   33

#define GPS_RX_PIN       16
#define GPS_TX_PIN       17

// ==========================================
// 3. HARDWARE OBJECTS & CONSTANTS
// ==========================================
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

#define DHTTYPE DHT22
DHT dht(PIN_DHT, DHTTYPE);

Adafruit_MPU6050 mpu1;
Adafruit_MPU6050 mpu2;
bool mpu1_found = false;
bool mpu2_found = false;

HardwareSerial gpsSerial(2);
TinyGPSPlus gps;

// Baseline reference angles
float mpu1_pitch_zero = 0.0, mpu1_roll_zero = 0.0;
float mpu2_pitch_zero = 0.0, mpu2_roll_zero = 0.0;

// Calibrate these 12-bit ADC counts against each installed probe and local soil.
const int SOIL_AIR_VALUE   = 3500;
const int SOIL_WATER_VALUE = 1400;
const int RAIN_DRY_VALUE   = 4095;
const int RAIN_WET_VALUE   = 1200;
const unsigned long SENSOR_SAMPLE_INTERVAL_MS = 2000;
const int ANALOG_OVERSAMPLE_COUNT = 5;

// System state
int soil1_raw = 0;
int soil2_raw = 0;
int rain_raw = 0;
float soil1_percent = 0.0;
float soil2_percent = 0.0;
float rain_percent  = 0.0;
float temp_c        = 25.0;
float humidity_p    = 60.0;
bool dht_available = false;
float mpu1_pitch    = 0.0;
float mpu1_roll     = 0.0;
float mpu2_pitch    = 0.0;
float mpu2_roll     = 0.0;
float max_tilt_dev  = 0.0;

int current_risk_code = 0; // 0: SAFE, 1: WARNING, 2: CRITICAL (HARMFUL)
float current_risk_score = 0.0;
bool pump_state = false;
bool buzzer_state = false;

unsigned long lastSensorRead = 0;
unsigned long lastMpu1Retry = 0;
unsigned long lastMpu2Retry = 0;
unsigned long lastDisplaySwitch = 0;
int displayScreen = 0;

// Forward declarations
void readAllSensors();
int readMedianAnalog(int pin);
float rawToPercent(int raw, int dryValue, int wetValue);
void calculateMPUAngles(Adafruit_MPU6050 &mpu, float &pitch, float &roll);
void evaluateSafety();
void updateActuators();
void updateOLED();
void handleRoot();
void handleTelemetryApi();
void handlePumpToggle();

// ==========================================
// SETUP
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n============================================");
  Serial.println("  GEOGUARD AI: ESP32 STANDALONE ACCESS POINT");
  Serial.println("============================================");

  // Initialize GPIO pins
  pinMode(PIN_SOIL_1, INPUT);
  pinMode(PIN_SOIL_2, INPUT);
  pinMode(PIN_RAIN, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_SOIL_1, ADC_11db);
  analogSetPinAttenuation(PIN_SOIL_2, ADC_11db);
  analogSetPinAttenuation(PIN_RAIN, ADC_11db);
  pinMode(PIN_LED_RED, OUTPUT);
  pinMode(PIN_LED_GREEN, OUTPUT);
  pinMode(PIN_LED_BLUE, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_RELAY_PUMP, OUTPUT);

  digitalWrite(PIN_LED_GREEN, HIGH);
  digitalWrite(PIN_LED_RED, LOW);
  digitalWrite(PIN_LED_BLUE, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_RELAY_PUMP, LOW);

  // I2C (SDA=21, SCL=22)
  Wire.begin(21, 22);

  // OLED Display (0x3C)
  if (display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    display.clearDisplay();
    display.setTextColor(WHITE);
    display.setTextSize(1);
    display.setCursor(10, 15);
    display.println("GeoGuard SoftAP");
    display.setCursor(10, 32);
    display.println("Starting Wi-Fi...");
    display.display();
  }

  // DHT22
  dht.begin();

  // Dual MPU6050
  if (mpu1.begin(0x68, &Wire)) {
    mpu1_found = true;
    mpu1.setAccelerometerRange(MPU6050_RANGE_4_G);
    mpu1.setFilterBandwidth(MPU6050_BAND_21_HZ);
    Serial.println("[+] MPU6050 #1 initialized (0x68)");
  }
  if (mpu2.begin(0x69, &Wire)) {
    mpu2_found = true;
    mpu2.setAccelerometerRange(MPU6050_RANGE_4_G);
    mpu2.setFilterBandwidth(MPU6050_BAND_21_HZ);
    Serial.println("[+] MPU6050 #2 initialized (0x69)");
  }

  // NEO-6M GPS on Serial2
  gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  // Calibrate baseline tilt
  Serial.println("[+] Calibrating zero-tilt baseline...");
  float p1_acc = 0, r1_acc = 0, p2_acc = 0, r2_acc = 0;
  for (int i = 0; i < 15; i++) {
    float p1, r1, p2, r2;
    if (mpu1_found) { calculateMPUAngles(mpu1, p1, r1); p1_acc += p1; r1_acc += r1; }
    if (mpu2_found) { calculateMPUAngles(mpu2, p2, r2); p2_acc += p2; r2_acc += r2; }
    delay(30);
  }
  if (mpu1_found) { mpu1_pitch_zero = p1_acc / 15; mpu1_roll_zero = r1_acc / 15; }
  if (mpu2_found) { mpu2_pitch_zero = p2_acc / 15; mpu2_roll_zero = r2_acc / 15; }

  // Start ESP32 Wi-Fi Access Point
  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(local_IP, gateway, subnet);
  WiFi.softAP(AP_SSID, AP_PASSWORD);

  Serial.println("\n[SUCCESS] Wi-Fi SoftAP Started!");
  Serial.print("[+] SSID:     "); Serial.println(AP_SSID);
  Serial.print("[+] Password: "); Serial.println(AP_PASSWORD);
  Serial.print("[+] Web URL:  http://"); Serial.println(WiFi.softAPIP());

  // Setup WebServer Routes
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/telemetry", HTTP_GET, handleTelemetryApi);
  server.on("/api/pump", HTTP_POST, handlePumpToggle);
  server.begin();
  Serial.println("[+] Embedded HTTP WebServer listening on port 80");
}

// ==========================================
// LOOP
// ==========================================
void loop() {
  server.handleClient();

  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }

  unsigned long currentMillis = millis();
  if (currentMillis - lastSensorRead >= SENSOR_SAMPLE_INTERVAL_MS) {
    lastSensorRead = currentMillis;
    readAllSensors();
    evaluateSafety();
    updateActuators();
  }

  if (currentMillis - lastDisplaySwitch >= 2500) {
    lastDisplaySwitch = currentMillis;
    displayScreen = (displayScreen + 1) % 3;
    updateOLED();
  }
}

// ==========================================
// SENSORS & SAFETY EVALUATION
// ==========================================
void readAllSensors() {
  unsigned long now = millis();
  if (!mpu1_found && now - lastMpu1Retry >= 5000) {
    lastMpu1Retry = now;
    if (mpu1.begin(0x68, &Wire)) {
      mpu1_found = true;
      mpu1.setAccelerometerRange(MPU6050_RANGE_4_G);
      mpu1.setFilterBandwidth(MPU6050_BAND_21_HZ);
      calculateMPUAngles(mpu1, mpu1_pitch_zero, mpu1_roll_zero);
      Serial.println("[+] MPU6050 #1 connected (0x68)");
    }
  }
  if (!mpu2_found && now - lastMpu2Retry >= 5000) {
    lastMpu2Retry = now;
    if (mpu2.begin(0x69, &Wire)) {
      mpu2_found = true;
      mpu2.setAccelerometerRange(MPU6050_RANGE_4_G);
      mpu2.setFilterBandwidth(MPU6050_BAND_21_HZ);
      calculateMPUAngles(mpu2, mpu2_pitch_zero, mpu2_roll_zero);
      Serial.println("[+] MPU6050 #2 connected (0x69)");
    }
  }

  soil1_raw = readMedianAnalog(PIN_SOIL_1);
  soil2_raw = readMedianAnalog(PIN_SOIL_2);
  rain_raw = readMedianAnalog(PIN_RAIN);
  soil1_percent = rawToPercent(soil1_raw, SOIL_AIR_VALUE, SOIL_WATER_VALUE);
  soil2_percent = rawToPercent(soil2_raw, SOIL_AIR_VALUE, SOIL_WATER_VALUE);
  rain_percent = rawToPercent(rain_raw, RAIN_DRY_VALUE, RAIN_WET_VALUE);

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  dht_available = isfinite(t) && isfinite(h) && t >= -40.0 && t <= 80.0 && h >= 0.0 && h <= 100.0;
  if (dht_available) {
    temp_c = t;
    humidity_p = h;
  }

  float p1 = 0, r1 = 0, p2 = 0, r2 = 0;
  if (mpu1_found) {
    calculateMPUAngles(mpu1, p1, r1);
    mpu1_pitch = abs(p1 - mpu1_pitch_zero);
    mpu1_roll  = abs(r1 - mpu1_roll_zero);
  }
  if (mpu2_found) {
    calculateMPUAngles(mpu2, p2, r2);
    mpu2_pitch = abs(p2 - mpu2_pitch_zero);
    mpu2_roll  = abs(r2 - mpu2_roll_zero);
  }
  max_tilt_dev = max(max(mpu1_pitch, mpu1_roll), max(mpu2_pitch, mpu2_roll));
}

int readMedianAnalog(int pin) {
  int samples[ANALOG_OVERSAMPLE_COUNT];
  for (int i = 0; i < ANALOG_OVERSAMPLE_COUNT; i++) {
    samples[i] = analogRead(pin);
    delay(2);
  }
  for (int i = 1; i < ANALOG_OVERSAMPLE_COUNT; i++) {
    int value = samples[i];
    int j = i - 1;
    while (j >= 0 && samples[j] > value) {
      samples[j + 1] = samples[j];
      j--;
    }
    samples[j + 1] = value;
  }
  return samples[ANALOG_OVERSAMPLE_COUNT / 2];
}

float rawToPercent(int raw, int dryValue, int wetValue) {
  float percent = 100.0 * (dryValue - raw) / (dryValue - wetValue);
  return constrain(percent, 0.0, 100.0);
}

void calculateMPUAngles(Adafruit_MPU6050 &mpu, float &pitch, float &roll) {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  pitch = atan2(-a.acceleration.x, sqrt(a.acceleration.y * a.acceleration.y + a.acceleration.z * a.acceleration.z)) * 180.0 / PI;
  roll  = atan2(a.acceleration.y, a.acceleration.z) * 180.0 / PI;
}

void evaluateSafety() {
  float avg_soil = (soil1_percent + soil2_percent) / 2.0;

  // HARMFUL / CRITICAL DANGER: Large tilt (>9 deg) or heavy saturation + rain
  if (max_tilt_dev >= 9.0 || (avg_soil >= 80.0 && rain_percent >= 65.0)) {
    current_risk_code = 2; // CRITICAL / HARMFUL
    current_risk_score = 95.0 + (max_tilt_dev * 0.5);
    if (current_risk_score > 100.0) current_risk_score = 100.0;
    pump_state = true;
    buzzer_state = true;
  }
  // ELEVATED RISK / WARNING
  else if (max_tilt_dev >= 3.0 || avg_soil >= 60.0 || rain_percent >= 45.0) {
    current_risk_code = 1; // WARNING
    current_risk_score = 45.0 + (avg_soil * 0.3);
    pump_state = (avg_soil >= 70.0 || rain_percent >= 55.0);
    buzzer_state = false;
  }
  // SAFE
  else {
    current_risk_code = 0; // SAFE
    current_risk_score = 5.0 + (avg_soil * 0.1);
    pump_state = false;
    buzzer_state = false;
  }
}

void updateActuators() {
  digitalWrite(PIN_LED_GREEN, (current_risk_code <= 1) ? HIGH : LOW);
  digitalWrite(PIN_LED_RED,   (current_risk_code >= 1) ? HIGH : LOW);
  digitalWrite(PIN_LED_BLUE, LOW);

  digitalWrite(PIN_BUZZER, buzzer_state ? HIGH : LOW);

  digitalWrite(PIN_RELAY_PUMP, pump_state ? HIGH : LOW);
}

void updateOLED() {
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.print("AP: GeoGuard");
  display.setCursor(80, 0);
  if (current_risk_code == 2) display.print("[DANGER]");
  else if (current_risk_code == 1) display.print("[WARN]");
  else display.print("[SAFE]");
  display.drawLine(0, 9, 128, 9, WHITE);

  if (displayScreen == 0) {
    display.setCursor(0, 14); display.printf("Soil #1: %.0f%%", soil1_percent);
    display.setCursor(0, 26); display.printf("Soil #2: %.0f%%", soil2_percent);
    display.setCursor(0, 38); display.printf("Rain: %.0f%%", rain_percent);
    display.setCursor(0, 50); display.printf("IP: 192.168.4.1");
  } else if (displayScreen == 1) {
    display.setCursor(0, 14); display.printf("Tilt Max: %.1f deg", max_tilt_dev);
    display.setCursor(0, 26); display.printf("Risk: %.1f%%", current_risk_score);
    display.setCursor(0, 38); display.printf("Pump: %s", pump_state ? "ACTIVE" : "OFF");
    display.setCursor(0, 50); display.printf("Siren: %s", buzzer_state ? "ON" : "OFF");
  } else {
    display.setCursor(0, 14); display.printf("Temp: %.1f C", temp_c);
    display.setCursor(0, 26); display.printf("Humidity: %.0f%%", humidity_p);
    display.setCursor(0, 38);
    if (gps.location.isValid()) {
      display.printf("Lat: %.4f", gps.location.lat());
      display.setCursor(0, 50); display.printf("Lon: %.4f", gps.location.lng());
    } else {
      display.print("GPS: Searching Sats");
      display.setCursor(0, 50); display.printf("Sats: %d", gps.satellites.value());
    }
  }
  display.display();
}

// ==========================================
// EMBEDDED HTTP SERVER HANDLERS
// ==========================================
void handleRoot() {
  // Mobile-Optimized Self-Contained Web App served directly from ESP32 flash!
  String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'><title>GeoGuard AI Emergency Monitor</title><style>";
  html += "*{box-sizing:border-box;margin:0;padding:0}body{background:#020617;color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;padding:12px}";
  html += ".card{background:#0f172a;border-radius:16px;padding:16px;margin-bottom:12px;border:1px solid #1e293b;box-shadow:0 4px 12px rgba(0,0,0,0.5)}";
  html += ".banner{border-radius:18px;padding:20px;text-align:center;margin-bottom:12px;transition:0.3s}";
  html += ".banner.safe{background:#064e3b;border:2px solid #10b981}.banner.warn{background:#78350f;border:2px solid #f59e0b}.banner.danger{background:#7f1d1d;border:2px solid #ef4444;animation:pulse 1s infinite}";
  html += "@keyframes pulse{0%,100%{opacity:1}50%{opacity:0.6}}";
  html += ".title{font-size:24px;font-weight:900;letter-spacing:-0.5px;margin-bottom:4px}";
  html += ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:12px}";
  html += ".metric{background:#1e293b;border-radius:12px;padding:12px;text-align:center}";
  html += ".metric-val{font-size:26px;font-weight:900;margin-top:4px}";
  html += ".btn{display:block;width:100%;padding:14px;border:none;border-radius:12px;font-weight:bold;font-size:15px;color:#fff;cursor:pointer;text-align:center;text-decoration:none;margin-top:8px}";
  html += ".btn-danger{background:#dc2626}.btn-call{background:#2563eb}";
  html += ".earth-frame{width:100%;height:260px;border:0;border-radius:12px;background:#020617}.earth-link{background:#0891b2}";
  html += "</style></head><body>";

  html += "<div class='card' style='display:flex;justify-content:space-between;align-items:center'>";
  html += "<div><h2 style='font-size:17px;font-weight:800'>GeoGuard AI Station</h2><p style='font-size:11px;color:#94a3b8'>ESP32 Direct Wi-Fi Connection</p></div>";
  html += "<span style='background:#10b981;color:#fff;padding:4px 8px;border-radius:8px;font-size:10px;font-weight:bold'>LIVE AP</span>";
  html += "</div>";

  html += "<div id='harmBanner' class='banner safe'>";
  html += "<div style='font-size:11px;font-weight:bold;letter-spacing:1px;text-transform:uppercase' id='harmSub'>LOCATION STATUS</div>";
  html += "<div class='title' id='harmTitle'>LOCATION SAFE</div>";
  html += "<p style='font-size:13px;opacity:0.9' id='harmDesc'>Normal stable slope. No movement detected.</p>";
  html += "<div style='margin-top:10px;font-size:20px;font-weight:900' id='harmScore'>Risk: 5%</div>";
  html += "</div>";

  html += "<div class='card'>";
  html += "<div style='font-size:11px;font-weight:bold;color:#94a3b8;margin-bottom:6px'>DISTANCE TO SENSOR STATION</div>";
  html += "<div style='font-size:14px;color:#e2e8f0' id='proximityMsg'>Detecting your device GPS position...</div>";
  html += "<div style='font-size:12px;color:#38bdf8;margin-top:4px;font-family:monospace' id='coordsText'>--</div>";
  html += "</div>";

  html += "<div class='grid'>";
  html += "<div class='metric'><div style='font-size:11px;color:#94a3b8'>Soil Moisture #1</div><div class='metric-val' id='s1' style='color:#60a5fa'>--%</div></div>";
  html += "<div class='metric'><div style='font-size:11px;color:#94a3b8'>Soil Moisture #2</div><div class='metric-val' id='s2' style='color:#818cf8'>--%</div></div>";
  html += "<div class='metric'><div style='font-size:11px;color:#94a3b8'>Rain Intensity</div><div class='metric-val' id='rain' style='color:#22d3ee'>--%</div></div>";
  html += "<div class='metric'><div style='font-size:11px;color:#94a3b8'>Max Slope Tilt</div><div class='metric-val' id='tilt' style='color:#fbbf24'>--°</div></div>";
  html += "</div>";

  html += "<div class='card'>";
  html += "<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px'><div style='font-size:11px;font-weight:bold;color:#67e8f9'>GOOGLE EARTH SATELLITE VIEW</div><span style='font-size:10px;color:#94a3b8'>ESP32 LIVE LOCATION</span></div>";
  html += "<iframe id='earthFrame' class='earth-frame' title='Google Earth satellite view' src='https://maps.google.com/maps?q=13.0827,80.2707&t=k&z=15&ie=UTF8&iwloc=&output=embed' loading='lazy' allowfullscreen></iframe>";
  html += "<a class='btn earth-link' href='https://maps.google.com/maps?q=13.0827,80.2707&t=k&z=15' target='_blank' rel='noopener'>Open Google Earth Satellite Map</a>";
  html += "</div>";

  html += "<div class='card'>";
  html += "<div style='font-size:11px;font-weight:bold;color:#94a3b8;margin-bottom:8px'>ACTUATOR CONTROLS</div>";
  html += "<div style='display:flex;justify-content:space-between;font-size:13px;margin-bottom:6px'><span>Drainage Pump:</span><strong id='pumpState' style='color:#38bdf8'>OFF</strong></div>";
  html += "<div style='display:flex;justify-content:space-between;font-size:13px;margin-bottom:6px'><span>Evacuation Siren:</span><strong id='buzzerState' style='color:#f87171'>SILENT</strong></div>";
  html += "<div style='display:flex;justify-content:space-between;font-size:13px'><span>Temperature / Hum:</span><strong id='env' style='color:#cbd5e1'>--</strong></div>";
  html += "<a href='tel:112' class='btn btn-danger'>Call Disaster Emergency (112)</a>";
  html += "</div>";

  html += "<script>";
  html += "let stLat=13.0827, stLng=80.2707;";
  html += "function dist(la1,lo1,la2,lo2){const R=6371e3,f1=la1*Math.PI/180,f2=la2*Math.PI/180,df=(la2-la1)*Math.PI/180,dl=(lo2-lo1)*Math.PI/180,a=Math.sin(df/2)**2+Math.cos(f1)*Math.cos(f2)*Math.sin(dl/2)**2;return Math.round(R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a)));}";
  html += "if('geolocation' in navigator){navigator.geolocation.watchPosition(p=>{const uLat=p.coords.latitude,uLng=p.coords.longitude,d=dist(uLat,uLng,stLat,stLng);document.getElementById('coordsText').innerText='You: '+uLat.toFixed(5)+', '+uLng.toFixed(5)+' | Station: '+stLat.toFixed(5)+', '+stLng.toFixed(5);if(d<=100){document.getElementById('proximityMsg').innerHTML='<span style=\"color:#f87171;font-weight:bold\">WARNING: You are inside the high-risk perimeter ('+d+'m)! Move uphill!</span>';}else{document.getElementById('proximityMsg').innerText='You are '+d+' meters away from the slope station.';}});}";
  html += "async function poll(){try{const r=await fetch('/api/telemetry');const d=await r.json();document.getElementById('s1').innerText=d.soil_moisture_1+'%';document.getElementById('s2').innerText=d.soil_moisture_2+'%';document.getElementById('rain').innerText=d.rain_intensity+'%';document.getElementById('tilt').innerText=d.max_tilt+'°';document.getElementById('env').innerText=d.temperature+'C / '+d.humidity+'%';document.getElementById('pumpState').innerText=d.pump_status?'ACTIVE':'OFF';document.getElementById('buzzerState').innerText=d.buzzer_status?'SIREN ACTIVE':'SILENT';if(d.latitude&&d.latitude!=0){stLat=d.latitude;stLng=d.longitude;document.getElementById('earthFrame').src='https://maps.google.com/maps?q='+stLat+','+stLng+'&t=k&z=15&ie=UTF8&iwloc=&output=embed';}const b=document.getElementById('harmBanner');const t=document.getElementById('harmTitle');const de=document.getElementById('harmDesc');const sc=document.getElementById('harmScore');sc.innerText='Risk Index: '+d.risk_score+'%';if(d.risk_level==='CRITICAL'){b.className='banner danger';t.innerText='LOCATION HARMFUL: EVACUATE NOW!';de.innerText='Immediate danger of slope collapse! High soil saturation & tilt displacement.';}else if(d.risk_level==='WARNING'){b.className='banner warn';t.innerText='LOCATION CAUTION: ELEVATED RISK';de.innerText='High rainfall infiltration detected. Maintain vigilance.';}else{b.className='banner safe';t.innerText='LOCATION SAFE: STABLE SLOPE';de.innerText='Geotechnical stability normal.';}}catch(e){}}setInterval(poll,1500);poll();";
  html += "</script></body></html>";

  server.send(200, "text/html", html);
}

void handleTelemetryApi() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  String json = "{";
  json += "\"soil_moisture_1\":" + String(soil1_percent, 1) + ",";
  json += "\"soil_moisture_2\":" + String(soil2_percent, 1) + ",";
  json += "\"soil_1_raw\":" + String(soil1_raw) + ",";
  json += "\"soil_2_raw\":" + String(soil2_raw) + ",";
  json += "\"soil_moisture_avg\":" + String((soil1_percent + soil2_percent) / 2.0, 1) + ",";
  json += "\"rain_intensity\":" + String(rain_percent, 1) + ",";
  json += "\"rain_raw\":" + String(rain_raw) + ",";
  json += "\"mpu1_pitch\":" + String(mpu1_pitch, 2) + ",";
  json += "\"mpu1_roll\":" + String(mpu1_roll, 2) + ",";
  json += "\"mpu2_pitch\":" + String(mpu2_pitch, 2) + ",";
  json += "\"mpu2_roll\":" + String(mpu2_roll, 2) + ",";
  json += "\"max_tilt\":" + String(max_tilt_dev, 2) + ",";
  json += "\"temperature\":" + String(temp_c, 1) + ",";
  json += "\"humidity\":" + String(humidity_p, 1) + ",";
  json += "\"sample_interval_ms\":" + String(SENSOR_SAMPLE_INTERVAL_MS) + ",";
  json += "\"sensor_status\":{";
  json += "\"soil_1\":true,";
  json += "\"soil_2\":true,";
  json += "\"rain\":true,";
  json += "\"mpu1\":" + String(mpu1_found ? "true" : "false") + ",";
  json += "\"mpu2\":" + String(mpu2_found ? "true" : "false") + ",";
  json += "\"dht\":" + String(dht_available ? "true" : "false") + ",";
  json += "\"gps\":" + String(gps.location.isValid() ? "true" : "false");
  json += "},";
  json += "\"risk_code\":" + String(current_risk_code) + ",";
  json += "\"risk_level\":\"" + String(current_risk_code == 2 ? "CRITICAL" : (current_risk_code == 1 ? "WARNING" : "SAFE")) + "\",";
  json += "\"risk_score\":" + String(current_risk_score, 1) + ",";
  json += "\"pump_status\":" + String(pump_state ? "true" : "false") + ",";
  json += "\"buzzer_status\":" + String(buzzer_state ? "true" : "false") + ",";
  json += "\"led_status\":\"" + String(current_risk_code == 2 ? "RED" : (current_risk_code == 1 ? "YELLOW" : "GREEN")) + "\",";

  if (gps.location.isValid()) {
    json += "\"latitude\":" + String(gps.location.lat(), 6) + ",";
    json += "\"longitude\":" + String(gps.location.lng(), 6) + ",";
    json += "\"altitude\":" + String(gps.altitude.meters(), 1) + ",";
    json += "\"satellites\":" + String(gps.satellites.value());
  } else {
    json += "\"latitude\":0,";
    json += "\"longitude\":0,";
    json += "\"altitude\":0,";
    json += "\"satellites\":0";
  }
  json += "}";

  server.send(200, "application/json", json);
}

void handlePumpToggle() {
  pump_state = !pump_state;
  digitalWrite(PIN_RELAY_PUMP, pump_state ? HIGH : LOW);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"pump_status\":" + String(pump_state ? "true" : "false") + "}");
}
