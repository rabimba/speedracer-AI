import type { TelemetryFrame, CoachAction, Corner, Track, CoachingDecision, CornerPhase, SessionGoal, SkillLevel, CoachingObjective } from '../types';
import { COACHES, DEFAULT_COACH, DECISION_MATRIX, RACING_PHYSICS_KNOWLEDGE } from '../utils/coachingKnowledge';
import {
  getTrackFeedforwardMessage,
  getTrackHotActionMessage,
  getTrackPromptContext,
  shouldSuppressTrackAction,
} from '../data/trackExpertise';
import { haversineDistance, isValidGps } from '../utils/geoUtils';
import {
  analyzeDeltaToTarget,
  referenceTraceForTrack,
  type DeltaAnalysis,
  type ReferenceTraceSample,
} from '@trustable/core-telemetry';
import { CornerPhaseDetector } from './cornerPhaseDetector';
import { TimingGate } from './timingGate';
import { CoachingQueue } from './coachingQueue';
import { DriverModel } from './driverModel';
import { PerformanceTracker } from './performanceTracker';

/** Map actions to priority levels (module-level Map avoids per-call array allocations) */
const ACTION_PRIORITY: Map<string, 0 | 1 | 2 | 3> = new Map([
  ['OVERSTEER_RECOVERY', 0], ['BRAKE', 0],
  ['EARLY_THROTTLE', 1], ['LIFT_MID_CORNER', 1], ['SPIKE_BRAKE', 1],
  ['COGNITIVE_OVERLOAD', 2],
  ['PUSH', 3], ['FULL_THROTTLE', 3], ['MAINTAIN', 3], ['COAST', 3], ['HESITATION', 3],
  ['HUSTLE', 3],
]);

function actionPriority(action: CoachAction): 0 | 1 | 2 | 3 {
  return ACTION_PRIORITY.get(action) ?? 1;
}

type CoachingCallback = (msg: CoachingDecision) => void;

export function coldPathInstructionForSkill(skillLevel: SkillLevel): string {
  const rootCauseFrame =
    'Prioritize WHY over WHAT: identify the root cause using brake release, weight transfer, line choice, throttle timing, or vision target. Do not merely describe the symptom.';
  switch (skillLevel) {
    case 'BEGINNER':
      return `${rootCauseFrame} Give one calm, feel-based sentence under 18 words. Avoid jargon.`;
    case 'ADVANCED':
      return `${rootCauseFrame} Reference the telemetry numbers and give one concise cause-first sentence under 22 words.`;
    default:
      return `${rootCauseFrame} Give one technique sentence with a brief physics cause under 20 words.`;
  }
}

/**
 * Split-brain coaching engine:
 * - HOT: heuristic rules with humanized text (<50ms)
 * - COLD: Gemini Cloud with cooldown (2-5s)
 * - FEEDFORWARD: geofence-based corner advice
 *
 * Now integrated with:
 * - CornerPhaseDetector: knows if driver is mid-corner
 * - TimingGate: enforces blackout during mid-corner/apex, safety bypass
 */
export class CoachingService {
  private coachId: string = DEFAULT_COACH;
  private listeners: CoachingCallback[] = [];
  private lastColdTime = 0;
  private lastHotAction: CoachAction | null = null;
  private lastCorner: Corner | null = null;
  private coldCooldownMs = 15000;
  private apiKey: string | null = null;
  private coldRetryCount = 0;
  private static readonly COLD_MAX_RETRIES = 2;
  private static readonly COLD_CONFIDENCE_THRESHOLD = 0.6;

  // New modules
  private cornerDetector = new CornerPhaseDetector();
  private timingGate = new TimingGate();
  private coachingQueue = new CoachingQueue();
  private driverModel = new DriverModel();
  private performanceTracker = new PerformanceTracker();
  private currentPhase: CornerPhase = 'STRAIGHT';
  private track: Track | null = null;
  private lastSkillLevel: import('../types').SkillLevel = 'BEGINNER';
  private lastCognitiveCheck = 0;
  private lastHustleCheck = 0;

  // DEL — reference-trace delta coaching (Pillar 1)
  private referenceTrace: ReferenceTraceSample[] = [];
  private lastDeltaCornerId: number | null = null;
  private lastDeltaTime = 0;
  private static readonly DELTA_COOLDOWN_S = 6;
  private apexFrame: TelemetryFrame | null = null;
  private lastDeltaAnalysis: DeltaAnalysis | null = null;

  // Session goals (Phase 6.2 — populated by pre-race chat or auto-generated)
  private sessionGoals: SessionGoal[] = [];

  // Session progression
  private sessionPhase: 1 | 2 | 3 = 1;
  private static readonly PHASE_SUPPRESSED: Record<number, Set<CoachAction>> = {
    1: new Set(['TRAIL_BRAKE', 'COMMIT', 'ROTATE', 'EARLY_THROTTLE', 'COGNITIVE_OVERLOAD']),
    2: new Set(['COGNITIVE_OVERLOAD']),
    3: new Set([]),
  };

  setCoach(id: string) { this.coachId = id; }
  getCoach() { return COACHES[this.coachId] || COACHES[DEFAULT_COACH]; }
  setApiKey(key: string) { this.apiKey = key; }

  setTrack(track: Track): void {
    this.track = track;
    this.cornerDetector.setTrack(track);
    this.referenceTrace = referenceTraceForTrack(track.name);
    this.lastDeltaCornerId = null;
    this.lastDeltaTime = 0;
  }

