import type {
  CoachAction,
  CornerPerformance,
  DriverProfile,
  DriverProfileStore,
  SessionSummary,
} from '../types';

const STORAGE_KEY = 'koru_driver_profile';
const DEFAULT_DRIVER_ID = 'default';

/**
 * localStorage-backed DriverProfileStore (Phase 6.3 — Pillar 3).
 *
 * Persists cross-session corner performance, problem corners, and skill level
 * so coaching can reference recurring weaknesses and tailor session goals.
 * Falls back to an in-memory profile when localStorage is unavailable
 * (WebView private mode, test environment, etc.).
 */
export class LocalDriverProfileStore implements DriverProfileStore {
  private cache: DriverProfile | null = null;

  async load(driverId: string): Promise<DriverProfile | null> {
    try {
      const raw = localStorage.getItem(this.keyFor(driverId));
      if (raw) {
        this.cache = JSON.parse(raw) as DriverProfile;
        return this.cache;
      }
    } catch {
      // localStorage unavailable or corrupt — fall through to memory
    }
    return this.cache;
  }

  async save(profile: DriverProfile): Promise<void> {
    this.cache = profile;
    try {
      localStorage.setItem(this.keyFor(profile.driverId), JSON.stringify(profile));
    } catch {
      // localStorage unavailable — keep in-memory only
    }
  }

  async addSession(driverId: string, summary: SessionSummary): Promise<void> {
    let profile = await this.load(driverId);
    if (!profile) {
      profile = {
        driverId,
        currentSkillLevel: summary.skillLevel,
        sessions: [],
        problemCorners: [],
        strengths: [],
        weaknesses: [],
      };
    }

    profile.sessions.push(summary);
    if (profile.sessions.length > 20) {
      profile.sessions = profile.sessions.slice(-20);
    }

    profile.currentSkillLevel = summary.skillLevel;
    profile.problemCorners = this.computeProblemCorners(profile.sessions);
    profile.strengths = this.computeStrengths(profile.sessions);
    profile.weaknesses = this.computeWeaknesses(profile.sessions);

    await this.save(profile);
  }

  /**
   * Build a session summary from the in-session PerformanceTracker data.
   * Called when a session ends to persist the corner performance data.
   */
  buildSessionSummary(
    trackName: string,
    skillLevel: import('../types').SkillLevel,
    cornerHistories: Map<number, {
      cornerName: string;
      snapshots: {
        lapNumber: number;
        minSpeed: number;
        maxBrake: number;
        maxThrottle: number;
        entrySpeed: number;
        exitSpeed: number;
      }[];
    }>,
    coachingActions: CoachAction[],
    goalsAchieved: string[] = [],
  ): SessionSummary {
    const cornerPerformance: CornerPerformance[] = [];
    let totalLaps = 0;

    for (const [cornerId, history] of cornerHistories) {
      if (history.snapshots.length === 0) continue;
      totalLaps = Math.max(totalLaps, ...history.snapshots.map(s => s.lapNumber));

      const lastSnap = history.snapshots[history.snapshots.length - 1];
      cornerPerformance.push({
        cornerId,
        cornerName: history.cornerName,
        minSpeed: lastSnap.minSpeed,
        brakePoint: lastSnap.entrySpeed,
        throttleApplication: lastSnap.maxThrottle,
        issueCount: coachingActions.filter(a => a !== 'MAINTAIN' && a !== 'STABILIZE').length,
      });
    }

    return {
      sessionId: `session-${Date.now()}`,
      date: new Date().toISOString(),
      trackName,
      totalLaps,
      bestLapTime: 0,
      avgLapTime: 0,
      skillLevel,
      cornerPerformance,
      goalsAchieved,
    };
  }

  private computeProblemCorners(sessions: SessionSummary[]): number[] {
    const issueCounts = new Map<number, number>();
    for (const session of sessions) {
      for (const corner of session.cornerPerformance) {
        issueCounts.set(corner.cornerId, (issueCounts.get(corner.cornerId) ?? 0) + corner.issueCount);
      }
    }
    const sorted = [...issueCounts.entries()].sort((a, b) => b[1] - a[1]);
    return sorted.filter(([, count]) => count >= 3).map(([id]) => id).slice(0, 5);
  }

  private computeStrengths(sessions: SessionSummary[]): CoachAction[] {
    // Actions the driver rarely triggers = doing well
    // For now, infer from throttle application: high throttle = COMMIT strength
    const avgThrottle = this.avgAcrossSessions(sessions, c => c.throttleApplication);
    if (avgThrottle > 80) return ['COMMIT', 'FULL_THROTTLE'];
    return [];
  }

  private computeWeaknesses(sessions: SessionSummary[]): CoachAction[] {
    // Actions the driver frequently triggers = needs work
    const avgBrake = this.avgAcrossSessions(sessions, c => c.brakePoint);
    if (avgBrake > 60) return ['SPIKE_BRAKE', 'BRAKE'];
    return [];
  }

  private avgAcrossSessions(
    sessions: SessionSummary[],
    selector: (c: CornerPerformance) => number,
  ): number {
    const values: number[] = [];
    for (const session of sessions) {
      for (const corner of session.cornerPerformance) {
        values.push(selector(corner));
      }
    }
    if (values.length === 0) return 0;
    return values.reduce((sum, v) => sum + v, 0) / values.length;
  }

  private keyFor(driverId: string): string {
    return `${STORAGE_KEY}_${driverId}`;
  }

  static getDefaultDriverId(): string {
    return DEFAULT_DRIVER_ID;
  }
}
