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
          telemetrySource: 'phone_imu_gps',
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
          telemetrySource: 'phone_imu_gps',
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
          telemetrySource: 'phone_imu_gps',
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
          telemetrySource: 'phone_imu_gps',
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
    expect(context).toContain('Primary telemetry source: phone_imu_gps');
    expect(context).toContain('Decision path counts:');
    expect(context).toContain('feedforward: 1');
    expect(context).toContain('edge: 1');
    expect(context).toContain('Turn 3');
    expect(context).toContain('Turn 11');
    expect(context).toContain('Commit to throttle once the late apex is done.');
  });
});
