import { describe, expect, it } from 'vitest';
import { buildDemoSession, summarizeSession } from '../paddockDemo';

describe('paddock demo model', () => {
  it('builds DEL summaries for dashboard surfaces', () => {
    const session = buildDemoSession();
    const summary = summarizeSession(session);

    expect(summary.deltas.length).toBe(session.frames.length);
    expect(summary.worstDelta.cue.length).toBeGreaterThan(0);
    expect(summary.brakeHealth.severity).toBe('critical');
    expect(summary.biometric.state).toBe('panic_overload');
  });
});
