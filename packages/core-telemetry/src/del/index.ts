import type { CornerPhase } from '../types/cornerPhase.js';
import type { TelemetryFrame } from '../types/telemetry.js';
import type { Corner, Track } from '../types/track.js';
import { SONOMA_GOLD_TRACE, type ReferenceTraceSample } from '../traces/sonomaGoldTrace.js';

export type CoachAction =
  | 'THRESHOLD' | 'TRAIL_BRAKE' | 'BRAKE' | 'WAIT'
  | 'TURN_IN' | 'COMMIT' | 'ROTATE' | 'APEX'
  | 'THROTTLE' | 'PUSH' | 'FULL_THROTTLE'
  | 'STABILIZE' | 'MAINTAIN' | 'COAST'
  | 'HESITATION' | 'OVERSTEER_RECOVERY'
  | 'EARLY_THROTTLE' | 'LIFT_MID_CORNER' | 'SPIKE_BRAKE' | 'COGNITIVE_OVERLOAD'
  | 'HUSTLE';

export type LearningPlanFocus = 'late_apex' | 'braking' | 'throttle' | 'vision' | 'line_use' | 'smoothness' | 'custom';

export interface LearningPlanTarget {
  cornerId: number;
  cornerName: string;
  phases: CornerPhase[];
  targetDelta: string;
  allowedCueActions: CoachAction[];
}

export interface LearningPlan {
  schemaVersion: 1;
  id: string;
  driverId: string;
  trackName: string;
  focus: LearningPlanFocus;
  objective: string;
  generatedAt: string;
  expiresAt: string;
  targets: LearningPlanTarget[];
  ignoredActions: CoachAction[];
  maxCueWords: number;
  notes?: string;
}

export interface LearningPlanEnvelope {
  plan: LearningPlan;
  digest: string;
  signedAt: string;
  signer: string;
  signature?: string;
}

export interface LearningPlanValidationResult {
  ok: boolean;
  errors: string[];
  sizeBytes: number;
  expectedDigest?: string;
}

export interface DeltaAnalysis {
  frame: TelemetryFrame;
  reference: ReferenceTraceSample;
  corner: Corner | null;
  phase: CornerPhase;
  brakeDelta: number;
  apexSpeedDelta: number;
  throttleTimingDelta: number;
  trackUseDelta: number;
  slipAngleEstimate: number;
  tractionCircleUtilization: number;
  recommendedAction: CoachAction;
  cue: string;
}

export interface BiometricSample {
  timestamp: number;
  heartRateBpm: number;
  hrvMs?: number;
}

export type BiometricLoadState = 'normal' | 'expected_performance_stress' | 'panic_overload';

export interface BiometricLoadResult {
  state: BiometricLoadState;
  cue: string | null;
  suppressStrategicAudio: boolean;
}

export interface BrakeHealthSample {
  timestamp: number;
  brakePressurePsi?: number;
  brakePedalPercent?: number;
  fluidTempC?: number;
  longitudinalG: number;
  speedMph: number;
}

export interface BrakeDegradationResult {
  degraded: boolean;
  severity: 'none' | 'warning' | 'critical';
  efficiencyDropPercent: number;
  cue: string | null;
}

export interface AudioDebounceState {
  lastCueAtMs: number;
  silenceWindowMs?: number;
}

const MAX_PLAN_BYTES = 50 * 1024;
const DEFAULT_MAX_CUE_WORDS = 7;
const PHASE_ORDER: Record<CornerPhase, number> = {
  STRAIGHT: 0,
  BRAKE_ZONE: 1,
  TURN_IN: 2,
  MID_CORNER: 3,
  APEX: 4,
  EXIT: 5,
  ACCELERATION: 6,
};

export function referenceTraceForTrack(trackName: string): ReferenceTraceSample[] {
  return trackName.toLowerCase().includes('sonoma') ? SONOMA_GOLD_TRACE : [];
}

