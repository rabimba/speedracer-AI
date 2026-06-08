import {
  SONOMA_RACEWAY,
  type TelemetryFrame,
  analyzeDeltaToTarget,
  detectBrakeDegradation,
  evaluateBiometricLoad,
} from '@trustable/core-telemetry';

export interface PaddockSession {
  id: string;
  trackName: string;
  startedAt: number;
  frames: TelemetryFrame[];
}

export function buildDemoSession(): PaddockSession {
  const frames = [
    frame(8.2, 300, 87, 42, 0, 0.12, -0.45),
    frame(10.1, 390, 65, 2, 10, 0.98, -0.04),
    frame(20.2, 640, 49, 0, 35, 0.94, -0.12),
    frame(52.0, 1760, 61, 0, 18, 1.04, 0.01),
    frame(67.5, 2190, 47, 0, 14, 0.96, -0.02),
    frame(101.2, 3700, 58, 0, 42, 0.95, -0.2),
    frame(110.1, 3990, 78, 0, 58, 0.88, 0.12),
  ];

  return {
    id: 'demo-sonoma-session',
    trackName: SONOMA_RACEWAY.name,
    startedAt: Date.now() - 900_000,
    frames,
  };
}

export function summarizeSession(session: PaddockSession) {
  const deltas = session.frames.map((telemetryFrame) => analyzeDeltaToTarget(telemetryFrame, undefined, SONOMA_RACEWAY));
  const brakeHealth = detectBrakeDegradation([
    { timestamp: 1, brakePressurePsi: 500, fluidTempC: 182, longitudinalG: -0.95, speedMph: 86 },
    { timestamp: 2, brakePressurePsi: 520, fluidTempC: 188, longitudinalG: -0.96, speedMph: 84 },
    { timestamp: 3, brakePressurePsi: 535, fluidTempC: 194, longitudinalG: -0.92, speedMph: 82 },
    { timestamp: 4, brakePressurePsi: 650, fluidTempC: 224, longitudinalG: -0.64, speedMph: 80 },
    { timestamp: 5, brakePressurePsi: 690, fluidTempC: 236, longitudinalG: -0.58, speedMph: 78 },
    { timestamp: 6, brakePressurePsi: 720, fluidTempC: 248, longitudinalG: -0.52, speedMph: 76 },
  ]);
  const biometric = evaluateBiometricLoad(
    { ...session.frames[0], gLat: 0.06, gLong: 0.03, steering: 64 },
    { timestamp: Date.now(), heartRateBpm: 166, hrvMs: 22 },
    0.31,
  );

  return {
    deltas,
    worstDelta: deltas.slice().sort((a, b) => a.apexSpeedDelta - b.apexSpeedDelta)[0],
    brakeHealth,
    biometric,
  };
}

function frame(
  time: number,
  distance: number,
  speed: number,
  brake: number,
  throttle: number,
  gLat: number,
  gLong: number,
): TelemetryFrame {
  return {
    time,
    distance,
    latitude: 38.161,
    longitude: -122.454,
    speed,
    brake,
    throttle,
    gLat,
    gLong,
    steering: gLat * 28,
    telemetrySource: 'racebox_obd_fusion',
    sourceHealth: {
      status: 'live',
      fallbackStage: 'full',
      motionSource: 'racebox',
      raceBoxConnected: true,
      raceBoxFixGood: true,
      obdConnected: true,
      canConnected: true,
    },
  };
}
