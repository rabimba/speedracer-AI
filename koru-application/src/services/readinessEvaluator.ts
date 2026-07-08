import type { SkillLevel } from '../types';

/**
 * ReadinessEvaluator — replaces wall-clock session phase with telemetry-based
 * readiness signal evaluation (Pillar 3).
 *
 * The T-Rod coaching data defines readinessSignals for each session step:
 *   Step 1 → 2: Consistent turn-in points, hitting apex, using full track width
 *   Step 2 → 3: Consistent gear selection, smooth RPM transitions
 *   Step 3 → 4: Brake pressure past turn-in, smooth brake release, improved entry speed
 *
 * Since we don't have reliable gear/RPM data from all telemetry sources, we
 * use proxies from the DriverModel and PerformanceTracker:
 *   Phase 1 → 2: Input smoothness ≥ 0.5 + ≥ 3 corners driven
 *   Phase 2 → 3: Brake smoothness (low brake-delta variance) + ≥ 6 corners
 *   Phase 3 → 4: Throttle commitment (avg exit throttle ≥ 70%) + ≥ 10 corners
 *
 * ADVANCED drivers skip to phase 3 immediately (existing behavior).
 */
export class ReadinessEvaluator {
  private cornersDriven = 0;
  private brakeDeltas: number[] = [];
  private exitThrottles: number[] = [];

  /** Called when a corner is completed (flushed from PerformanceTracker) */
  onCornerComplete(metrics: {
    maxBrake: number;
    maxThrottle: number;
    entrySpeed: number;
    exitSpeed: number;
    lapNumber: number;
  }): void {
    this.cornersDriven++;
    this.brakeDeltas.push(metrics.maxBrake);
    this.exitThrottles.push(metrics.maxThrottle);
  }

  /** Reset for a new session */
  reset(): void {
    this.cornersDriven = 0;
    this.brakeDeltas = [];
    this.exitThrottles = [];
  }

  /**
   * Evaluate the current session phase based on readiness signals.
   * Returns 1, 2, 3, or 4 (4 = throttle commitment phase).
   */
  evaluateSessionPhase(skillLevel: SkillLevel, inputSmoothness: number): 1 | 2 | 3 {
    // ADVANCED drivers skip to phase 3
    if (skillLevel === 'ADVANCED') return 3;

    // Phase 3 → throttle commitment readiness
    if (this.cornersDriven >= 10 && this.avgExitThrottle() >= 70) {
      return 3;
    }

    // Phase 2 → trail braking readiness (brake smoothness + enough corners)
    if (this.cornersDriven >= 6 && this.brakeSmoothness() >= 0.5) {
      return 2;
    }

    // Phase 1 → 2: lines and marks readiness (smoothness + some corners)
    if (this.cornersDriven >= 3 && inputSmoothness >= 0.5) {
      return 2;
    }

    return 1;
  }

  /** Brake smoothness: 1 - normalized variance of brake deltas */
  private brakeSmoothness(): number {
    if (this.brakeDeltas.length < 2) return 0;
    const mean = this.brakeDeltas.reduce((s, v) => s + v, 0) / this.brakeDeltas.length;
    const variance = this.brakeDeltas.reduce((s, v) => s + (v - mean) ** 2, 0) / this.brakeDeltas.length;
    return Math.max(0, 1 - Math.min(1, variance / 2500));
  }

  private avgExitThrottle(): number {
    if (this.exitThrottles.length === 0) return 0;
    return this.exitThrottles.reduce((s, v) => s + v, 0) / this.exitThrottles.length;
  }

  /** Get readiness diagnostics for UI/debugging */
  getDiagnostics(): {
    cornersDriven: number;
    brakeSmoothness: number;
    avgExitThrottle: number;
  } {
    return {
      cornersDriven: this.cornersDriven,
      brakeSmoothness: this.brakeSmoothness(),
      avgExitThrottle: this.avgExitThrottle(),
    };
  }
}