export function findNearestReferenceSample(
  frame: TelemetryFrame,
  referenceTrace: ReferenceTraceSample[],
): ReferenceTraceSample | null {
  if (referenceTrace.length === 0) return null;
  const frameDistance = typeof frame.distance === 'number' ? frame.distance : null;
  const frameTime = frame.time;
  return referenceTrace.reduce((best, sample) => {
    const bestMetric = referenceDistance(frameDistance, frameTime, best);
    const sampleMetric = referenceDistance(frameDistance, frameTime, sample);
    return sampleMetric < bestMetric ? sample : best;
  }, referenceTrace[0]);
}

export function analyzeDeltaToTarget(
  frame: TelemetryFrame,
  referenceTrace: ReferenceTraceSample[] = SONOMA_GOLD_TRACE,
  track?: Track | null,
): DeltaAnalysis {
  const reference = findNearestReferenceSample(frame, referenceTrace);
  if (!reference) {
    throw new Error('DEL requires a reference trace or best-lap target before generating cues.');
  }
  const corner = reference.cornerId == null
    ? null
    : track?.corners.find((candidate) => candidate.id === reference.cornerId) ?? null;
  const brakeDelta = frame.brake - reference.brake;
  const apexSpeedDelta = frame.speed - reference.speed;
  const throttleTimingDelta = frame.throttle - reference.throttle;
  const trackUse = estimateTrackUse(frame, reference);
  const trackUseDelta = trackUse - reference.trackUse;
  const slipAngleEstimate = estimateSlipAngle(frame);
  const tractionCircleUtilization = estimateTractionCircle(frame);
  const recommendedAction = chooseDeltaAction({
    phase: reference.phase,
    brakeDelta,
    apexSpeedDelta,
    throttleTimingDelta,
    trackUseDelta,
    tractionCircleUtilization,
  });
  const cue = cueForAction(recommendedAction, reference.phase, corner);

  return {
    frame,
    reference,
    corner,
    phase: reference.phase,
    brakeDelta,
    apexSpeedDelta,
    throttleTimingDelta,
    trackUseDelta,
    slipAngleEstimate,
    tractionCircleUtilization,
    recommendedAction,
    cue,
  };
}

export function sanitizeOnTrackCue(text: string, maxWords = DEFAULT_MAX_CUE_WORDS): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  if (!clean) return clean;
  const words = clean.split(' ');
  return words.slice(0, Math.max(1, maxWords)).join(' ');
}

export function shouldSuppressCueForPhase(cuePhase: CornerPhase, currentPhase: CornerPhase): boolean {
  if (cuePhase === 'BRAKE_ZONE' || cuePhase === 'TURN_IN') {
    return PHASE_ORDER[currentPhase] >= PHASE_ORDER.APEX;
  }
  if (cuePhase === 'APEX') {
    return currentPhase === 'EXIT' || currentPhase === 'ACCELERATION';
  }
  return false;
}

export function canDeliverAudioCue(
  priority: 0 | 1 | 2 | 3,
  nowMs: number,
  state: AudioDebounceState,
): boolean {
  if (priority === 0) return true;
  const silenceWindowMs = state.silenceWindowMs ?? 4000;
  return nowMs - state.lastCueAtMs >= silenceWindowMs;
}

export function evaluateBiometricLoad(
  frame: TelemetryFrame,
  sample: BiometricSample | null,
  inputSmoothness = 1,
): BiometricLoadResult {
  if (!sample) return { state: 'normal', cue: null, suppressStrategicAudio: false };
  const highHr = sample.heartRateBpm >= 150;
  const lowHrv = typeof sample.hrvMs === 'number' && sample.hrvMs < 28;
  const highG = Math.abs(frame.gLat) >= 0.8 || Math.abs(frame.gLong) >= 0.75;
  const lowG = Math.abs(frame.gLat) < 0.25 && Math.abs(frame.gLong) < 0.25;
  const erraticInputs = inputSmoothness < 0.42 || Math.abs(frame.steering ?? 0) > 55;

  if (highHr && highG && !erraticInputs) {
    return { state: 'expected_performance_stress', cue: null, suppressStrategicAudio: false };
  }
  if ((highHr || lowHrv) && lowG && erraticInputs) {
    return { state: 'panic_overload', cue: 'Breathe. Relax hands. Eyes up.', suppressStrategicAudio: true };
  }
  return { state: 'normal', cue: null, suppressStrategicAudio: false };
}

