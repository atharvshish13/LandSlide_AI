import math
import threading
from datetime import datetime


TELEMETRY_RANGES = {
    'soil_moisture_1': (0.0, 100.0),
    'soil_moisture_2': (0.0, 100.0),
    'rain_intensity': (0.0, 100.0),
    'soil_1_raw': (0.0, 4095.0),
    'soil_2_raw': (0.0, 4095.0),
    'rain_raw': (0.0, 4095.0),
    'mpu1_pitch': (-180.0, 180.0),
    'mpu1_roll': (-180.0, 180.0),
    'mpu2_pitch': (-180.0, 180.0),
    'mpu2_roll': (-180.0, 180.0),
    'mpu1_pitch_dev': (0.0, 180.0),
    'mpu1_roll_dev': (0.0, 180.0),
    'mpu2_pitch_dev': (0.0, 180.0),
    'mpu2_roll_dev': (0.0, 180.0),
    'max_tilt': (0.0, 180.0),
    'tilt_velocity': (0.0, 100.0),
    'tilt_acceleration': (0.0, 100.0),
    'soil_saturation_rate': (-100.0, 100.0),
    'rain_duration_min': (0.0, 1440.0),
    'vibration': (0.0, 100.0),
    'temperature': (-40.0, 85.0),
    'humidity': (0.0, 100.0),
    'latitude': (-90.0, 90.0),
    'longitude': (-180.0, 180.0),
    'altitude': (-1000.0, 10000.0),
    'satellites': (0.0, 100.0),
}

REQUIRED_SENSORS = ('soil_1', 'soil_2', 'rain', 'mpu1', 'mpu2', 'dht')
ALL_SENSORS = (*REQUIRED_SENSORS, 'gps')


def validate_telemetry(data):
    required = ('soil_moisture_1', 'soil_moisture_2', 'rain_intensity')
    missing = [key for key in required if key not in data]
    if missing:
        return f"Missing required sensor readings: {', '.join(missing)}."

    for key, (minimum, maximum) in TELEMETRY_RANGES.items():
        if key not in data:
            continue
        value = data[key]
        if isinstance(value, bool):
            return f'{key} must be a finite number.'
        try:
            numeric_value = float(value)
        except (TypeError, ValueError, OverflowError):
            return f'{key} must be a finite number.'
        if not math.isfinite(numeric_value) or not minimum <= numeric_value <= maximum:
            return f'{key} must be between {minimum} and {maximum}.'
        if key in ('soil_1_raw', 'soil_2_raw', 'rain_raw', 'satellites') and not numeric_value.is_integer():
            return f'{key} must be an integer.'
        data[key] = numeric_value
    return None


def build_sensor_quality(sensor_status):
    known = [sensor_status.get(name) for name in REQUIRED_SENSORS]
    coverage = round(100.0 * sum(value is True for value in known) / len(REQUIRED_SENSORS))
    if all(value is True for value in known):
        label = 'COMPLETE'
    elif any(value is False for value in known):
        label = 'DEGRADED'
    else:
        label = 'UNVERIFIED'
    return {
        'label': label,
        'coverage_percent': coverage,
        'required_sensors': list(REQUIRED_SENSORS),
        'unavailable': [name for name, value in zip(REQUIRED_SENSORS, known) if value is False],
        'unverified': [name for name, value in zip(REQUIRED_SENSORS, known) if value is None],
    }


class KinematicTracker:
    def __init__(self):
        self._history = []
        self._rain_start_time = None
        self._lock = threading.Lock()

    def update(self, current_data):
        with self._lock:
            return self._update(current_data)

    def _update(self, current_data):
        now = datetime.utcnow()
        current_tilt = max(
            abs(float(current_data.get('mpu1_pitch_dev', current_data.get('mpu1_pitch', 0.0)))),
            abs(float(current_data.get('mpu1_roll_dev', current_data.get('mpu1_roll', 0.0)))),
            abs(float(current_data.get('mpu2_pitch_dev', current_data.get('mpu2_pitch', 0.0)))),
            abs(float(current_data.get('mpu2_roll_dev', current_data.get('mpu2_roll', 0.0)))),
        )
        s1 = float(current_data.get('soil_moisture_1', 25.0))
        s2 = float(current_data.get('soil_moisture_2', 28.0))
        s_avg = (s1 + s2) / 2.0
        rain = float(current_data.get('rain_intensity', 0.0))

        if rain > 15.0:
            if self._rain_start_time is None:
                self._rain_start_time = now
            rain_duration_min = max(1.0, (now - self._rain_start_time).total_seconds() / 60.0)
        else:
            self._rain_start_time = None
            rain_duration_min = 0.0

        while self._history and (now - self._history[0]['time']).total_seconds() > 120:
            self._history.pop(0)

        tilt_velocity = float(current_data.get('tilt_velocity', 0.0))
        tilt_accel = float(current_data.get('tilt_acceleration', 0.0))
        ds_dt = float(current_data.get('soil_saturation_rate', 0.0))

        if len(self._history) >= 2:
            prev = self._history[-1]
            dt_min = (now - prev['time']).total_seconds() / 60.0
            if dt_min > 0.005:
                v_now = abs(current_tilt - prev['tilt']) / dt_min
                tilt_velocity = round(v_now, 3)
                ds_dt = round((s_avg - prev['sat']) / dt_min, 2)

                if len(self._history) >= 4:
                    prev_v = prev.get('v', 0.02)
                    tilt_accel = round(abs(v_now - prev_v) / dt_min, 4)

        self._history.append({
            'time': now,
            'tilt': current_tilt,
            'sat': s_avg,
            'v': tilt_velocity,
        })

        current_data['tilt_velocity'] = tilt_velocity
        current_data['tilt_acceleration'] = tilt_accel
        current_data['rain_duration_min'] = rain_duration_min
        current_data['soil_saturation_rate'] = ds_dt
        return current_data
