import math
import unittest

from backend.telemetry import KinematicTracker, build_sensor_quality, validate_telemetry


class ValidateTelemetryTests(unittest.TestCase):
    def test_accepts_and_normalizes_required_readings(self):
        data = {
            'soil_moisture_1': '40.5',
            'soil_moisture_2': 60,
            'rain_intensity': '12',
            'satellites': '5',
        }

        self.assertIsNone(validate_telemetry(data))
        self.assertEqual(data['soil_moisture_1'], 40.5)
        self.assertEqual(data['rain_intensity'], 12.0)
        self.assertEqual(data['satellites'], 5.0)

    def test_rejects_missing_required_reading(self):
        error = validate_telemetry({'soil_moisture_1': 40, 'soil_moisture_2': 60})

        self.assertEqual(error, 'Missing required sensor readings: rain_intensity.')

    def test_rejects_boolean_non_finite_out_of_range_and_fractional_integer(self):
        cases = (
            ({'soil_moisture_1': True}, 'soil_moisture_1 must be a finite number.'),
            ({'soil_moisture_1': math.nan}, 'soil_moisture_1 must be between 0.0 and 100.0.'),
            ({'soil_moisture_1': 101}, 'soil_moisture_1 must be between 0.0 and 100.0.'),
            ({'satellites': 2.5}, 'satellites must be an integer.'),
        )
        base = {'soil_moisture_1': 40, 'soil_moisture_2': 60, 'rain_intensity': 10}

        for update, expected in cases:
            with self.subTest(update=update):
                data = {**base, **update}
                self.assertEqual(validate_telemetry(data), expected)


class SensorQualityTests(unittest.TestCase):
    def test_classifies_complete_degraded_and_unverified_coverage(self):
        sensors = ('soil_1', 'soil_2', 'rain', 'mpu1', 'mpu2', 'dht')
        complete = build_sensor_quality({name: True for name in sensors})
        degraded = build_sensor_quality({'soil_1': True, 'rain': False})
        unverified = build_sensor_quality({})

        self.assertEqual((complete['label'], complete['coverage_percent']), ('COMPLETE', 100))
        self.assertEqual((degraded['label'], degraded['coverage_percent']), ('DEGRADED', 17))
        self.assertEqual((unverified['label'], unverified['coverage_percent']), ('UNVERIFIED', 0))
        self.assertEqual(degraded['unavailable'], ['rain'])


class KinematicTrackerTests(unittest.TestCase):
    def test_derives_initial_sample_and_resets_rain_duration_when_rain_stops(self):
        tracker = KinematicTracker()
        rainy = {
            'soil_moisture_1': 40,
            'soil_moisture_2': 60,
            'rain_intensity': 20,
            'mpu1_pitch_dev': 1.25,
        }

        result = tracker.update(rainy)
        self.assertEqual(result['tilt_velocity'], 0.0)
        self.assertEqual(result['tilt_acceleration'], 0.0)
        self.assertEqual(result['soil_saturation_rate'], 0.0)
        self.assertGreaterEqual(result['rain_duration_min'], 1.0)

        dry = tracker.update({'soil_moisture_1': 40, 'soil_moisture_2': 60, 'rain_intensity': 0})
        self.assertEqual(dry['rain_duration_min'], 0.0)


if __name__ == '__main__':
    unittest.main()