export function detectBrakeDegradation(samples: BrakeHealthSample[]): BrakeDegradationResult {
  const usable = samples.filter((sample) =>
    sample.speedMph > 25 &&
    ((sample.brakePressurePsi ?? 0) > 80 || (sample.brakePedalPercent ?? 0) > 35)
  );
  if (usable.length < 6) {
    return { degraded: false, severity: 'none', efficiencyDropPercent: 0, cue: null };
  }
  const midpoint = Math.floor(usable.length / 2);
  const early = averageBrakeEfficiency(usable.slice(0, midpoint));
  const late = averageBrakeEfficiency(usable.slice(midpoint));
  const drop = early > 0 ? ((early - late) / early) * 100 : 0;
  const latestTemp = Math.max(...usable.map((sample) => sample.fluidTempC ?? 0));
  const critical = drop >= 28 || latestTemp >= 245;
  const warning = drop >= 15 || latestTemp >= 210;
  if (critical) {
    return {
      degraded: true,
      severity: 'critical',
      efficiencyDropPercent: drop,
      cue: 'Brake fade detected. Brake earlier or pit.',
    };
  }
  if (warning) {
    return {
      degraded: true,
      severity: 'warning',
      efficiencyDropPercent: drop,
      cue: 'Brake efficiency falling. Move marker back.',
    };
  }
  return { degraded: false, severity: 'none', efficiencyDropPercent: Math.max(0, drop), cue: null };
}

export function buildLearningPlan(input: Omit<LearningPlan, 'schemaVersion' | 'maxCueWords'> & { maxCueWords?: number }): LearningPlan {
  return {
    ...input,
    schemaVersion: 1,
    maxCueWords: input.maxCueWords ?? DEFAULT_MAX_CUE_WORDS,
    targets: input.targets.map((target) => ({
      ...target,
      allowedCueActions: Array.from(new Set(target.allowedCueActions)),
    })),
    ignoredActions: Array.from(new Set(input.ignoredActions)),
  };
}

export async function createLearningPlanEnvelope(
  plan: LearningPlan,
  signer = 'local-paddock',
): Promise<LearningPlanEnvelope> {
  return {
    plan,
    digest: await sha256Hex(stableStringify(plan)),
    signedAt: new Date().toISOString(),
    signer,
  };
}

export async function validateLearningPlanEnvelope(
  envelope: LearningPlanEnvelope,
  now = new Date(),
): Promise<LearningPlanValidationResult> {
  const errors = validateLearningPlan(envelope.plan, now);
  const expectedDigest = await sha256Hex(stableStringify(envelope.plan));
  if (envelope.digest !== expectedDigest) {
    errors.push('learning_plan_digest_mismatch');
  }
  const sizeBytes = byteLength(stableStringify(envelope));
  if (sizeBytes > MAX_PLAN_BYTES) {
    errors.push('learning_plan_exceeds_50kb');
  }
  return { ok: errors.length === 0, errors, sizeBytes, expectedDigest };
}

export function validateLearningPlan(plan: LearningPlan, now = new Date()): string[] {
  const errors: string[] = [];
  if (plan.schemaVersion !== 1) errors.push('unsupported_schema_version');
  if (!plan.id) errors.push('missing_plan_id');
  if (!plan.driverId) errors.push('missing_driver_id');
  if (!plan.trackName) errors.push('missing_track_name');
  if (plan.maxCueWords > DEFAULT_MAX_CUE_WORDS) errors.push('max_cue_words_exceeds_7');
  if (Number.isNaN(Date.parse(plan.generatedAt))) errors.push('invalid_generated_at');
  if (Number.isNaN(Date.parse(plan.expiresAt))) {
    errors.push('invalid_expires_at');
  } else if (new Date(plan.expiresAt).getTime() <= now.getTime()) {
    errors.push('learning_plan_expired');
  }
  if (plan.targets.length === 0) errors.push('missing_targets');
  for (const target of plan.targets) {
    if (target.phases.length === 0) errors.push(`target_${target.cornerId}_missing_phases`);
    if (target.allowedCueActions.length === 0) errors.push(`target_${target.cornerId}_missing_allowed_actions`);
  }
  return errors;
}

