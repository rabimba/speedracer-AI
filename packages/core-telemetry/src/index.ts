export type {
  SessionMode,
  TelemetrySourceKind,
  ObdTransportPreference,
  AimCanBitrate,
  TelemetryFrame,
  VehicleDiagnostics,
  CanVehicleDiagnostics,
  TelemetrySourceHealth,
  VisionFeatureSnapshot,
  GpsSSEPoint,
} from './types/telemetry.js';

export type { Corner, CornerDoctrine, Sector, Track } from './types/track.js';

export type { CornerPhase } from './types/cornerPhase.js';

export { haversineDistance, calculateHeading, isValidGps } from './geo/geoUtils.js';

export { CornerPhaseDetector } from './corner/cornerPhaseDetector.js';
export type { CornerDetection } from './corner/cornerPhaseDetector.js';

export {
  analyzeDeltaToTarget,
  buildLearningPlan,
  canDeliverAudioCue,
  createLearningPlanEnvelope,
  detectBrakeDegradation,
  evaluateBiometricLoad,
  findNearestReferenceSample,
  referenceTraceForTrack,
  sanitizeOnTrackCue,
  shouldSuppressCueForPhase,
  validateLearningPlan,
  validateLearningPlanEnvelope,
} from './del/index.js';
export type {
  AudioDebounceState,
  BiometricLoadResult,
  BiometricLoadState,
  BiometricSample,
  BrakeDegradationResult,
  BrakeHealthSample,
  CoachAction,
  DeltaAnalysis,
  LearningPlan,
  LearningPlanEnvelope,
  LearningPlanFocus,
  LearningPlanTarget,
  LearningPlanValidationResult,
} from './del/index.js';

export { SONOMA_GOLD_TRACE, THUNDERHILL_GOLD_TRACE } from './traces/sonomaGoldTrace.js';
export type { ReferenceTraceSample } from './traces/sonomaGoldTrace.js';

export {
  DEFAULT_TRACK,
  SONOMA_RACEWAY,
  THUNDERHILL_EAST,
  TRACKS,
  getTrackByName,
} from './tracks/index.js';
