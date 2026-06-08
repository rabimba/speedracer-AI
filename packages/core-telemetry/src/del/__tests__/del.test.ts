import { describe, expect, it } from 'vitest';
import {
  analyzeDeltaToTarget,
  buildLearningPlan,
  canDeliverAudioCue,
  createLearningPlanEnvelope,
  detectBrakeDegradation,
  evaluateBiometricLoad,
  sanitizeOnTrackCue,
  shouldSuppressCueForPhase,
  validateLearningPlanEnvelope,
} from '../index.js';
import { SONOMA_RACEWAY } from '../../tracks/index.js';
import type { TelemetryFrame } from '../../types/telemetry.js';

const baseFrame: TelemetryFrame = {
  time: 101.2,
  latitude: 38.161,
  longitude: -122.453,
  speed: 58,
  throttle: 42,
  brake: 0,
  gLat: 0.96,
  gLong: -0.2,
  distance: 3700,
  steering: 22,
};

describe('Domain Expertise Layer', () => {
  it('generates delta-based coaching from the Sonoma reference trace', () => {
    const analysis = analyzeDeltaToTarget(baseFrame, undefined, SONOMA_RACEWAY);

    expect(analysis.corner?.name).toBe('Turn 11');
    expect(analysis.phase).toBe('APEX');
    expect(analysis.apexSpeedDelta).toBe(16);
    expect(analysis.recommendedAction).toBe('EARLY_THROTTLE');
    expect(analysis.cue.split(/\s+/).length).toBeLessThanOrEqual(7);
  });

  it('suppresses stale entry cues once the car has reached apex or exit', () => {
    expect(shouldSuppressCueForPhase('BRAKE_ZONE', 'APEX')).toBe(true);
    expect(shouldSuppressCueForPhase('TURN_IN', 'EXIT')).toBe(true);
    expect(shouldSuppressCueForPhase('EXIT', 'ACCELERATION')).toBe(false);
  });

  it('enforces the non-P0 audio silence window', () => {
    expect(canDeliverAudioCue(1, 6000, { lastCueAtMs: 2500 })).toBe(false);
    expect(canDeliverAudioCue(2, 7000, { lastCueAtMs: 2500 })).toBe(true);
    expect(canDeliverAudioCue(0, 2600, { lastCueAtMs: 2500 })).toBe(true);
  });

  it('detects panic overload from biometrics only when vehicle load is low', () => {
    const result = evaluateBiometricLoad(
      { ...baseFrame, gLat: 0.05, gLong: 0.04, steering: 62 },
      { timestamp: Date.now(), heartRateBpm: 168, hrvMs: 20 },
      0.3,
    );

    expect(result.state).toBe('panic_overload');
    expect(result.suppressStrategicAudio).toBe(true);
    expect(sanitizeOnTrackCue(result.cue ?? '', 7)).toBe('Breathe. Relax hands. Eyes up.');
  });

  it('detects brake degradation from falling decel efficiency', () => {
    const early = Array.from({ length: 6 }, (_, index) => ({
      timestamp: index,
      brakePressurePsi: 500,
      fluidTempC: 180,
      longitudinalG: -1.0,
      speedMph: 82,
    }));
    const late = Array.from({ length: 6 }, (_, index) => ({
      timestamp: index + 6,
      brakePressurePsi: 700,
      fluidTempC: 252,
      longitudinalG: -0.55,
      speedMph: 80,
    }));

    const result = detectBrakeDegradation([...early, ...late]);

    expect(result.degraded).toBe(true);
    expect(result.severity).toBe('critical');
    expect(result.cue).toContain('Brake fade');
  });

  it('validates signed Learning Plan envelopes and rejects tampering', async () => {
    const plan = buildLearningPlan({
      id: 'plan-sonoma-late-apex',
      driverId: 'driver-1',
      trackName: 'Sonoma Raceway',
      focus: 'late_apex',
      objective: 'Only coach late apex turn-in timing.',
      generatedAt: '2026-05-28T00:00:00.000Z',
      expiresAt: '2026-06-28T00:00:00.000Z',
      targets: [{
        cornerId: 11,
        cornerName: 'Turn 11',
        phases: ['BRAKE_ZONE', 'TURN_IN'],
        targetDelta: 'turn in later than current trace',
        allowedCueActions: ['WAIT', 'TURN_IN'],
      }],
      ignoredActions: ['HUSTLE', 'PUSH'],
    });
    const envelope = await createLearningPlanEnvelope(plan);
    const valid = await validateLearningPlanEnvelope(envelope, new Date('2026-05-29T00:00:00.000Z'));
    const tampered = await validateLearningPlanEnvelope({
      ...envelope,
      plan: { ...plan, objective: 'Coach everything.' },
    }, new Date('2026-05-29T00:00:00.000Z'));

    expect(valid.ok).toBe(true);
    expect(valid.sizeBytes).toBeLessThan(50 * 1024);
    expect(tampered.ok).toBe(false);
    expect(tampered.errors).toContain('learning_plan_digest_mismatch');
  });
});