function chooseDeltaAction(input: {
  phase: CornerPhase;
  brakeDelta: number;
  apexSpeedDelta: number;
  throttleTimingDelta: number;
  trackUseDelta: number;
  tractionCircleUtilization: number;
}): CoachAction {
  if (input.tractionCircleUtilization > 1.14 && input.throttleTimingDelta > 15) return 'EARLY_THROTTLE';
  if (input.phase === 'BRAKE_ZONE' && input.apexSpeedDelta > 10 && input.brakeDelta < -15) return 'BRAKE';
  if (input.phase === 'APEX' && input.throttleTimingDelta > 18) return 'EARLY_THROTTLE';
  if (input.phase === 'EXIT' && input.trackUseDelta < -0.16) return 'PUSH';
  if (input.apexSpeedDelta < -12) return 'HUSTLE';
  if (input.brakeDelta > 25) return 'SPIKE_BRAKE';
  return 'MAINTAIN';
}

function cueForAction(action: CoachAction, phase: CornerPhase, corner: Corner | null): string {
  const prefix = corner?.name ? `${corner.name}: ` : '';
  switch (action) {
    case 'BRAKE':
      return sanitizeOnTrackCue(`${prefix}Brake now. Keep it straight.`);
    case 'EARLY_THROTTLE':
      return sanitizeOnTrackCue(`${prefix}Wait. Squeeze throttle later.`);
    case 'PUSH':
      return sanitizeOnTrackCue(`${prefix}Use more road out wide.`);
    case 'HUSTLE':
      return sanitizeOnTrackCue(`${prefix}Carry more speed next time.`);
    case 'SPIKE_BRAKE':
      return sanitizeOnTrackCue(`${prefix}Squeeze brake. No stab.`);
    case 'MAINTAIN':
      return sanitizeOnTrackCue(phase === 'APEX' ? 'Hold balance through apex.' : 'Stay smooth and committed.');
    default:
      return sanitizeOnTrackCue(`${prefix}Reset. Eyes up.`);
  }
}

function referenceDistance(
  frameDistance: number | null,
  frameTime: number,
  sample: ReferenceTraceSample,
): number {
  return frameDistance == null
    ? Math.abs(frameTime - sample.time)
    : Math.abs(frameDistance - sample.distance);
}

function estimateTrackUse(frame: TelemetryFrame, reference: ReferenceTraceSample): number {
  if (typeof frame.distance !== 'number') return reference.trackUse;
  const phaseBias = reference.phase === 'EXIT' ? 0.92 : 0.82;
  const gUse = Math.min(1, Math.abs(frame.gLat) / 1.25);
  return Math.max(0, Math.min(1, phaseBias * 0.35 + gUse * 0.65));
}

function estimateSlipAngle(frame: TelemetryFrame): number {
  const steering = Math.abs(frame.steering ?? 0);
  const yawProxy = Math.abs(frame.gLat) * 18;
  return Math.max(0, steering * 0.18 + yawProxy * 0.42);
}

function estimateTractionCircle(frame: TelemetryFrame): number {
  return Math.sqrt(frame.gLat * frame.gLat + frame.gLong * frame.gLong);
}

function averageBrakeEfficiency(samples: BrakeHealthSample[]): number {
  if (samples.length === 0) return 0;
  const total = samples.reduce((sum, sample) => {
    const pressure = sample.brakePressurePsi ?? (sample.brakePedalPercent ?? 0) * 9;
    if (pressure <= 0) return sum;
    return sum + Math.abs(sample.longitudinalG) / pressure;
  }, 0);
  return total / samples.length;
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .filter(([, entry]) => entry !== undefined)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, entry]) => `${JSON.stringify(key)}:${stableStringify(entry)}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).byteLength;
}
