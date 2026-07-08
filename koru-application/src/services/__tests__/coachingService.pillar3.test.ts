import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LocalDriverProfileStore } from '../driverProfileStore';
import { ReadinessEvaluator } from '../readinessEvaluator';
import { CoachingService } from '../coachingService';
import { THUNDERHILL_EAST } from '../../data/trackData';
import type { DriverProfile, SessionSummary, TelemetryFrame, CoachingDecision } from '../../types';

// Mock localStorage for node test environment
const localStorageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value; },
    removeItem: (key: string) => { delete store[key]; },
    clear: () => { store = {}; },
    key: (index: number) => Object.keys(store)[index] ?? null,
    get length() { return Object.keys(store).length; },
  };
})();

Object.defineProperty(globalThis, 'localStorage', {
  value: localStorageMock,
  writable: true,
  configurable: true,
});

describe('DriverProfileStore (Pillar 3)', () => {
  let store: LocalDriverProfileStore;

  beforeEach(() => {
    localStorage.clear();
    store = new LocalDriverProfileStore();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('returns null when no profile exists', async () => {
    const profile = await store.load('new-driver');
    expect(profile).toBeNull();
  });

  it('saves and loads a profile', async () => {
    const profile: DriverProfile = {
      driverId: 'test-driver',
      currentSkillLevel: 'INTERMEDIATE',
      sessions: [],
      problemCorners: [3, 7],
      strengths: ['COMMIT'],
      weaknesses: ['SPIKE_BRAKE'],
    };

    await store.save(profile);
    const loaded = await store.load('test-driver');

    expect(loaded).not.toBeNull();
    expect(loaded!.driverId).toBe('test-driver');
    expect(loaded!.currentSkillLevel).toBe('INTERMEDIATE');
    expect(loaded!.problemCorners).toEqual([3, 7]);
  });

  it('adds a session and computes problem corners', async () => {
    const summary: SessionSummary = {
      sessionId: 's1',
      date: new Date().toISOString(),
      trackName: 'Thunderhill',
      totalLaps: 5,
      bestLapTime: 120,
      avgLapTime: 125,
      skillLevel: 'BEGINNER',
      cornerPerformance: [
        { cornerId: 1, cornerName: 'Turn 1', minSpeed: 45, brakePoint: 90, throttleApplication: 60, issueCount: 5 },
        { cornerId: 7, cornerName: 'Cyclone', minSpeed: 40, brakePoint: 80, throttleApplication: 50, issueCount: 3 },
      ],
      goalsAchieved: [],
    };

    await store.addSession('driver-1', summary);
    const profile = await store.load('driver-1');

    expect(profile).not.toBeNull();
    expect(profile!.sessions).toHaveLength(1);
    expect(profile!.problemCorners).toContain(1);
    expect(profile!.problemCorners).toContain(7);
  });

  it('keeps only the last 20 sessions', async () => {
    for (let i = 0; i < 25; i++) {
      await store.addSession('driver-1', {
        sessionId: `s${i}`,
        date: new Date().toISOString(),
        trackName: 'Test',
        totalLaps: 1,
        bestLapTime: 100,
        avgLapTime: 100,
        skillLevel: 'BEGINNER',
        cornerPerformance: [],
        goalsAchieved: [],
      });
    }
    const profile = await store.load('driver-1');
    expect(profile!.sessions.length).toBeLessThanOrEqual(20);
  });

  it('builds a session summary from corner histories', () => {
    const cornerHistories = new Map([
      [1, {
        cornerName: 'Turn 1',
        snapshots: [
          { lapNumber: 1, minSpeed: 45, maxBrake: 80, maxThrottle: 60, entrySpeed: 90, exitSpeed: 55 },
          { lapNumber: 2, minSpeed: 48, maxBrake: 75, maxThrottle: 65, entrySpeed: 88, exitSpeed: 58 },
        ],
      }],
    ]);

    const summary = store.buildSessionSummary('Thunderhill', 'BEGINNER', cornerHistories, ['BRAKE', 'SPIKE_BRAKE']);
    expect(summary.trackName).toBe('Thunderhill');
    expect(summary.cornerPerformance).toHaveLength(1);
    expect(summary.cornerPerformance[0].cornerId).toBe(1);
    expect(summary.cornerPerformance[0].issueCount).toBe(2);
  });
});

describe('ReadinessEvaluator (Pillar 3)', () => {
  let evaluator: ReadinessEvaluator;

  beforeEach(() => {
    evaluator = new ReadinessEvaluator();
  });

  it('starts at phase 1 with no corners driven', () => {
    expect(evaluator.evaluateSessionPhase('BEGINNER', 0.3)).toBe(1);
  });

  it('advances to phase 2 after 3 corners with decent smoothness', () => {
    for (let i = 0; i < 3; i++) {
      evaluator.onCornerComplete({
        maxBrake: 60, maxThrottle: 55, entrySpeed: 80, exitSpeed: 55, lapNumber: 1,
      });
    }
    expect(evaluator.evaluateSessionPhase('BEGINNER', 0.55)).toBe(2);
  });

  it('stays at phase 1 with low smoothness even after corners', () => {
    for (let i = 0; i < 3; i++) {
      evaluator.onCornerComplete({
        maxBrake: 60, maxThrottle: 55, entrySpeed: 80, exitSpeed: 55, lapNumber: 1,
      });
    }
    expect(evaluator.evaluateSessionPhase('BEGINNER', 0.3)).toBe(1);
  });

  it('advances to phase 3 after 10 corners with high throttle commitment', () => {
    for (let i = 0; i < 10; i++) {
      evaluator.onCornerComplete({
        maxBrake: 50, maxThrottle: 85, entrySpeed: 80, exitSpeed: 60, lapNumber: 1,
      });
    }
    expect(evaluator.evaluateSessionPhase('BEGINNER', 0.7)).toBe(3);
  });

  it('ADVANCED drivers skip to phase 3 immediately', () => {
    expect(evaluator.evaluateSessionPhase('ADVANCED', 0.9)).toBe(3);
  });

  it('resets correctly', () => {
    for (let i = 0; i < 5; i++) {
      evaluator.onCornerComplete({
        maxBrake: 60, maxThrottle: 55, entrySpeed: 80, exitSpeed: 55, lapNumber: 1,
      });
    }
    evaluator.reset();
    expect(evaluator.getDiagnostics().cornersDriven).toBe(0);
    expect(evaluator.evaluateSessionPhase('BEGINNER', 0.5)).toBe(1);
  });

  it('exposes diagnostics for UI/debugging', () => {
    evaluator.onCornerComplete({
      maxBrake: 60, maxThrottle: 70, entrySpeed: 80, exitSpeed: 55, lapNumber: 1,
    });
    const diag = evaluator.getDiagnostics();
    expect(diag.cornersDriven).toBe(1);
    expect(diag.avgExitThrottle).toBe(70);
    expect(diag.brakeSmoothness).toBeGreaterThanOrEqual(0);
  });
});

describe('CoachingService Pillar 3 integration', () => {
  let service: CoachingService;
  let decisions: CoachingDecision[];

  beforeEach(() => {
    localStorage.clear();
    service = new CoachingService();
    decisions = [];
    service.onCoaching(msg => decisions.push(msg));
  });

  afterEach(() => {
    localStorage.clear();
  });

  const createFrame = (overrides: Partial<TelemetryFrame> = {}): TelemetryFrame => ({
    time: 0, latitude: 0, longitude: 0, speed: 60, throttle: 50, brake: 0, gLat: 0, gLong: 0, ...overrides,
  });

  it('exposes readiness diagnostics', () => {
    service.setTrack(THUNDERHILL_EAST);
    const diag = service.getReadinessDiagnostics();
    expect(diag.cornersDriven).toBe(0);
  });

  it('exposes driver profile (null initially)', () => {
    service.setTrack(THUNDERHILL_EAST);
    expect(service.getDriverProfile()).toBeNull();
  });

  it('auto-generates session goals from a loaded driver profile with problem corners', async () => {
    // Pre-seed a profile with a problem corner
    const store = new LocalDriverProfileStore();
    await store.addSession('default', {
      sessionId: 's1',
      date: new Date().toISOString(),
      trackName: 'Thunderhill Raceway (East)',
      totalLaps: 5,
      bestLapTime: 120,
      avgLapTime: 125,
      skillLevel: 'BEGINNER',
      cornerPerformance: [
        { cornerId: 1, cornerName: 'Turn 1 brake', minSpeed: 40, brakePoint: 90, throttleApplication: 50, issueCount: 5 },
      ],
      goalsAchieved: [],
    });

    service.setTrack(THUNDERHILL_EAST);
    await service.loadDriverProfile();

    const goals = service.getSessionGoals();
    expect(goals.length).toBeGreaterThan(0);
    expect(goals.some(g => g.source === 'auto_generated')).toBe(true);
  });

  it('uses readiness-based session phase after corners are driven', () => {
    service.setTrack(THUNDERHILL_EAST);
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;

    // Drive 4 corners with decent smoothness to advance to phase 2
    for (let lap = 1; lap <= 4; lap++) {
      // Apex frame
      service.processFrame(createFrame({
        time: lap * 20 + 10.2,
        latitude: corner.lat,
        longitude: corner.lon,
        speed: 48,
        throttle: 40,
        brake: 10,
        gLat: 0.5,
        gLong: -0.06,
        distance: corner.apexDist,
      }));
      // Exit frame
      service.processFrame(createFrame({
        time: lap * 20 + 12.0,
        latitude: 0,
        longitude: 0,
        speed: 58,
        throttle: 35,
        brake: 0,
        gLat: 0.1,
        gLong: 0.05,
        distance: corner.exitDist + 50,
      }));
    }

    const diag = service.getReadinessDiagnostics();
    expect(diag.cornersDriven).toBeGreaterThanOrEqual(3);
  });

  it('saves a session summary', async () => {
    service.setTrack(THUNDERHILL_EAST);
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;

    // Drive one corner
    service.processFrame(createFrame({
      time: 10.2,
      latitude: corner.lat,
      longitude: corner.lon,
      speed: 48, throttle: 14, brake: 6, gLat: 0.5, gLong: -0.06,
      distance: corner.apexDist,
    }));
    service.processFrame(createFrame({
      time: 12.0,
      latitude: 0, longitude: 0,
      speed: 58, throttle: 35, brake: 0, gLat: 0.1, gLong: 0.05,
      distance: corner.exitDist + 50,
    }));

    await service.saveSessionSummary();

    // Verify it was saved
    const raw = localStorage.getItem('koru_driver_profile_default');
    expect(raw).not.toBeNull();
    const profile = JSON.parse(raw!) as DriverProfile;
    expect(profile.sessions).toHaveLength(1);
    expect(profile.sessions[0].trackName).toBe('Thunderhill Raceway (East)');
  });
});
