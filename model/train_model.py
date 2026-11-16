import os
import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import classification_report, accuracy_score

MODEL_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(MODEL_DIR, 'landslide_model.pkl')
SCALER_PATH = os.path.join(MODEL_DIR, 'scaler.pkl')

FEATURE_COLUMNS = [
    'soil_moisture_1',
    'soil_moisture_2',
    'soil_moisture_avg',
    'soil_saturation_rate',   # dS/dt (%/hr)
    'rain_intensity',         # %
    'rain_duration_min',      # duration of continuous rain (min)
    'max_tilt_dev',           # current angular displacement (deg)
    'tilt_velocity',          # dθ/dt (deg/min)
    'tilt_acceleration',      # d²θ/dt² (deg/min²) - tertiary creep
    'vibration',              # RMS g
    'temperature',            # °C
    'humidity',               # %
    'pore_water_index'        # combined hydrologic loading
]

def generate_geotechnical_dataset(n_samples=8000, random_state=42):
    """
    Generates realistic 4-Tier geotechnical landslide dataset:
    0: LOW (Stable slope, FoS > 1.5)
    1: MEDIUM (Elevated infiltration, FoS 1.2 - 1.5)
    2: HIGH (Significant creep & high pore pressure, FoS 1.0 - 1.2)
    3: CRITICAL (Accelerating tertiary creep & imminent slope failure, FoS < 1.0)
    """
    np.random.seed(random_state)
    classes = np.random.choice([0, 1, 2, 3], size=n_samples, p=[0.45, 0.25, 0.18, 0.12])

    s1, s2, ds_dt, rain, rain_dur = [], [], [], [], []
    tilt, v_tilt, a_tilt, vib = [], [], [], []
    temp = np.random.uniform(14.0, 36.0, n_samples)
    hum = np.random.uniform(40.0, 99.0, n_samples)

    for c in classes:
        if c == 0:  # LOW / STABLE
            _s1 = np.random.uniform(10.0, 45.0)
            _s2 = np.random.uniform(10.0, 45.0)
            _ds = np.random.uniform(-0.5, 1.0)
            _r = np.random.uniform(0.0, 20.0)
            _rdur = np.random.uniform(0.0, 15.0)
            _t = np.random.normal(0.2, 0.2)
            _vt = np.random.normal(0.01, 0.01)
            _at = np.random.normal(0.001, 0.001)
            _v = np.random.uniform(0.01, 0.08)
        elif c == 1:  # MEDIUM / ELEVATED
            _s1 = np.random.uniform(45.0, 68.0)
            _s2 = np.random.uniform(45.0, 68.0)
            _ds = np.random.uniform(1.0, 4.0)
            _r = np.random.uniform(20.0, 55.0)
            _rdur = np.random.uniform(15.0, 60.0)
            _t = np.random.normal(1.8, 0.6)
            _vt = np.random.normal(0.08, 0.03)
            _at = np.random.normal(0.01, 0.005)
            _v = np.random.uniform(0.08, 0.22)
        elif c == 2:  # HIGH / ACCELERATING CREEP
            _s1 = np.random.uniform(68.0, 85.0)
            _s2 = np.random.uniform(68.0, 85.0)
            _ds = np.random.uniform(3.5, 9.0)
            _r = np.random.uniform(55.0, 85.0)
            _rdur = np.random.uniform(45.0, 150.0)
            _t = np.random.normal(4.8, 1.2)
            _vt = np.random.normal(0.35, 0.10)
            _at = np.random.normal(0.08, 0.025)
            _v = np.random.uniform(0.22, 0.50)
        else:  # CRITICAL / IMMINENT RUPTURE
            _s1 = np.random.uniform(82.0, 99.0)
            _s2 = np.random.uniform(85.0, 100.0)
            _ds = np.random.uniform(8.0, 20.0)
            _r = np.random.uniform(75.0, 100.0)
            _rdur = np.random.uniform(90.0, 300.0)
            _t = np.random.normal(10.5, 3.0)
            _vt = np.random.normal(1.20, 0.40)
            _at = np.random.normal(0.35, 0.12)
            _v = np.random.uniform(0.45, 0.98)

        s1.append(float(np.clip(_s1, 0, 100)))
        s2.append(float(np.clip(_s2, 0, 100)))
        ds_dt.append(float(np.clip(_ds, -5, 30)))
        rain.append(float(np.clip(_r, 0, 100)))
        rain_dur.append(float(max(0, _rdur)))
        tilt.append(float(max(0, _t)))
        v_tilt.append(float(max(0, _vt)))
        a_tilt.append(float(max(0, _at)))
        vib.append(float(max(0.01, _v)))

    df = pd.DataFrame({
        'soil_moisture_1': s1,
        'soil_moisture_2': s2,
        'soil_saturation_rate': ds_dt,
        'rain_intensity': rain,
        'rain_duration_min': rain_dur,
        'max_tilt_dev': tilt,
        'tilt_velocity': v_tilt,
        'tilt_acceleration': a_tilt,
        'vibration': vib,
        'temperature': temp,
        'humidity': hum
    })

    df['soil_moisture_avg'] = (df['soil_moisture_1'] + df['soil_moisture_2']) / 2.0
    # Combined pore pressure loading index
    df['pore_water_index'] = (df['soil_moisture_avg'] * 0.5) + (df['rain_intensity'] * 0.3) + (np.clip(df['rain_duration_min'], 0, 120) * 0.2)
    df['target'] = classes
    return df

def train():
    print('[+] Generating 4-Tier geotechnical dataset...')
    df = generate_geotechnical_dataset()

    X = df[FEATURE_COLUMNS]
    y = df['target']

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    print('[+] Training Random Forest Classifier for 4-Tier Early Warning...')
    clf = RandomForestClassifier(
        n_estimators=180,
        max_depth=14,
        min_samples_split=4,
        random_state=42,
        class_weight='balanced'
    )
    clf.fit(X_train_scaled, y_train)

    y_pred = clf.predict(X_test_scaled)
    acc = accuracy_score(y_test, y_pred)
    print(f'[SUCCESS] Validation Accuracy: {acc * 100:.2f}%')
    print(classification_report(y_test, y_pred, target_names=['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']))

    joblib.dump(clf, MODEL_PATH)
    joblib.dump(scaler, SCALER_PATH)
    print(f'[SUCCESS] Model exported to: {MODEL_PATH}')
    print(f'[SUCCESS] Scaler exported to: {SCALER_PATH}')

if __name__ == '__main__':
    train()
