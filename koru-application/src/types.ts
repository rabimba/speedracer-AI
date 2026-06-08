import type {
  CornerPhase,
  SessionMode,
  TelemetryFrame,
} from '@trustable/core-telemetry';

// ── Telemetry (shared via @trustable/core-telemetry) ───────

export type {
  SessionMode,
  TelemetrySourceKind,
  ObdTransportPreference,
  TelemetryFrame,
  VehicleDiagnostics,
  CanVehicleDiagnostics,
  TelemetrySourceHealth,
  VisionFeatureSnapshot,
  GpsSSEPoint,
} from '@trustable/core-telemetry';

// ── Track (shared via @trustable/core-telemetry) ───────────

export type { Corner, Sector, Track } from '@trustable/core-telemetry';

// ── Lap & Session ──────────────────────────────────────────

export interface Lap {
  id: string;
  lapNumber: number;
  time: number;          // total seconds
  valid: boolean;
  frames: TelemetryFrame[];
  sectors: number[];     // sector times
  isComplete: boolean;
}

export interface Session {
  id: string;
  trackName: string;
  date: string;
  laps: Lap[];
  bestLapId: string;
  weather: 'Sunny' | 'Cloudy' | 'Rain';
  trackTemp: number;
}

export interface RecordedSessionSummary {
  sessionId: string;
  mode: SessionMode;
  trackName: string;
  coachId: string;
  frameCount: number;
  decisionCount: number;
  durationSeconds: number;
}

export interface RecordedSessionArtifact {
  schemaVersion?: number;
  id: string;
  mode: SessionMode;
  trackName: string;
  coachId: string;
  startedAt: number;
  endedAt: number;
  summary: RecordedSessionSummary;
  sessionGoals: SessionGoal[];
  frames: TelemetryFrame[];
  decisions: CoachingDecision[];
  audioEvents?: AudioDispatchEvent[];
  artifactPath?: string;
  canDumpPath?: string;
}

// ── Coaching ───────────────────────────────────────────────

export interface CoachPersona {
  id: string;
  name: string;
  style: string;
  systemPrompt: string;
  icon: string;
}

export type CoachAction =
  | 'THRESHOLD' | 'TRAIL_BRAKE' | 'BRAKE' | 'WAIT'
  | 'TURN_IN' | 'COMMIT' | 'ROTATE' | 'APEX'
  | 'THROTTLE' | 'PUSH' | 'FULL_THROTTLE'
  | 'STABILIZE' | 'MAINTAIN' | 'COAST'
  | 'HESITATION' | 'OVERSTEER_RECOVERY'
  | 'EARLY_THROTTLE' | 'LIFT_MID_CORNER' | 'SPIKE_BRAKE' | 'COGNITIVE_OVERLOAD'
  | 'HUSTLE';

// ── Session Goals (Phase 6.2) ────────────────────────────

export type SessionGoalFocus = 'braking' | 'throttle' | 'vision' | 'lines' | 'smoothness' | 'custom';

export interface SessionGoal {
  id: string;
  focus: SessionGoalFocus;
  description: string;            // e.g. "Work on harder initial brake application in Turn 7"
  source: 'pre_race_chat' | 'auto_generated' | 'coach_assigned';
  prioritizedActions?: CoachAction[];  // Hot path rules to boost when this goal is active
}

// ── Cross-Session Driver Profile (Phase 6.3) ─────────────
// Persistence layer owned by AGY Pipeline.
// Data Reasoning defines the interface and implements read/write logic.

export interface CornerPerformance {
  cornerId: number;
  cornerName: string;
  minSpeed: number;
  brakePoint: number;         // distance from corner entry where braking started
  throttleApplication: number; // avg throttle % on exit
  issueCount: number;          // how many coaching messages fired here
}

export interface SessionSummary {
  sessionId: string;
  date: string;
  trackName: string;
  totalLaps: number;
  bestLapTime: number;
  avgLapTime: number;
  skillLevel: SkillLevel;
  cornerPerformance: CornerPerformance[];
  goalsAchieved: string[];     // SessionGoal IDs that were met
}

export interface DriverProfile {
  driverId: string;
  currentSkillLevel: SkillLevel;
  sessions: SessionSummary[];
  problemCorners: number[];     // Corner IDs that consistently cause issues
  strengths: CoachAction[];     // Actions driver rarely triggers (doing well)
  weaknesses: CoachAction[];    // Actions driver frequently triggers (needs work)
}

/**
 * Interface that AGY Pipeline must implement for cross-session persistence.
 * Data Reasoning calls these methods; AGY Pipeline provides the storage backend
 * (IndexedDB, localStorage, or cloud sync).
 */
export interface DriverProfileStore {
  load(driverId: string): Promise<DriverProfile | null>;
  save(profile: DriverProfile): Promise<void>;
  addSession(driverId: string, summary: SessionSummary): Promise<void>;
}

// ── Corner Phase & Timing ─────────────────────────────────

export type { CornerPhase } from '@trustable/core-telemetry';

export type TimingState = 'OPEN' | 'DELIVERING' | 'COOLDOWN' | 'BLACKOUT';
export type CoachingPath = 'hot' | 'cold' | 'feedforward' | 'edge';
export type RuntimeBackend = 'browser' | 'aicore' | 'litertlm' | 'deterministic';
export type RuntimeAccelerator = 'none' | 'mediapipe_litert' | 'aicore' | 'unknown';
export type LiveBackendState = 'idle' | 'starting' | 'ready' | 'degraded' | 'unavailable' | 'error';

export interface CoachingDecision {
  id?: string;
  path: CoachingPath;
  action?: CoachAction;
  text: string;
  priority: 0 | 1 | 2 | 3;
  cornerPhase: CornerPhase;
  timestamp: number;
  backend?: RuntimeBackend;
  latencyMs?: number;
  confidence?: number;
  phraseId?: string;
}

export type AudioDispatchStatus =
  | 'CLIP_STARTED'
  | 'TTS_QUEUED'
  | 'TTS_STARTED'
  | 'TTS_UNAVAILABLE'
  | 'DISABLED';

export interface AudioDispatchEvent {
  decisionId: string;
  utteranceId: string;
  action?: CoachAction;
  priority: number;
  requestedAt: number;
  dispatchLatencyMs: number;
  ttsStartLatencyMs?: number;
  status: AudioDispatchStatus;
  fallbackReason?: string;
}

// ── Driver Model ──────────────────────────────────────────

export type SkillLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export interface DriverState {
  skillLevel: SkillLevel;
  cognitiveLoad: number;       // 0-1
  inputSmoothness: number;     // 0-1 (1 = perfectly smooth)
  coastingRatio: number;       // 0-1 (fraction of recent frames coasting)
}

export type SSEConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

export type TTSProvider = 'browser' | 'gemini';

export type CloudModel = 'flash' | 'pro';

export interface LiveBackendStatus {
  backend: RuntimeBackend;
  state: LiveBackendState;
  detail: string;
  lastUpdated: number;
  model?: string;
  usesOnDeviceModel: boolean;
  supportedPaths: CoachingPath[];
  accelerator?: RuntimeAccelerator;
}