  getTimingState() { return this.timingGate.getState(); }
  getCornerPhase() { return this.currentPhase; }
  getDriverState() { return this.driverModel.getState(); }
  getSessionGoals() { return this.sessionGoals; }
  getPerformanceTracker() { return this.performanceTracker; }

  /** Call when a new lap starts (e.g. from lap detection logic) */
  newLap(): void { this.performanceTracker.newLap(); }

  /**
   * Set session goals (Phase 6.2).
   * Called before session starts — either from pre-race chat UI (Rabimba/UX)
   * or auto-generated from driver profile + track knowledge.
   *
   * Goals bias the hot path: prioritizedActions in goals get boosted.
   * Max 3 goals per session to keep the driver from overloading.
   *
   * TODO: UX team (Rabimba) builds pre-race chat that calls this method.
   * TODO: Auto-generation from DriverProfile when persistence layer is ready (AGY Pipeline).
   */
  setSessionGoals(goals: SessionGoal[]): void {
    this.sessionGoals = goals.slice(0, 3);
  }

  private goalBoostForAction(action: CoachAction): number {
    return this.sessionGoals.reduce((boost, goal) => {
      return boost + (goal.prioritizedActions?.includes(action) ? 1 : 0);
    }, 0);
  }

  private currentGoalPromptContext(): string {
    if (this.sessionGoals.length === 0) return 'Session goals: none set.';
    const lines = this.sessionGoals.map((goal, index) =>
      `${index + 1}. ${goal.focus}: ${goal.description}`
    );
    return `Session goals:\n${lines.join('\n')}`;
  }

  onCoaching(cb: CoachingCallback) {
    this.listeners.push(cb);
    return () => { this.listeners = this.listeners.filter(l => l !== cb); };
  }

  private emit(msg: CoachingDecision) {
    // TODO: Remove when CoachPanel displays priority/action/phase metadata
    const pri = ['🔴P0', '🟠P1', '🔵P2', '⚪P3'][msg.priority] ?? `P${msg.priority}`;
    console.log(`[COACH] ${pri} ${msg.action ?? msg.path} | ${msg.cornerPhase} | ${msg.text}`);
    this.timingGate.startDelivery();
    this.listeners.forEach(cb => cb(msg));
  }

  /** Called on every telemetry frame */
  processFrame(frame: TelemetryFrame) {
    // Detect corner phase
    const detection = this.cornerDetector.detect(frame);
    this.currentPhase = detection.phase;
    if (this.track && detection.cornerId !== null) {
      const detectedCorner = this.track.corners.find((corner) => corner.id === detection.cornerId) ?? null;
      if (detectedCorner) this.lastCorner = detectedCorner;
    }

    // Track per-corner performance (Phase 6.4)
    const improvement = this.performanceTracker.update(
      frame, this.currentPhase,
      detection.cornerId ?? null, detection.cornerName ?? null,
    );
    if (improvement) this.coachingQueue.enqueue(improvement);

    // Update timing gate with current phase
    this.timingGate.update(this.currentPhase);

    // Update driver model and adapt coaching parameters
    this.driverModel.update(frame);
    this.adaptToSkillLevel();

    // Session progression
    this.updateSessionPhase(frame.time);

    // Run coaching paths (enqueue decisions)
    this.runHotPath(frame);
    this.runDeltaPath(frame);
    this.checkCognitiveOverload(frame);
    this.checkHustle(frame);
    this.runFeedforward(frame);
    void this.runColdPath(frame);

    // Drain queue — deliver highest-priority message if timing allows
    this.drainQueue();
  }

  private drainQueue(): void {
    const decision = this.coachingQueue.dequeue(this.timingGate);
    if (decision) {
      this.emit(decision);
    }
  }

  /** Adapt coaching parameters when skill level changes */
  private adaptToSkillLevel(): void {
    const level = this.driverModel.getSkillLevel();
    if (level === this.lastSkillLevel) return;
    // TODO: Remove when CoachPanel displays driver state metadata
    console.log(`[DRIVER] Skill level changed: ${this.lastSkillLevel} → ${level}`);
    this.lastSkillLevel = level;

    switch (level) {
      case 'BEGINNER':
        this.timingGate.updateConfig({
          cooldownMs: 3000,
          deliveryMs: 2000,
          blackoutPhases: ['MID_CORNER', 'APEX'],
        });
        this.coldCooldownMs = 20000;
        break;
      case 'INTERMEDIATE':
        this.timingGate.updateConfig({
          cooldownMs: 2200,
          deliveryMs: 1800,
          blackoutPhases: ['APEX'],
        });
        this.coldCooldownMs = 15000;
        break;
      case 'ADVANCED':
        this.timingGate.updateConfig({
          cooldownMs: 2800,
          deliveryMs: 1200,
          blackoutPhases: [],
        });
        this.coldCooldownMs = 10000;
        break;
    }
  }

  /** Update session phase based on frame time and skill level */
  private updateSessionPhase(frameTime: number): void {
    const skill = this.driverModel.getSkillLevel();
    if (skill === 'ADVANCED') { this.sessionPhase = 3; return; }
    if (frameTime > 180) { this.sessionPhase = 3; }
    else if (frameTime > 60) { this.sessionPhase = 2; }
    else { this.sessionPhase = 1; }
  }

  // ── DELTA PATH: reference-trace comparison (Pillar 1) ──────

