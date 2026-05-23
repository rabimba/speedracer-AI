import { describe, expect, it } from 'vitest';
import { SONOMA_RACEWAY } from '../../data/trackData';
import { buildRecordedSessionAnalysisContext } from '../../utils/sessionAnalysis';
import type { RecordedSessionArtifact } from '../../types';

describe('recorded session analysis context', () => {
  it('summarizes fused session evidence for Gemini cold-path analysis', () => {
    const turn3 = SONOMA_RACEWAY.corners.find((corner) => corner.id === 3)!;
    const turn11 = SONOMA_RACEWAY.corners.find((corner) => corner.id === 11)!;

    const session: RecordedSessionArtifact = {
      id: 'session-1',
      mode: 'telemetry',
      trackName: SONOMA_RACEWAY.name,
      coachId: 'superaj',
      startedAt: 1_000,
      endedAt: 21_000,
      summary: {
        sessionId: 'session-1',
        mode: 'telemetry',
        trackName: SONOMA_RACEWAY.name,
        coachId: 'superaj',
        frameCount: 4,
        decisionCount: 3,
        durationSeconds: 20,
      },
      sessionGoals: [
        {
          id: 'goal-braking',
          focus: 'braking',
          description: 'Harder initial squeeze and cleaner release.',
          source: 'pre_race_chat',
          prioritizedActions: ['THRESHOLD', 'SPIKE_BRAKE'],
        },
      ],
      frames: [
        {
          time: 0,
          latitude: 38.1606,
          longitude: -122.4546,
          speed: 92,
          throttle: 25,
          brake: 70,
          gLat: 0.85,
          gLong: -0.65,
          distance: turn11.apexDist - 30,
          sourceMode: 'telemetry',
          telemetrySource: 'racebox_obd_fusion',
          rpm: 5200,
          coolantTempC: 88,
          oilTempC: 96,
          vehicleDiagnostics: {
            engineLoadPercent: 78,
            mafGramsPerSecond: 94.5,
            intakeTempC: 34,
            timingAdvanceDegrees: 18,
            shortFuelTrim1Percent: 2.1,
            longFuelTrim1Percent: -1.4,
            o2Bank1Sensor1Volts: 0.68,
          },
          sourceHealth: {
            status: 'RaceBox fix OK | OBDLink live',
            motionSource: 'racebox',
            motionConnected: true,
            motionFixGood: true,
            fallbackStage: 'full',
            raceBoxConnected: true,
            raceBoxFixGood: true,
            raceBoxSatellites: 12,
            obdConnected: true,
            obdStale: false,
            obdSpeedDeltaMph: -1.2,
          },
          vision: {
            timestamp: 1_000,
            averageLuma: 0.52,
            motionEnergy: 0.44,
            lateralBalance: -0.08,
            verticalBalance: 0.11,
            centerContrast: 0.36,
            framesPerSecond: 9.8,
          },
        },
        {
          time: 1,
          latitude: 38.1607,
          longitude: -122.4545,
          speed: 58,
          throttle: 10,
          brake: 82,
          gLat: 1.12,
          gLong: -0.91,
          distance: turn11.apexDist + 15,
          sourceMode: 'telemetry',
          telemetrySource: 'racebox_obd_fusion',
          rpm: 6100,
          coolantTempC: 89,
          oilTempC: 98,
          sourceHealth: {
            status: 'RaceBox fix OK | OBDLink live',
            motionSource: 'racebox',
            motionConnected: true,
            motionFixGood: true,
            fallbackStage: 'full',
            raceBoxConnected: true,
            raceBoxFixGood: true,
            raceBoxSatellites: 12,
            obdConnected: true,
            obdStale: false,
            obdSpeedDeltaMph: 0.8,
          },
        },
        {
          time: 8,
          latitude: 38.1615,
          longitude: -122.4520,
          speed: 64,
          throttle: 34,
          brake: 22,
          gLat: 1.24,
          gLong: -0.18,
          distance: turn3.apexDist - 20,
          sourceMode: 'telemetry',
          telemetrySource: 'racebox_obd_fusion',
          rpm: 5800,
          coolantTempC: 90,
          oilTempC: 100,
          sourceHealth: {
            status: 'RaceBox fix OK | OBDLink connected, stale sample',
            motionSource: 'racebox',
            motionConnected: true,
            motionFixGood: true,
            fallbackStage: 'racebox_only',
            degradedReason: 'obd_unavailable_or_stale',
            raceBoxConnected: true,
            raceBoxFixGood: true,
            raceBoxSatellites: 11,
            obdConnected: true,
            obdStale: true,
          },
        },
        {
          time: 9,
          latitude: 38.1616,
          longitude: -122.4518,
          speed: 71,
          throttle: 78,
          brake: 0,
          gLat: 1.06,
          gLong: 0.28,
          distance: turn3.apexDist + 18,
          sourceMode: 'telemetry',
          telemetrySource: 'racebox_obd_fusion',
          rpm: 6900,
          coolantTempC: 91,
          oilTempC: 103,
          sourceHealth: {
            status: 'RaceBox unavailable | OBDLink live',
            motionSource: 'phone',
            motionConnected: true,
            motionFixGood: true,
            fallbackStage: 'phone_obd_fusion',
            degradedReason: 'racebox_unavailable_using_phone_motion',
            phoneMotionConnected: true,
            phoneMotionFixGood: true,
            raceBoxConnected: false,
            raceBoxFixGood: false,
            raceBoxSatellites: 0,
            obdConnected: true,
            obdStale: false,
            obdSpeedDeltaMph: 1.0,
          },
        },
      ],
      decisions: [
        {
          path: 'feedforward',
          text: 'Turn 11: squeeze the brake, then release cleanly.',
          priority: 1,
          cornerPhase: 'BRAKE_ZONE',
          timestamp: 2_000,
          backend: 'deterministic',
        },
        {
          path: 'hot',
          action: 'THROTTLE',
          text: 'Commit to throttle once the late apex is done.',
          priority: 1,
          cornerPhase: 'EXIT',
          timestamp: 11_000,
          backend: 'deterministic',
        },
        {
          path: 'edge',
          action: 'STABILIZE',
          text: 'Stabilize the platform before full release.',
          priority: 1,
          cornerPhase: 'MID_CORNER',
          timestamp: 14_000,
          backend: 'litertlm',
          confidence: 0.82,
        },
      ],
    };

    const context = buildRecordedSessionAnalysisContext(session, SONOMA_RACEWAY);

    expect(context).toContain(`Track: ${SONOMA_RACEWAY.name}`);
    expect(context).toContain('Primary telemetry source: racebox_obd_fusion');
    expect(context).toContain('Max RPM: 6900');
    expect(context).toContain('Hardware trust summary:');
    expect(context).toContain('Vehicle-health diagnostics:');
    expect(context).toContain('Diagnostic frames: 1/4');
    expect(context).toContain('MAF max: 94.5 g/s');
    expect(context).toContain('RaceBox good fix rate: 75%');
    expect(context).toContain('OBD stale rate: 25%');
    expect(context).toContain('Fallback stages: full=2, racebox_only=1, phone_obd_fusion=1');
    expect(context).toContain('Motion sources: racebox=3, phone=1');
    expect(context).toContain('Phone fallback frames: 1/4');
    expect(context).toContain('Treat telemetrySource as the transport wrapper');
    expect(context).toContain('Lap and pace summary:');
    expect(context).toContain('Decision path counts:');
    expect(context).toContain('Pre-race goals:');
    expect(context).toContain('Harder initial squeeze and cleaner release.');
    expect(context).toContain('feedforward: 1');
    expect(context).toContain('edge: 1');
    expect(context).toContain('Turn 3');
    expect(context).toContain('Turn 11');
    expect(context).toContain('Commit to throttle once the late apex is done.');
    expect(context).toContain('Identify the biggest lap-time losses');
  });

  it('maps multi-lap cumulative distance back to corner windows', () => {
    const turn11 = SONOMA_RACEWAY.corners.find((corner) => corner.id === 11)!;
    const session: RecordedSessionArtifact = {
      id: 'session-2',
      mode: 'telemetry',
      trackName: SONOMA_RACEWAY.name,
      coachId: 'superaj',
      startedAt: 1_000,
      endedAt: 3_000,
      summary: {
        sessionId: 'session-2',
        mode: 'telemetry',
        trackName: SONOMA_RACEWAY.name,
        coachId: 'superaj',
        frameCount: 1,
        decisionCount: 0,
        durationSeconds: 2,
      },
      sessionGoals: [],
      frames: [
        {
          time: 1,
          latitude: 0,
          longitude: 0,
          speed: 61,
          throttle: 20,
          brake: 76,
          gLat: 1.05,
          gLong: -0.8,
          distance: SONOMA_RACEWAY.length + turn11.apexDist + 12,
          sourceMode: 'telemetry',
          telemetrySource: 'racebox_obd_fusion',
        },
      ],
      decisions: [],
    };

    const context = buildRecordedSessionAnalysisContext(session, SONOMA_RACEWAY);

    expect(context).toContain('Turn 11');
    expect(context).toContain('minSpeed=61mph');
  });

  it('summarizes CAN frame freshness and calibration evidence separately from RaceBox-only labels', () => {
    const session: RecordedSessionArtifact = {
      id: 'session-can',
      mode: 'telemetry',
      trackName: SONOMA_RACEWAY.name,
      coachId: 'superaj',
      startedAt: 1_000,
      endedAt: 2_000,
      summary: {
        sessionId: 'session-can',
        mode: 'telemetry',
        trackName: SONOMA_RACEWAY.name,
        coachId: 'superaj',
        frameCount: 2,
        decisionCount: 0,
        durationSeconds: 1,
      },
      sessionGoals: [],
      frames: [
        {
          time: 0,
          latitude: 38.16272,
          longitude: -122.455,
          speed: 92,
          rpm: 6400,
          throttle: 48.5,
          brake: 50,
          steering: -8,
          gLat: 0.82,
          gLong: -0.41,
          telemetrySource: 'aim_can_usb',
          sourceMode: 'telemetry',
          canVehicleDiagnostics: {
            brakePressureRaw: 6000,
            brakePressurePsi: 600,
            brakePressureZeroOffsetRaw: 10,
            brakePressureCalibratedPsi: 599,
            brakePressureZeroOffsetPsi: 1,
            pedalPositionRaw: 4850,
            pedalPositionPercent: 48.5,
            oilPressurePsi: 62,
            oilFilterTempC: 105,
            batteryVoltage: 13.8,
            frameAgesMs: { '0x420': 40, '0x423': 35 },
            frameStale: { '0x420': false, '0x423': false, '0x452': false },
            rawFrameSamples: { '0x422': 't42287017621201000000' },
          },
          sourceHealth: {
            status: 'AiM CAN USB live',
            motionSource: 'aim_can',
            fallbackStage: 'aim_can_full',
            canConnected: true,
            canFrameAgesMs: { '0x420': 40, '0x423': 35 },
            canFrameStale: { '0x420': false, '0x423': false, '0x452': false },
            canFrameRatesHz: { '0x420': 10, '0x423': 50 },
            rawCanSample: 't4238B0FF78005200D7FF',
            rawCanSamplesById: { '0x422': 't42287017621201000000', '0x423': 't4238B0FF78005200D7FF' },
            signUnverified: true,
          },
        },
        {
          time: 1,
          latitude: 38.1628,
          longitude: -122.4551,
          speed: 93,
          rpm: 6500,
          throttle: 52,
          brake: 0,
          gLat: 0.5,
          gLong: 0.12,
          telemetrySource: 'aim_can_usb',
          sourceMode: 'telemetry',
          sourceHealth: {
            status: 'AiM CAN motion stale | RaceBox fallback fix OK',
            motionSource: 'racebox',
            fallbackStage: 'aim_can_racebox_motion',
            canConnected: true,
            canFrameStale: { '0x420': false, '0x423': true, '0x452': true },
            signUnverified: true,
          },
        },
      ],
      decisions: [],
    };

    const context = buildRecordedSessionAnalysisContext(session, SONOMA_RACEWAY);

    expect(context).toContain('Primary telemetry source: aim_can_usb');
    expect(context).toContain('CAN/AiM diagnostics:');
    expect(context).toContain('CAN connected rate: 100%');
    expect(context).toContain('CAN sign-unverified frames: 2/2');
    expect(context).toContain('Fallback stages: aim_can_full=1, aim_can_racebox_motion=1');
    expect(context).toContain('Motion sources: aim_can=1, racebox=1');
    expect(context).toContain('CAN fallback stages: aim_can_full=1, aim_can_racebox_motion=1');
    expect(context).toContain('CAN stale frame counts: 0x423=1, 0x452=1');
    expect(context).toContain('Brake pressure max: 600 psi');
    expect(context).toContain('Brake calibrated max: 599 psi');
    expect(context).toContain('Brake raw max: 6000');
    expect(context).toContain('Pedal raw max: 4850');
    expect(context).toContain('Observed CAN IDs: 0x422, 0x423');
    expect(context).toContain('Raw CAN samples: t4238B0FF78005200D7FF');
    expect(context).toContain('Latest raw samples by ID: 0x422=t42287017621201000000, 0x423=t4238B0FF78005200D7FF');
  });
});
