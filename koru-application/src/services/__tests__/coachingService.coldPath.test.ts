import { describe, expect, it } from 'vitest';
import { coldPathInstructionForSkill } from '../coachingService';

describe('CoachingService COLD path prompt', () => {
  it('prioritizes root cause over symptom for beginner coaching', () => {
    const instruction = coldPathInstructionForSkill('BEGINNER');

    expect(instruction).toContain('Prioritize WHY over WHAT');
    expect(instruction).toContain('root cause');
    expect(instruction).toContain('brake release');
    expect(instruction).toContain('weight transfer');
    expect(instruction).toContain('line choice');
    expect(instruction).toContain('throttle timing');
    expect(instruction).toContain('vision target');
    expect(instruction).not.toContain('Give ONE simple instruction');
  });

  it('keeps advanced coaching cause-first while allowing telemetry references', () => {
    const instruction = coldPathInstructionForSkill('ADVANCED');

    expect(instruction).toContain('root cause');
    expect(instruction).toContain('telemetry numbers');
    expect(instruction).toContain('cause-first');
    expect(instruction).toContain('Do not merely describe the symptom');
  });
});