  /**
   * Compare the stored apex frame against a gold reference trace and emit a
   * grounded, evidence-based cue at P2 when the driver exits a corner.
   *
   * The cue fires at corner EXIT (not during the apex) so it doesn't fight the
   * timing-gate blackout and doesn't distract the driver mid-corner. The
   * analysis runs on the apex frame captured while the driver was in the
   * corner, so it references the corner the driver just drove.
   *
   * Unlike the HOT path (threshold rules → generic phrase), the delta path
   * explains the gap to the reference: "carrying 8 mph less through the
   * apex", "braked 14m early", etc.
   */
  private runDeltaPath(frame: TelemetryFrame): void {
    if (this.referenceTrace.length === 0 || !this.track) return;

    // Capture the apex frame for later analysis at corner exit.
    if (this.currentPhase === 'APEX' || this.currentPhase === 'MID_CORNER') {
      this.apexFrame = frame;
      return;
    }

    // Fire at corner exit — when we have a stored apex frame and the driver
    // has transitioned to a non-blackout phase (STRAIGHT / EXIT / ACCELERATION).
    if (!this.apexFrame) return;
    if (this.currentPhase !== 'STRAIGHT' && this.currentPhase !== 'EXIT' && this.currentPhase !== 'ACCELERATION') {
      return;
    }

    // Cooldown: don't fire more than once per DELTA_COOLDOWN_S seconds.
    if (frame.time - this.lastDeltaTime < CoachingService.DELTA_COOLDOWN_S) return;

    let analysis: DeltaAnalysis;
    try {
      analysis = analyzeDeltaToTarget(this.apexFrame, this.referenceTrace, this.track);
    } catch {
      this.apexFrame = null;
      return;
    }

    // Don't re-cue the same corner within the cooldown.
    const refCornerId = analysis.corner?.id ?? null;
    if (refCornerId !== null && refCornerId === this.lastDeltaCornerId &&
        frame.time - this.lastDeltaTime < CoachingService.DELTA_COOLDOWN_S) {
      this.apexFrame = null;
      return;
    }

    // Skip non-actionable "MAINTAIN" cues — the hot path covers smoothness.
    if (analysis.recommendedAction === 'MAINTAIN') {
      this.apexFrame = null;
      this.lastDeltaAnalysis = null;
      return;
    }

    this.lastDeltaCornerId = refCornerId;
    this.lastDeltaTime = frame.time;
    this.apexFrame = null;
    this.lastDeltaAnalysis = analysis;

    const objective = this.deltaObjectiveFor(analysis);
    const causeId = this.deltaCauseIdFor(analysis);

    this.coachingQueue.enqueue({
      path: 'hot',
      action: analysis.recommendedAction,
      text: analysis.cue,
      priority: 2,
      cornerPhase: this.currentPhase,
      timestamp: Date.now(),
      cornerId: refCornerId ?? undefined,
      cornerName: analysis.corner?.name,
      objective,
      causeId,
    });
  }

  private deltaObjectiveFor(analysis: DeltaAnalysis): CoachingObjective {
    switch (analysis.recommendedAction) {
      case 'BRAKE':
      case 'SPIKE_BRAKE':
        return 'brake_entry';
      case 'EARLY_THROTTLE':
      case 'LIFT_MID_CORNER':
        return 'exit_throttle';
      case 'PUSH':
        return 'line_vision';
      case 'HUSTLE':
        return 'exit_throttle';
      default:
        return 'smoothness';
    }
  }

  private deltaCauseIdFor(analysis: DeltaAnalysis): string {
    const corner = analysis.corner?.name ? `${analysis.corner.name}_` : '';
    const sign = (n: number) => (n > 0 ? 'plus' : n < 0 ? 'minus' : 'flat');
    return `delta_${corner}${analysis.recommendedAction}_${sign(analysis.apexSpeedDelta)}speed_${sign(analysis.brakeDelta)}brake`;
  }

  // ── HOT PATH: instant heuristic commands ───────────────

  private runHotPath(frame: TelemetryFrame) {
    const state = this.driverModel.getState();
    const skillLevel = this.driverModel.getSkillLevel();
    const data = {
      brake: frame.brake,
      throttle: frame.throttle,
      gLat: frame.gLat,
      gLong: frame.gLong,
      speed: frame.speed,
    };
    const candidates = DECISION_MATRIX
      .map((rule, index) => ({ rule, index }))
      .filter(({ rule }) => rule.check(data))
      .filter(({ rule }) => rule.action !== 'STABILIZE' && rule.action !== 'MAINTAIN')
      .filter(({ rule }) => {
        const suppressed = CoachingService.PHASE_SUPPRESSED[this.sessionPhase];
        return !suppressed?.has(rule.action);
      })
      .filter(({ rule }) => !shouldSuppressTrackAction(
        this.track,
        this.lastCorner,
        rule.action,
        skillLevel,
        this.sessionPhase,
        this.currentPhase,
        state.cognitiveLoad,
      ))
      .filter(({ rule }) => rule.action !== this.lastHotAction)
      .sort((a, b) => {
        const priorityDelta = actionPriority(a.rule.action) - actionPriority(b.rule.action);
        if (priorityDelta !== 0) return priorityDelta;
        const goalDelta = this.goalBoostForAction(b.rule.action) - this.goalBoostForAction(a.rule.action);
        if (goalDelta !== 0) return goalDelta;
        return a.index - b.index;
      });

    const selected = candidates[0]?.rule;
    if (!selected) return;

    const priority = actionPriority(selected.action);
    this.lastHotAction = selected.action;

    const decision: CoachingDecision = {
      path: 'hot',
      action: selected.action,
      text: this.humanizeAction(selected.action, frame),
      priority,
      cornerPhase: this.currentPhase,
      timestamp: Date.now(),
    };

    if (priority === 0) {
      this.emit(this.coachingQueue.preempt(decision));
    } else {
      this.coachingQueue.enqueue(decision);
    }
  }

