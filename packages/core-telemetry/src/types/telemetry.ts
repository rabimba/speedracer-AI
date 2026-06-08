export type SessionMode = 'telemetry' | 'device_test' | 'camera_direct';
export type TelemetrySourceKind =
  | 'synthetic'
  | 'phone_imu_gps'
  | 'racebox_ble'
  | 'obd_bluetooth'
  | 'racebox_obd_fusion'
  | 'aim_can_usb';
export type ObdTransportPreference = 'auto' | 'bluetooth' | 'usb';

export interface TelemetryFrame {
  time: number;          // seconds from session start
  latitude: number;
  longitude: number;
  altitude?: number;
  speed: number;         // mph
  rpm?: number;
  throttle: number;      // 0-100
  brake: number;         // 0-100
  steering?: number;     // degrees
  gLat: number;          // lateral G
  gLong: number;         // longitudinal G
  gear?: number;
  distance?: number;     // cumulative meters
  coolantTempC?: number;
  oilTempC?: number;
  vehicleDiagnostics?: VehicleDiagnostics;
  canVehicleDiagnostics?: CanVehicleDiagnostics;
  sourceMode?: SessionMode;
  telemetrySource?: TelemetrySourceKind;
  sourceHealth?: TelemetrySourceHealth;
  vision?: VisionFeatureSnapshot;
}

export interface VehicleDiagnostics {
  engineLoadPercent?: number;
  mafGramsPerSecond?: number;
  intakeTempC?: number;
  timingAdvanceDegrees?: number;
  shortFuelTrim1Percent?: number;
  longFuelTrim1Percent?: number;
  shortFuelTrim2Percent?: number;
  longFuelTrim2Percent?: number;
  o2Bank1Sensor1Volts?: number;
  o2Bank2Sensor1Volts?: number;
}

export interface CanVehicleDiagnostics {
  waterPressurePsi?: number;
  oilPressurePsi?: number;
  brakePressureRaw?: number;
  brakePressurePsi?: number;
  brakePressureZeroOffsetRaw?: number;
  brakePressureCalibratedPsi?: number;
  brakePressureZeroOffsetPsi?: number;
  pedalPositionRaw?: number;
  pedalPositionPercent?: number;
  brakeSwitchRaw?: number;
  brakeSwitchApplied?: boolean;
  rollRateDegPerSec?: number;
  pitchRateDegPerSec?: number;
  yawRateDegPerSec?: number;
  steeringAngleDeg?: number;
  lateralG?: number;
  inlineG?: number;
  verticalG?: number;
  fuelLevelGal?: number;
  batteryVoltage?: number;
  wheelSpeedFrontLeftMph?: number;
  wheelSpeedFrontRightMph?: number;
  wheelSpeedRearLeftMph?: number;
  wheelSpeedRearRightMph?: number;
  ecuSpeedMph?: number;
  gpsSpeedMph?: number;
  outsideTempC?: number;
  waterTempC?: number;
  engineOilTempC?: number;
  oilFilterTempC?: number;
  dscRegActive?: boolean;
  gearRaw?: number;
  frameAgesMs?: Record<string, number>;
  frameStale?: Record<string, boolean>;
  rawFrameSamples?: Record<string, string>;
}

export interface TelemetrySourceHealth {
  status: string;
  motionSource?: 'racebox' | 'phone' | string;
  motionConnected?: boolean;
  motionFixGood?: boolean;
  motionSampleAgeMs?: number;
  fallbackStage?: 'full' | 'racebox_only' | 'phone_obd_fusion' | 'phone_only' | 'no_live_data' | string;
  degradedReason?: string;
  phoneMotionConnected?: boolean;
  phoneMotionFixGood?: boolean;
  phoneMotionSampleAgeMs?: number;
  raceBoxConnected?: boolean;
  raceBoxFixGood?: boolean;
  raceBoxFixStatus?: number;
  raceBoxSatellites?: number;
  raceBoxSampleAgeMs?: number;
  obdConnected?: boolean;
  obdSampleAgeMs?: number;
  obdStale?: boolean;
  obdSpeedDeltaMph?: number;
  obdTransport?: string;
  obdSupportedPids?: string[];
  obdReconnectCount?: number;
  obdChannelAgesMs?: Record<string, number>;
  obdChannelStale?: Record<string, boolean>;
  canConnected?: boolean;
  canFrameAgesMs?: Record<string, number>;
  canFrameStale?: Record<string, boolean>;
  canFrameRatesHz?: Record<string, number>;
  canDecodeErrors?: number;
  usbDeviceName?: string;
  rawCanSample?: string;
  rawCanSamplesById?: Record<string, string>;
  signUnverified?: boolean;
}

export interface VisionFeatureSnapshot {
  timestamp: number;
  averageLuma: number;
  motionEnergy: number;
  lateralBalance: number;
  verticalBalance: number;
  centerContrast: number;
  framesPerSecond: number;
}

export interface GpsSSEPoint {
  time: string | number;
  lat: number;
  lon: number;
  alt?: number;
  speed: number;         // m/s or mph
  speed_mps?: number;
  climb?: number;
  track?: number;        // heading
  mode?: number;
  brake?: number;
  throttle?: number;
  rpm?: number;
  gear?: number;
  steering?: number;
  gLat?: number;
  gLong?: number;
}
