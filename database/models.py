import os
import json
from datetime import datetime
from sqlalchemy import create_engine, Column, Integer, Float, String, Boolean, DateTime, Text, inspect
from sqlalchemy.orm import declarative_base, sessionmaker

DB_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get('GEOGUARD_DB_PATH', os.path.join(DB_DIR, 'landslide.db'))
os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
DATABASE_URI = f'sqlite:///{DB_PATH}'

engine = create_engine(DATABASE_URI, echo=False, connect_args={'check_same_thread': False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

class Telemetry(Base):
    __tablename__ = 'telemetry'

    id = Column(Integer, primary_key=True, autoincrement=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    soil_moisture_1 = Column(Float, default=0.0)
    soil_moisture_2 = Column(Float, default=0.0)
    soil_1_raw = Column(Integer, nullable=True)
    soil_2_raw = Column(Integer, nullable=True)
    soil_moisture_avg = Column(Float, default=0.0)
    soil_saturation_rate = Column(Float, default=0.0)
    rain_intensity = Column(Float, default=0.0)
    rain_raw = Column(Integer, nullable=True)
    rain_duration_min = Column(Float, default=0.0)
    mpu1_pitch = Column(Float, default=0.0)
    mpu1_roll = Column(Float, default=0.0)
    mpu2_pitch = Column(Float, default=0.0)
    mpu2_roll = Column(Float, default=0.0)
    max_tilt = Column(Float, default=0.0)
    tilt_velocity = Column(Float, default=0.0)
    tilt_acceleration = Column(Float, default=0.0)
    vibration = Column(Float, default=0.0)
    temperature = Column(Float, default=25.0)
    humidity = Column(Float, default=60.0)
    latitude = Column(Float, default=0.0)
    longitude = Column(Float, default=0.0)
    altitude = Column(Float, default=0.0)
    satellites = Column(Integer, default=0)
    risk_code = Column(Integer, default=0)
    risk_level = Column(String(20), default='LOW')
    risk_score = Column(Float, default=0.0)
    factor_of_safety = Column(Float, default=2.1)
    predicted_level_30m = Column(String(20), default='LOW')
    predicted_score_30m = Column(Float, default=0.0)
    creep_status = Column(String(30), default='STABLE')
    pump_status = Column(Boolean, default=False)
    buzzer_status = Column(Boolean, default=False)
    led_status = Column(String(20), default='GREEN')
    sensor_status = Column(Text, nullable=True)

    def to_dict(self):
        try:
            sensor_status = json.loads(self.sensor_status) if self.sensor_status else None
        except (TypeError, ValueError):
            sensor_status = None
        return {
            'id': self.id,
            'timestamp': f'{self.timestamp.isoformat()}Z' if self.timestamp else None,
            'soil_moisture_1': round(self.soil_moisture_1 or 0.0, 1),
            'soil_moisture_2': round(self.soil_moisture_2 or 0.0, 1),
            'soil_1_raw': self.soil_1_raw,
            'soil_2_raw': self.soil_2_raw,
            'soil_moisture_avg': round(self.soil_moisture_avg or 0.0, 1),
            'soil_saturation_rate': round(self.soil_saturation_rate or 0.0, 2),
            'rain_intensity': round(self.rain_intensity or 0.0, 1),
            'rain_raw': self.rain_raw,
            'rain_duration_min': round(self.rain_duration_min or 0.0, 1),
            'mpu1_pitch': round(self.mpu1_pitch or 0.0, 2),
            'mpu1_roll': round(self.mpu1_roll or 0.0, 2),
            'mpu2_pitch': round(self.mpu2_pitch or 0.0, 2),
            'mpu2_roll': round(self.mpu2_roll or 0.0, 2),
            'max_tilt': round(self.max_tilt or 0.0, 2),
            'tilt_velocity': round(self.tilt_velocity or 0.0, 3),
            'tilt_acceleration': round(self.tilt_acceleration or 0.0, 4),
            'vibration': round(self.vibration or 0.0, 3),
            'temperature': round(self.temperature or 0.0, 1),
            'humidity': round(self.humidity or 0.0, 1),
            'latitude': round(self.latitude or 0.0, 6),
            'longitude': round(self.longitude or 0.0, 6),
            'altitude': round(self.altitude or 0.0, 1),
            'satellites': self.satellites or 0,
            'risk_code': self.risk_code,
            'risk_level': self.risk_level,
            'risk_score': round(self.risk_score or 0.0, 1),
            'factor_of_safety': round(self.factor_of_safety or 2.1, 2),
            'predicted_level_30m': self.predicted_level_30m or 'LOW',
            'predicted_score_30m': round(self.predicted_score_30m or 0.0, 1),
            'creep_status': self.creep_status or 'STABLE',
            'pump_status': bool(self.pump_status),
            'buzzer_status': bool(self.buzzer_status),
            'led_status': self.led_status,
            'sensor_status': sensor_status,
        }

class Alert(Base):
    __tablename__ = 'alerts'

    id = Column(Integer, primary_key=True, autoincrement=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    level = Column(String(20), default='WARNING')
    message = Column(Text, nullable=False)
    risk_score = Column(Float, default=0.0)

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'level': self.level,
            'message': self.message,
            'risk_score': round(self.risk_score or 0.0, 1)
        }

class VulnerablePerson(Base):
    __tablename__ = 'vulnerable_person'
    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(100))
    address = Column(String(200))
    disability_type = Column(String(100))
    contact_phone = Column(String(20))
    lat = Column(Float)
    lng = Column(Float)
    created_at = Column(DateTime, default=datetime.utcnow)

class Responder(Base):
    __tablename__ = 'responder'
    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(100))
    skill = Column(String(100))
    phone = Column(String(20))
    lat = Column(Float)
    lng = Column(Float)
    status = Column(String(20), default='available')
    created_at = Column(DateTime, default=datetime.utcnow)

class EvacuationRecord(Base):
    __tablename__ = 'evacuation_record'
    id = Column(Integer, primary_key=True, autoincrement=True)
    person_name = Column(String(100))
    timestamp = Column(DateTime, default=datetime.utcnow)
    shelter_id = Column(Integer)
    method = Column(String(20), default='manual')

class MissingPerson(Base):
    __tablename__ = 'missing_person'
    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(100))
    age = Column(Integer)
    last_seen_location = Column(String(200))
    reported_by = Column(String(100))
    status = Column(String(20), default='missing')
    photo_url = Column(String(300))
    created_at = Column(DateTime, default=datetime.utcnow)