  /** Check driver model for cognitive overload — runs outside decision matrix */
  private checkCognitiveOverload(frame: TelemetryFrame): void {
    // Only check every 10 seconds
    if (frame.time - this.lastCognitiveCheck < 10) return;
    this.lastCognitiveCheck = frame.time;

    const state = this.driverModel.getState();
    if (state.inputSmoothness < 0.3 && state.skillLevel !== 'ADVANCED') {
      this.coachingQueue.enqueue({
        path: 'hot',
        action: 'COGNITIVE_OVERLOAD',
        text: this.humanizeAction('COGNITIVE_OVERLOAD', frame),
        priority: 2,
        cornerPhase: this.currentPhase,
        timestamp: Date.now(),
      });
    }
  }

  /**
   * Detect lazy throttle application on exits.
   * Drivers get lazy mid-session — brain says "why go to 100% for 2 seconds?"
   * But that last 10-15% throttle matters for exit speed onto straights.
   * Fires every 8 seconds when on straight/acceleration with throttle 50-92%.
   * Beginner-focused: only fires for BEGINNER skill level.
   */
  private checkHustle(frame: TelemetryFrame): void {
    if (frame.time - this.lastHustleCheck < 8) return;
    if (this.driverModel.getSkillLevel() !== 'BEGINNER') return;

    const onExit = this.currentPhase === 'ACCELERATION' || this.currentPhase === 'STRAIGHT';
    const lazyThrottle = frame.throttle > 50 && frame.throttle < 92;
    const movingFast = frame.speed > 40;
    const lowLateralG = Math.abs(frame.gLat) < 0.3;
    const driverState = this.driverModel.getState();

    if (
      onExit &&
      lazyThrottle &&
      movingFast &&
      lowLateralG &&
      !shouldSuppressTrackAction(
        this.track,
        this.lastCorner,
        'HUSTLE',
        driverState.skillLevel,
        this.sessionPhase,
        this.currentPhase,
        driverState.cognitiveLoad,
      )
    ) {
      this.lastHustleCheck = frame.time;
      this.coachingQueue.enqueue({
        path: 'hot',
        action: 'HUSTLE',
        text: this.humanizeAction('HUSTLE', frame),
        priority: 3,
        cornerPhase: this.currentPhase,
        timestamp: Date.now(),
      });
    }
  }

