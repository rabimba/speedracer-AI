import { describe, expect, it } from 'vitest';
import { buildSessionGoals, recommendCoach } from '../preRaceGoals';

describe('pre-race goals', () => {
  it('builds up to three structured session goals from selected focuses', () => {
    const goals = buildSessionGoals(['braking', 'throttle', 'smoothness', 'vision'], '');

    expect(goals).toHaveLength(3);
    expect(goals[0]).toMatchObject({
      focus: 'braking',
      source: 'pre_race_chat',
    });
    expect(goals[1].prioritizedActions).toContain('HUSTLE');
  });

  it('recommends Rachel for technique-focused braking and smoothness work', () => {
    const goals = buildSessionGoals(['braking', 'smoothness'], '');
    const recommendation = recommendCoach(goals, 'telemetry');

    expect(recommendation.coachId).toBe('rachel');
    expect(recommendation.rationale).toContain('braking technique');
  });

  it('falls back to Super AJ for mixed or signal-limited sessions', () => {
    const goals = buildSessionGoals(['custom'], 'See if I am overdriving the entry.');
    const recommendation = recommendCoach(goals, 'device_test');

    expect(recommendation.coachId).toBe('superaj');
  });
});
