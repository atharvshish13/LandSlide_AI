import os

try:
    import joblib
    import pandas as pd
except ImportError:
    joblib = None
    pd = None

MODEL_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(MODEL_DIR, 'landslide_model.pkl')
SCALER_PATH = os.path.join(MODEL_DIR, 'scaler.pkl')

FEATURE_COLUMNS = [
    'soil_moisture_1',
    'soil_moisture_2',
    'soil_moisture_avg',
    'soil_saturation_rate',
    'rain_intensity',
    'rain_duration_min',
    'max_tilt_dev',
    'tilt_velocity',
    'tilt_acceleration',
    'vibration',
    'temperature',
    'humidity',
    'pore_water_index'
]

_model = None
_scaler = None

def load_model():
    global _model, _scaler
    if os.environ.get('GEOGUARD_DISABLE_ML_MODEL') == '1':
        return None, None
    if joblib is not None and _model is None and os.path.exists(MODEL_PATH) and os.path.exists(SCALER_PATH):
        _model = joblib.load(MODEL_PATH)
        _scaler = joblib.load(SCALER_PATH)
    return _model, _scaler

def evaluate_landslide_risk(data):
    """
    Sensor Fusion & Geotechnical Factor of Safety (FoS) Engine with 30-Min Horizon Forecasting.
    """
    s1 = float(data.get('soil_moisture_1', 25.0))
    s2 = float(data.get('soil_moisture_2', 28.0))
    s_avg = (s1 + s2) / 2.0
    ds_dt = float(data.get('soil_saturation_rate', 0.5))
    rain = float(data.get('rain_intensity', 5.0))
    rain_dur = float(data.get('rain_duration_min', 0.0))

    # Tilt kinematics
    p1 = float(data.get('mpu1_pitch_dev', data.get('mpu1_pitch', 0.0)))
    r1 = float(data.get('mpu1_roll_dev', data.get('mpu1_roll', 0.0)))
    p2 = float(data.get('mpu2_pitch_dev', data.get('mpu2_pitch', 0.0)))
    r2 = float(data.get('mpu2_roll_dev', data.get('mpu2_roll', 0.0)))
    max_tilt = max(abs(p1), abs(r1), abs(p2), abs(r2))

    v_tilt = float(data.get('tilt_velocity', 0.02))         # deg/min
    a_tilt = float(data.get('tilt_acceleration', 0.002))     # deg/min² - tertiary creep indicator
    vib = float(data.get('vibration', 0.04))
    temp = float(data.get('temperature', 25.0))
    hum = float(data.get('humidity', 60.0))

    pore_idx = (s_avg * 0.5) + (rain * 0.3) + (min(120.0, rain_dur) * 0.2)

    model, scaler = load_model()

    if model is not None and scaler is not None and pd is not None:
        features = pd.DataFrame([[
            s1, s2, s_avg, ds_dt, rain, rain_dur, max_tilt,
            v_tilt, a_tilt, vib, temp, hum, pore_idx
        ]], columns=FEATURE_COLUMNS)
        X_scaled = scaler.transform(features)
        probs = model.predict_proba(X_scaled)[0]
        # Pad to 4 classes if needed
        p_low = float(probs[0]) if len(probs) > 0 else 0.0
        p_med = float(probs[1]) if len(probs) > 1 else 0.0
        p_high = float(probs[2]) if len(probs) > 2 else 0.0
        p_crit = float(probs[3]) if len(probs) > 3 else 0.0
        pred_code = int(model.predict(X_scaled)[0])
    elif model is None or pd is None:
        from model.android_forest import predict
        features = [
            s1, s2, s_avg, ds_dt, rain, rain_dur, max_tilt,
            v_tilt, a_tilt, vib, temp, hum, pore_idx
        ]
        pred_code, probs = predict(features)
        p_low = float(probs[0]) if len(probs) > 0 else 0.0
        p_med = float(probs[1]) if len(probs) > 1 else 0.0
        p_high = float(probs[2]) if len(probs) > 2 else 0.0
        p_crit = float(probs[3]) if len(probs) > 3 else 0.0
    else:
        # Geotechnical fallback heuristic
        if max_tilt > 8.0 or a_tilt > 0.15 or (s_avg > 80 and rain > 65):
            pred_code = 3
            p_crit, p_high, p_med, p_low = 0.85, 0.10, 0.05, 0.0
        elif max_tilt > 3.5 or v_tilt > 0.20 or s_avg > 65:
            pred_code = 2
            p_crit, p_high, p_med, p_low = 0.15, 0.70, 0.10, 0.05
        elif s_avg > 45 or rain > 30:
            pred_code = 1
            p_crit, p_high, p_med, p_low = 0.05, 0.15, 0.65, 0.15
        else:
            pred_code = 0
            p_crit, p_high, p_med, p_low = 0.01, 0.04, 0.10, 0.85

    # Geotechnical Failsafe Rule: Accelerating Tertiary Creep Override
    # When angular acceleration > 0.20 deg/min² or tilt > 9 deg with saturated soil
    if a_tilt >= 0.18 or max_tilt >= 8.5 or (s_avg >= 82.0 and rain >= 65.0 and rain_dur >= 30.0):
        pred_code = 3
        p_crit = max(p_crit, 0.95)

    # Calculate Geotechnical Factor of Safety (FoS)
    # Standard: FoS > 1.5 Safe | 1.2 - 1.5 Medium | 1.0 - 1.2 High | < 1.0 Failure
    # Driving stress increases with tilt & pore water saturation
    driving_stress = 0.4 + (max_tilt * 0.08) + (s_avg * 0.007) + (v_tilt * 0.3)
    resisting_strength = 1.6 - (s_avg * 0.008) - (rain_dur * 0.002)
    fos = resisting_strength / max(0.2, driving_stress)
    fos = round(max(0.45, min(2.8, fos)), 2)

    # Risk Score (0 - 100%)
    risk_score = round((p_med * 30.0) + (p_high * 70.0) + (p_crit * 100.0), 1)
    risk_score = min(100.0, max(0.0, risk_score))

    level_map = {0: 'LOW', 1: 'MEDIUM', 2: 'HIGH', 3: 'CRITICAL'}
    risk_level = level_map.get(pred_code, 'LOW')

    # =========================================================
    # PREDICTIVE HORIZON: "NEXT 30 MINUTES" EXTRAPOLATION
    # =========================================================
    # Taylor series kinematics extrapolation: θ(t+30) = θ + v*t + 0.5*a*t²
    proj_30m_tilt = max_tilt + (v_tilt * 30.0) + (0.5 * a_tilt * (30.0 ** 2))
    proj_30m_sat = min(100.0, s_avg + (ds_dt * 0.5))

    proj_score = risk_score + (v_tilt * 40.0) + (a_tilt * 120.0) + (rain * 0.15)
    proj_score = round(min(100.0, max(risk_score, proj_score)), 1)

    if proj_score >= 85.0 or proj_30m_tilt >= 7.5 or fos <= 1.05:
        proj_30m_level = 'CRITICAL'
        proj_lead_time_min = 18
    elif proj_score >= 55.0 or proj_30m_tilt >= 3.5 or fos <= 1.25:
        proj_30m_level = 'HIGH'
        proj_lead_time_min = 28
    elif proj_score >= 30.0:
        proj_30m_level = 'MEDIUM'
        proj_lead_time_min = 45
    else:
        proj_30m_level = 'LOW'
        proj_lead_time_min = 90

    # Actuator states (4-Tier Response Matrix)
    pump_active = (pred_code >= 2 or s_avg >= 70.0 or rain >= 55.0)
    buzzer_active = (pred_code == 3)
    led_status = 'GREEN' if pred_code == 0 else ('YELLOW' if pred_code == 1 else ('AMBER' if pred_code == 2 else 'RED'))

    # 1. AI-Based Risk Prediction (Already handled via pred_code & probabilities)
    # 2. Multi-Sensor Data Fusion (Already happening by combining features into X_scaled)
    # 8. AI Risk Score (Already calculated: risk_score)

    # 3. Risk Trend Analysis
    if a_tilt > 0.05 or ds_dt > 0.5:
        trend = "INCREASING (DANGER)"
    elif a_tilt < -0.02 or ds_dt < -0.2:
        trend = "DECREASING (STABILIZING)"
    else:
        trend = "STABLE"

    # 5. Anomaly Detection
    anomalies = []
    if abs(v_tilt) > 0.5:
        anomalies.append("Sudden high slope movement detected")
    if ds_dt > 1.0:
        anomalies.append("Abnormal spike in soil moisture rate")
    if vib > 0.2:
        anomalies.append("High vibration anomaly (possible equipment bump or tremor)")
    
    # 6. Rainfall-Soil Correlation
    if rain > 0 and ds_dt > 0:
        correlation = "High (Soil is rapidly absorbing rain)"
    elif rain > 0 and ds_dt <= 0:
        correlation = "Low (Soil already saturated or runoff occurring)"
    else:
        correlation = "Neutral"

    # 7. Time-Series Prediction (Using kinematic projection) & 4. Early Warning Prediction
    # (Already handled in proj_30m_level and proj_30m_tilt)

    # 10. AI-Based Alert Prioritization
    if pred_code >= 3:
        alert_priority = "IMMEDIATE EMERGENCY ACTION"
    elif pred_code == 2:
        alert_priority = "WARNING - PREPARE EVACUATION"
    else:
        alert_priority = "MONITORING"

    drivers = []
    if max_tilt >= 3.0:
        drivers.append(f"slope tilt is {max_tilt:.1f}°")
    if s_avg >= 60.0:
        drivers.append(f"average soil moisture is {s_avg:.0f}%")
    if rain >= 45.0:
        drivers.append(f"rain intensity is {rain:.0f}%")
    if v_tilt >= 0.2 or a_tilt >= 0.05:
        drivers.append("slope movement is accelerating")
    if fos <= 1.2:
        drivers.append(f"estimated factor of safety is {fos:.2f}")
    if not drivers:
        drivers.append("no monitored input has crossed an elevated screening threshold")

    if pred_code >= 3:
        recommended_action = "Move away from the slope and follow local emergency instructions."
    elif pred_code == 2:
        recommended_action = "Prepare to move to a safe area and monitor official alerts."
    elif pred_code == 1:
        recommended_action = "Increase observation frequency and verify sensor connections."
    else:
        recommended_action = "Continue monitoring; this is not a guarantee of slope safety."
    ai_insight = (
        f"AI screening: {'; '.join(drivers)}. {recommended_action} "
        "This estimate is not a substitute for field inspection or official warnings."
    )

    return {
        'risk_code': pred_code,
        'risk_level': risk_level,
        'risk_score': risk_score,
        'risk_trend': trend,
        'factor_of_safety': fos,
        'slope_kinematics': {
            'tilt_velocity_deg_per_min': round(v_tilt, 3),
            'tilt_acceleration_deg_per_min2': round(a_tilt, 4),
            'creep_status': 'TERTIARY (ACCELERATING)' if a_tilt > 0.05 else ('SECONDARY (STEADY)' if v_tilt > 0.05 else 'PRIMARY (STABLE)')
        },
        'forecast_30min': {
            'predicted_level': proj_30m_level,
            'predicted_score': proj_score,
            'projected_tilt_deg': round(proj_30m_tilt, 1),
            'estimated_time_to_rupture_min': proj_lead_time_min if pred_code >= 2 else None,
            'early_warning_advisory': f"Slope displacement accelerating. Risk will transition from {risk_level} to {proj_30m_level} within 30 minutes. Evacuate downstream sector!" if pred_code >= 2 else "Slope currently stable. Minimal risk propagation in next 30 min."
        },
        'anomaly_detection': anomalies,
        'rainfall_soil_correlation': correlation,
        'alert_prioritization': alert_priority,
        'ai_insight': ai_insight,
        'probabilities': {
            'low': round(p_low, 3),
            'medium': round(p_med, 3),
            'high': round(p_high, 3),
            'critical': round(p_crit, 3)
        },
        'telemetry_computed': {
            'soil_moisture_avg': round(s_avg, 1),
            'rain_duration_min': round(rain_dur, 1),
            'max_tilt_deviation': round(max_tilt, 2),
            'pore_water_index': round(pore_idx, 1)
        },
        'actuators': {
            'pump_active': pump_active,
            'buzzer_active': buzzer_active,
            'led_status': led_status
        }
    }

def simulate_what_if(data, rainfall_increase_percent=20.0):
    """
    9. What-If Simulation
    """
    sim_data = data.copy()
    current_rain = float(sim_data.get('rain_intensity', 0.0))
    sim_data['rain_intensity'] = current_rain * (1.0 + (rainfall_increase_percent / 100.0))
    # Also simulate moisture increase based on rain increase
    sim_data['soil_saturation_rate'] = float(sim_data.get('soil_saturation_rate', 0.0)) + (rainfall_increase_percent / 100.0)
    
    res = evaluate_landslide_risk(sim_data)
    return {
        'scenario': f"If rainfall increases by {rainfall_increase_percent}%",
        'predicted_risk_level': res['risk_level'],
        'predicted_risk_score': res['risk_score'],
        'alert_prioritization': res['alert_prioritization']
    }

if __name__ == '__main__':

    test_d = {
        'soil_moisture_1': 88.0,
        'soil_moisture_2': 92.0,
        'rain_intensity': 80.0,
        'rain_duration_min': 45.0,
        'mpu1_pitch_dev': 5.5,
        'tilt_velocity': 0.45,
        'tilt_acceleration': 0.12
    }
    print('[4-TIER TEST EVALUATION]:', evaluate_landslide_risk(test_d))