  /** Convert action enum to coaching phrase — context-aware and persona-specific */
  private humanizeAction(action: CoachAction, frame: TelemetryFrame): string {
    const skillLevel = this.driverModel.getSkillLevel();
    const doctrineText = getTrackHotActionMessage(
      this.track,
      this.lastCorner,
      action,
      skillLevel,
      this.sessionPhase,
      this.currentPhase,
      this.driverModel.getState().cognitiveLoad,
    );
    if (doctrineText) return doctrineText;

    // Skill-adapted phrases for key actions (override persona for clarity)
    // Beginner phrases: feel-based trigger phrases
    // Coaching pedagogy: short, actionable, feel-based for beginners
    // "Do this, do this now" — direct commands, no jargon (00:28:56)
    if (skillLevel === 'BEGINNER') {
      switch (action) {
        case 'TRAIL_BRAKE': return 'Hold a little brake as you turn in.';
        case 'BRAKE': return frame.speed > 80 ? 'Brake! Hard initial!' : 'Start braking — squeeze it.';
        case 'THRESHOLD': return 'Harder initial! Squeeze the brakes faster.';
        case 'COMMIT': return 'Commit! Full throttle now — the car can take it.';
        case 'THROTTLE': return 'Gently add gas now.';
        case 'COAST': return 'Pick a pedal — gas or brake. Stay committed!';
        case 'OVERSTEER_RECOVERY': return 'Easy! Straighten the wheel gently!';
        case 'EARLY_THROTTLE': return 'Wait for it... wait... NOW! Full throttle.';
        case 'LIFT_MID_CORNER': return 'Keep a little gas on through the turn — don\'t lift!';
        case 'SPIKE_BRAKE': return 'Smoother on the brakes — squeeze, don\'t stab.';
        case 'COGNITIVE_OVERLOAD': return 'Feeling busy? Just focus on your marks this lap.';
        case 'HESITATION': return 'Trust the car — commit!';
        case 'HUSTLE': return 'Hustle! Squirt the throttle — full send!';
        case 'PUSH': return 'Eyes up! Look further ahead.';
        case 'FULL_THROTTLE': return 'Full throttle — stay flat!';
      }
    }

    if (skillLevel === 'ADVANCED') {
      const advGLat = Math.abs(frame.gLat);
      switch (action) {
        case 'TRAIL_BRAKE': return `Trail off. G-Lat: ${advGLat.toFixed(2)}. Release linearly to apex.`;
        case 'BRAKE': return `Brake. ${frame.speed.toFixed(0)} mph, target ${Math.abs(frame.gLong).toFixed(1)}G decel.`;
        case 'COMMIT': return `Committed. G-Lat: ${advGLat.toFixed(2)}. Hold.`;
        case 'THROTTLE': return `Throttle. ${frame.throttle.toFixed(0)}%. ${advGLat > 0.8 ? 'Progressive.' : 'Extend.'}`;
        case 'COAST': return `Coasting — zero G-vector at ${frame.speed.toFixed(0)} mph. Losing time.`;
        case 'OVERSTEER_RECOVERY': return `Countersteer. G-Lat ${advGLat.toFixed(2)}. Smooth inputs.`;
        case 'EARLY_THROTTLE': return `Early throttle — still ${advGLat.toFixed(2)}G lateral. Delay.`;
        case 'LIFT_MID_CORNER': return `Lift detected mid-corner. Maintenance throttle.`;
        case 'SPIKE_BRAKE': return `Brake spike — ${frame.brake.toFixed(0)}% at ${Math.abs(frame.gLong).toFixed(1)}G. Squeeze, don't stab.`;
        case 'COGNITIVE_OVERLOAD': return 'Reset. Smooth lap, no heroics.';
        case 'HUSTLE': return `Throttle ${frame.throttle.toFixed(0)}% on exit. Commit 100%.`;
      }
    }

    // INTERMEDIATE falls through to existing persona-based logic
    const coach = this.getCoach();
    const speed = frame.speed;
    const gLat = Math.abs(frame.gLat);
    const gLong = frame.gLong;
    const brake = frame.brake;
    const throttle = frame.throttle;

    const fast = speed > 80;
    const med  = speed > 45 && speed <= 80;
    const highBrake  = brake > 70;
    const lightBrake = brake > 0 && brake <= 40;
    const highCornerLoad = gLat > 1.2;
    const highThrottle   = throttle > 70;

    // ── AJ: terse telemetry commands ────────────────────────
    if (coach.id === 'aj') {
      switch (action) {
        case 'OVERSTEER_RECOVERY': return 'Countersteer. Smooth.';
        case 'THRESHOLD':    return highBrake ? 'Max brake. Hold.' : 'More brake. Now.';
        case 'TRAIL_BRAKE':  return highCornerLoad ? 'Trail. Ease.' : 'Trail off. Release.';
        case 'BRAKE':        return fast ? 'Brake hard.' : 'Brake.';
        case 'WAIT':         return 'Hold. Wait.';
        case 'TURN_IN':      return fast ? 'Late turn. Now.' : 'Turn.';
        case 'COMMIT':       return 'Commit. Go.';
        case 'ROTATE':       return highCornerLoad ? 'Rotate. Less wheel.' : 'Rotate.';
        case 'APEX':         return 'Apex. Hit it.';
        case 'THROTTLE':     return 'Throttle. Now.';
        case 'PUSH':         return fast ? 'Flat. Stay flat.' : 'Push. More speed.';
        case 'FULL_THROTTLE':return 'Flat.';
        case 'STABILIZE':    return 'Stabilize.';
        case 'MAINTAIN':     return 'Maintain.';
        case 'COAST':        return 'Pick a pedal.';
        case 'HESITATION': return 'Send it.';
        case 'EARLY_THROTTLE': return 'Too early. Wait.';
        case 'LIFT_MID_CORNER': return 'Don\'t lift. Maintenance throttle.';
        case 'SPIKE_BRAKE': return 'Squeeze. Not stab.';
        case 'COGNITIVE_OVERLOAD': return 'Reset. Smooth lap.';
        case 'HUSTLE': return 'Hustle. Full throttle.';
      }
    }

    // ── Rachel: physics-grounded ─────────────────────────────
    if (coach.id === 'rachel') {
      switch (action) {
        case 'OVERSTEER_RECOVERY': return 'Countersteer gently — the rear has lost grip. Ease off inputs.';
        case 'THRESHOLD':    return highBrake
          ? 'Maximum decel — you\'re saturating the friction circle.'
          : 'More brake pedal — you have front traction available.';
        case 'TRAIL_BRAKE':  return highCornerLoad
          ? 'Trail off the brake — front is loaded, ease the G-vector.'
          : 'Trail brake into the corner — transfer weight to the front axle.';
        case 'BRAKE':        return fast
          ? 'Brake now — shift weight forward, load the fronts.'
          : 'Light brake — set platform balance for the corner.';
        case 'WAIT':         return 'Patience — wait for weight to settle before turning.';
        case 'TURN_IN':      return lightBrake
          ? 'Turn in — you\'re still on brakes, use the understeer to your advantage.'
          : 'Turn in — front is free, commit to the line.';
        case 'COMMIT':       return highCornerLoad
          ? 'Committed — you\'re near the friction limit, maintain the line.'
          : 'Commit to the corner — trust available grip.';
        case 'ROTATE':       return 'Ease the wheel — let yaw momentum rotate the car.';
        case 'APEX':         return 'Clip the apex — tighten the radius, minimum speed point.';
        case 'THROTTLE':     return highCornerLoad
          ? 'Progressive throttle — don\'t overwhelm the rear on exit.'
          : 'Build throttle — shift weight rearward, drive off the corner.';
        case 'PUSH':         return fast
          ? 'You\'re at speed — max longitudinal, full friction circle forward.'
          : 'Straight — extend throttle application, chase the exit.';
        case 'FULL_THROTTLE':return 'Full throttle — max longitudinal G, rear is planted.';
        case 'STABILIZE':    return 'Neutral inputs — let the platform settle.';
        case 'MAINTAIN':     return 'Platform balanced — maintain this G-vector.';
        case 'COAST':        return `Coasting at ${speed.toFixed(0)} mph — no G-vector. Pick a pedal to load the tires.`;
        case 'HESITATION': return 'The friction circle has margin — commit, the data says so.';
        case 'EARLY_THROTTLE': return 'Throttle before exit — you\'re overloading the rear.';
        case 'LIFT_MID_CORNER': return 'Lift mid-corner shifts weight forward — maintain throttle.';
        case 'SPIKE_BRAKE': return 'Brake input too aggressive — the trace should be a ski slope, not a cliff.';
        case 'COGNITIVE_OVERLOAD': return 'Cognitive saturation. Focus on one thing — smoothness.';
        case 'HUSTLE': return `Exit throttle ${frame.throttle.toFixed(0)}% — commit to 100%. Tire load demands it.`;
      }
    }

    // ── Tony: motivational, feel-based ──────────────────────
    if (coach.id === 'tony') {
      switch (action) {
        case 'OVERSTEER_RECOVERY': return 'Easy! Catch it — smooth hands!';
        case 'THRESHOLD':    return highBrake
          ? 'Yes! Hammer those brakes — own the stop!'
          : 'More brake — you\'ve got more stopping left!';
        case 'TRAIL_BRAKE':  return 'Breathe off the brake — feel the car rotate!';
        case 'BRAKE':        return fast
          ? 'Brake! Brake! Brake! Trust the tires!'
          : 'Brake now — set it up clean!';
        case 'WAIT':         return 'Hold it — patience is speed here!';
        case 'TURN_IN':      return 'Turn in — commit, you\'ve got grip!';
        case 'COMMIT':       return highCornerLoad
          ? 'You\'re committed — hold it, trust the car!'
          : 'Commit! Don\'t second-guess yourself!';
        case 'ROTATE':       return 'Let it breathe — feel the rear come around!';
        case 'APEX':         return 'Clip that apex — laser focus!';
        case 'THROTTLE':     return highCornerLoad
          ? 'Careful on the throttle — feed it in!'
          : 'Gas! Get on it — drive off that corner!';
        case 'PUSH':         return fast
          ? `${speed.toFixed(0)} mph and climbing — stay flat, push it!`
          : 'Clear road ahead — push harder, more speed!';
        case 'FULL_THROTTLE':return 'Full send — floor it, don\'t lift!';
        case 'STABILIZE':    return 'Easy — breathe, hold it steady!';
        case 'MAINTAIN':     return 'That\'s it! Keep that pace — you\'re flying!';
        case 'COAST':        return 'Don\'t coast — commit to a pedal, stay sharp!';
        case 'HESITATION': return 'Stop lifting! Trust it — send it!';
        case 'EARLY_THROTTLE': return 'Easy on the gas — wait for the exit!';
        case 'LIFT_MID_CORNER': return 'Don\'t lift! Keep a little gas on!';
        case 'SPIKE_BRAKE': return 'Squeeze those brakes — smooth is fast!';
        case 'COGNITIVE_OVERLOAD': return 'Take a breath — one thing at a time!';
        case 'HUSTLE': return 'Hustle! Squirt the throttle — full send!';
      }
    }

    // ── Garmin: data-focused, clinical numbers ───────────────
    if (coach.id === 'garmin') {
      switch (action) {
        case 'OVERSTEER_RECOVERY': return `Oversteer detected. G-Lat: ${gLat.toFixed(2)}. Countersteer.`;
        case 'THRESHOLD':    return highBrake
          ? `${brake.toFixed(0)}% brake — holding threshold. Maintain.`
          : `${brake.toFixed(0)}% brake — ${(100 - brake).toFixed(0)}% capacity unused. Apply more.`;
        case 'TRAIL_BRAKE':  return `Trail braking. G-Long: ${gLong.toFixed(2)}. Release linearly.`;
        case 'BRAKE':        return fast
          ? `Brake point. ${speed.toFixed(0)} mph — target -${Math.abs(gLong).toFixed(1)}G decel.`
          : `Brake. Entry speed ${speed.toFixed(0)} mph.`;
        case 'WAIT':         return 'Patience zone. Hold position. Delta neutral.';
        case 'TURN_IN':      return `Turn-in. Speed: ${speed.toFixed(0)} mph. G-Lat target: 1.2+.`;
        case 'COMMIT':       return `Committed. G-Lat: ${frame.gLat.toFixed(2)}. Hold the line.`;
        case 'ROTATE':       return `Rotation phase. Yaw in progress. Reduce steering input.`;
        case 'APEX':         return `Apex. Minimum speed: ${speed.toFixed(0)} mph. Begin exit.`;
        case 'THROTTLE':     return highCornerLoad
          ? `Throttle — ${throttle.toFixed(0)}%. G-Lat ${gLat.toFixed(2)} — progressive only.`
          : `Throttle. ${throttle.toFixed(0)}% — room to extend.`;
        case 'PUSH':         return `Straight. ${speed.toFixed(0)} mph — +${(0.3 + (90 - Math.min(speed, 90)) * 0.01).toFixed(1)}s potential. Stay flat.`;
        case 'FULL_THROTTLE':return `Full throttle. G-Long: ${gLong.toFixed(2)}. Max longitudinal.`;
        case 'STABILIZE':    return 'Inputs neutral. G-forces stabilizing.';
        case 'MAINTAIN':     return `On delta. ${speed.toFixed(0)} mph. Maintain.`;
        case 'COAST':        return `Coasting — ${speed.toFixed(0)} mph. Zero G-vector. Losing time.`;
        case 'HESITATION': return `G-Lat headroom: ${(2.0 - gLat).toFixed(1)}G unused. Commit.`;
        case 'EARLY_THROTTLE': return `Early throttle. G-Lat: ${gLat.toFixed(2)}. Delay to exit.`;
        case 'LIFT_MID_CORNER': return `Lift detected. G-Lat: ${gLat.toFixed(2)}. Maintain 10-20% throttle.`;
        case 'SPIKE_BRAKE': return `Brake spike: ${brake.toFixed(0)}% at ${Math.abs(gLong).toFixed(1)}G. Modulate.`;
        case 'COGNITIVE_OVERLOAD': return 'Input variance high. Simplify.';
        case 'HUSTLE': return `Exit throttle: ${frame.throttle.toFixed(0)}%. Target: 100%. Commit.`;
      }
    }

    // ── Super AJ: adaptive, hobby-driver-friendly ────────────
    switch (action) {
      case 'OVERSTEER_RECOVERY': return 'Catch the slide! Countersteer gently and ease off!';
      case 'THRESHOLD':    return highBrake
        ? 'Good — keep that brake pressure!'
        : 'Squeeze harder on the brakes — you\'ve got more stopping power!';
      case 'TRAIL_BRAKE':  return fast
        ? 'Release the brake slowly as you turn — don\'t let go all at once!'
        : 'Ease off the brake as you turn in — balance the car.';
      case 'BRAKE':        return fast
        ? 'Brake now — you\'re carrying too much speed!'
        : 'Start braking — set up the corner entry.';
      case 'WAIT':         return 'Be patient — wait for the car to settle before turning!';
      case 'TURN_IN':      return lightBrake
        ? 'Turn in while trailing the brake — use that front grip!'
        : 'Turn in now — commit to the line!';
      case 'COMMIT':       return highCornerLoad
        ? 'Stay committed — you\'re on the limit, hold it!'
        : 'Trust the car — commit to the corner!';
      case 'ROTATE':       return 'Less steering, more patience — let the car rotate naturally.';
      case 'APEX':         return med
        ? 'Hit that apex tight — clip it!'
        : 'Apex — get close to the inside!';
      case 'THROTTLE':     return highCornerLoad
        ? 'Feed in the throttle gently — don\'t overwhelm the rear!'
        : 'Get on the gas — drive off the corner!';
      case 'PUSH':         return fast
        ? `${speed.toFixed(0)} mph — stay flat, don't lift!`
        : 'Nice straight — push it harder!';
      case 'FULL_THROTTLE':return 'Floor it — full throttle now!';
      case 'STABILIZE':    return 'Smooth inputs — hold it steady.';
      case 'MAINTAIN':     return 'Looking good — keep that pace!';
      case 'COAST':        return `Coasting at ${speed.toFixed(0)} mph — pick a pedal, stay committed!`;
      case 'HESITATION': return highThrottle
        ? 'You\'re on throttle — now commit fully, don\'t lift!'
        : 'Stop hesitating — trust the grip and send it!';
      case 'EARLY_THROTTLE': return 'Wait for the exit before getting on the gas!';
      case 'LIFT_MID_CORNER': return 'Don\'t lift mid-corner — keep a bit of throttle!';
      case 'SPIKE_BRAKE': return 'Easy on the brakes — squeeze, don\'t slam!';
      case 'COGNITIVE_OVERLOAD': return 'Slow down mentally — focus on smooth inputs.';
      case 'HUSTLE': return 'Hustle! Get on that throttle — full commit!';
    }

    return action;
  }