class AuditLog(Base):
    __tablename__ = 'audit_log'
    id = Column(Integer, primary_key=True, autoincrement=True)
    user_email = Column(String(100))
    action = Column(String(100))
    target = Column(String(200))
    old_value = Column(Text)
    new_value = Column(Text)
    timestamp = Column(DateTime, default=datetime.utcnow)

class Drill(Base):
    __tablename__ = 'drill'
    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(100))
    scheduled_at = Column(DateTime)
    location = Column(String(200))
    participants_count = Column(Integer, default=0)
    status = Column(String(20), default='scheduled')

class CommunityReport(Base):
    __tablename__ = 'community_report'
    id = Column(Integer, primary_key=True, autoincrement=True)
    report_text = Column(Text)
    audio_url = Column(String(300))
    photo_url = Column(String(300))
    translated_text = Column(Text)
    lat = Column(Float)
    lng = Column(Float)
    timestamp = Column(DateTime, default=datetime.utcnow)

def init_db():
    Base.metadata.create_all(bind=engine)
    inspector = inspect(engine)
    identifier = engine.dialect.identifier_preparer
    with engine.begin() as connection:
        for table in Base.metadata.sorted_tables:
            existing_columns = {column['name'] for column in inspector.get_columns(table.name)}
            for column in table.columns:
                if column.name in existing_columns:
                    continue
                if column.primary_key:
                    raise RuntimeError(
                        f'Cannot add missing primary-key column {table.name}.{column.name} to the existing database.'
                    )

                column_type = column.type.compile(dialect=engine.dialect)
                definition = f'{identifier.quote(column.name)} {column_type}'
                if column.default is not None and not callable(column.default.arg):
                    compiler = engine.dialect.statement_compiler(engine.dialect, None)
                    default_sql = compiler.render_literal_value(column.default.arg, column.type)
                    definition += f' DEFAULT {default_sql}'
                if not column.nullable:
                    if column.default is None or callable(column.default.arg):
                        raise RuntimeError(
                            f'Cannot safely migrate required column {table.name}.{column.name} without a constant default.'
                        )
                    definition += ' NOT NULL'

                connection.exec_driver_sql(
                    f'ALTER TABLE {identifier.quote(table.name)} ADD COLUMN {definition}'
                )
                existing_columns.add(column.name)

def get_db():
    return SessionLocal()