  // ── COLD PATH: Gemini Cloud detailed analysis (grounded, Pillar 2) ──

  /**
   * Grounded COLD path — fires at corner exit (not arbitrary frame) so the
   * LLM sees a complete corner. Includes DEL delta evidence in the prompt,
   * requests structured JSON output, routes by confidence, and retries on
   * transient failures.
   */
  private async runColdPath(frame: TelemetryFrame) {
    const now = Date.now();
    if (now - this.lastColdTime < this.coldCooldownMs) return;
    if (!this.apiKey) return;

    // Corner-aware trigger: only fire at corner exit, not mid-corner or on
    // random straights. This ensures the LLM reasons about a complete corner.
    const isCornerExit = this.currentPhase === 'STRAIGHT' ||
                        this.currentPhase === 'EXIT' ||
                        this.currentPhase === 'ACCELERATION';
    if (!isCornerExit || !this.lastCorner) return;

    this.lastColdTime = now;
    this.coldRetryCount = 0;

    const coach = this.getCoach();
    const skillLevel = this.driverModel.getSkillLevel();
    const instruction = coldPathInstructionForSkill(skillLevel);

    // RAG: pull track context + DEL delta evidence + driver corner history
    const trackContext = getTrackPromptContext(
      this.track,
      this.lastCorner,
      skillLevel,
      this.sessionPhase,
      this.lastDeltaAnalysis,
    );

    const deltaSummary = this.lastDeltaAnalysis
      ? `DELTA ANALYSIS: ${this.lastDeltaAnalysis.corner?.name ?? 'corner'} — ` +
        `apex speed ${this.lastDeltaAnalysis.apexSpeedDelta > 0 ? '+' : ''}${this.lastDeltaAnalysis.apexSpeedDelta.toFixed(0)} mph vs reference, ` +
        `brake ${this.lastDeltaAnalysis.brakeDelta > 0 ? '+' : ''}${this.lastDeltaAnalysis.brakeDelta.toFixed(0)}% vs reference. ` +
        `Root cause likely: ${this.inferColdRootCause(this.lastDeltaAnalysis)}.`
      : 'No delta analysis available for this corner.';

    const prompt = `${coach.systemPrompt}

${RACING_PHYSICS_KNOWLEDGE}

${trackContext}

${this.currentGoalPromptContext()}

Current Telemetry:
Speed: ${frame.speed.toFixed(1)} mph | Brake: ${frame.brake.toFixed(0)}% | Throttle: ${frame.throttle.toFixed(0)}%
G-Lat: ${frame.gLat.toFixed(2)} | G-Long: ${frame.gLong.toFixed(2)}
Location: ${this.lastCorner.name} - ${this.lastCorner.advice}

${deltaSummary}

${instruction}

Respond as JSON: {"cause":"root cause in one phrase","fix":"one actionable fix under 15 words","confidence":0.0-1.0}
If you are unsure, set confidence below 0.6 and the system will use the deterministic cue instead.`;

    try {
      const result = await this.callGeminiWithRetry(prompt);
      if (!result) return;

      const parsed = this.parseColdResponse(result);
      if (!parsed) {
        // Unparseable response — fall back to raw text at P2
        if (result.text) this.enqueueColdDecision(result.text, 2, parsed);
        return;
      }

      // Confidence routing: low confidence → let the deterministic delta cue stand
      if (parsed.confidence < CoachingService.COLD_CONFIDENCE_THRESHOLD) {
        return;
      }

      // High confidence → use the LLM's cause-first fix
      const text = parsed.fix || result.text || '';
      if (text) this.enqueueColdDecision(text, 2, parsed);
    } catch (err) {
      console.error('Cold path failed:', err);
    }
  }

  private async callGeminiWithRetry(
    prompt: string,
  ): Promise<{ text: string } | null> {
    const maxRetries = CoachingService.COLD_MAX_RETRIES;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const res = await fetch(
          'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent',
          {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'x-goog-api-key': this.apiKey!,
            },
            body: JSON.stringify({
              contents: [{ parts: [{ text: prompt }] }],
              generationConfig: {
                responseMimeType: 'application/json',
                temperature: 0.4,
                maxOutputTokens: 150,
              },
            }),
          },
        );
        if (res.ok) {
          const data = await res.json();
          const text = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
          this.coldRetryCount = 0;
          return { text };
        }
        // 4xx → don't retry (bad request / bad key)
        if (res.status >= 400 && res.status < 500) {
          console.error(`Cold path: client error ${res.status}, not retrying`);
          return null;
        }
        // 5xx → retry with backoff
        if (attempt < maxRetries) {
          this.coldRetryCount++;
          await new Promise(resolve => setTimeout(resolve, 500 * (attempt + 1)));
        }
      } catch (err) {
        if (attempt < maxRetries) {
          this.coldRetryCount++;
          await new Promise(resolve => setTimeout(resolve, 500 * (attempt + 1)));
        } else {
          throw err;
        }
      }
    }
    return null;
  }

  private parseColdResponse(response: { text: string }): { cause: string; fix: string; confidence: number } | null {
    if (!response.text) return null;
    try {
      const parsed = JSON.parse(response.text);
      if (typeof parsed.fix === 'string' && typeof parsed.confidence === 'number') {
        return {
          cause: parsed.cause ?? '',
          fix: parsed.fix,
          confidence: parsed.confidence,
        };
      }
    } catch {
      // Not JSON — try to extract from text
      return null;
    }
    return null;
  }

  private enqueueColdDecision(
    text: string,
    priority: 0 | 1 | 2 | 3,
    parsed: { cause: string; fix: string; confidence: number } | null,
  ): void {
    this.coachingQueue.enqueue({
      path: 'cold',
      text,
      priority,
      cornerPhase: this.currentPhase,
      timestamp: Date.now(),
      cornerId: this.lastCorner?.id,
      cornerName: this.lastCorner?.name,
      confidence: parsed?.confidence,
      causeId: parsed?.cause ? `cold_${parsed.cause.slice(0, 40).replace(/\s+/g, '_')}` : undefined,
    });
  }

  private inferColdRootCause(delta: DeltaAnalysis): string {
    if (delta.apexSpeedDelta < -10 && delta.brakeDelta > 20) {
      return 'overbraking at entry — braking too hard or too late, scrubbing speed';
    }
    if (delta.apexSpeedDelta < -10 && delta.brakeDelta <= 0) {
      return 'insufficient cornering commitment — not carrying enough speed through the apex';
    }
    if (delta.throttleTimingDelta > 15 && delta.phase === 'APEX') {
      return 'early throttle — getting on the gas before the apex, pushing the line wide';
    }
    if (delta.trackUseDelta < -0.15) {
      return 'poor track use — not using the full width on exit';
    }
    if (delta.brakeDelta > 25) {
      return 'brake modulation — stabbing instead of squeezing the brake';
    }
    return 'corner execution gap vs reference line';
  }

  // ── FEEDFORWARD: geofence-based corner advice ──────────

  private runFeedforward(frame: TelemetryFrame) {
    if (!this.track) return;
    if (!isValidGps(frame.latitude, frame.longitude)) return;
    const nearest = this.findNearestCorner(frame.latitude, frame.longitude, this.track.corners);

    if (nearest && nearest !== this.lastCorner) {
      this.lastCorner = nearest;
      this.coachingQueue.enqueue({
        path: 'feedforward',
        text: `📍 ${getTrackFeedforwardMessage(
          this.track,
          nearest,
          this.driverModel.getSkillLevel(),
          this.sessionPhase,
        )}`,
        priority: 1,
        cornerPhase: this.currentPhase,
        timestamp: Date.now(),
      });
    }
  }

  private findNearestCorner(lat: number, lon: number, corners: Corner[]): Corner | null {
    for (const c of corners) {
      const dist = haversineDistance(lat, lon, c.lat, c.lon);
      if (dist < 150) return c;
    }
    return null;
  }
}
