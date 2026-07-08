import { describe, it, expect } from 'vitest';
import { shouldSuppressTrackAction, getTrackHotActionMessage } from '../../data/trackExpertise';
import { THUNDERHILL_EAST, SONOMA_RACEWAY } from '../../data/trackData';
import type { SkillLevel } from '../../types';

describe('Corner doctrine properties (de-hardcoded track expertise)', () => {
  const intermediate: SkillLevel = 'INTERMEDIATE';

  it('suppresses PUSH on Thunderhill maintenance corners (Cyclone)', () => {
    const cycloneCorner = THUNDERHILL_EAST.corners.find(c => c.id === 6)!;
    expect(cycloneCorner.doctrine?.maintenance).toBe(true);

    const suppressed = shouldSuppressTrackAction(
      THUNDERHILL_EAST, cycloneCorner, 'PUSH', intermediate, 2, 'MID_CORNER', 0.3,
    );
    expect(suppressed).toBe(true);
  });

  it('does not suppress PUSH on Thunderhill non-maintenance corners', () => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 9)!;
    expect(corner.doctrine?.maintenance).toBeFalsy();

    const suppressed = shouldSuppressTrackAction(
      THUNDERHILL_EAST, corner, 'PUSH', intermediate, 2, 'STRAIGHT', 0.3,
    );
    expect(suppressed).toBe(false);
  });

  it('suppresses throttle on exit-priority corners when not at exit phase', () => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;
    expect(corner.doctrine?.exitPriority).toBe(true);

    const suppressed = shouldSuppressTrackAction(
      THUNDERHILL_EAST, corner, 'THROTTLE', intermediate, 2, 'MID_CORNER', 0.3,
    );
    expect(suppressed).toBe(true);
  });

  it('allows throttle on exit-priority corners at exit phase', () => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;

    const suppressed = shouldSuppressTrackAction(
      THUNDERHILL_EAST, corner, 'THROTTLE', intermediate, 2, 'EXIT', 0.3,
    );
    expect(suppressed).toBe(false);
  });

  it('generates doctrine-keyed hot action text for Thunderhill brake zone', () => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;
    expect(corner.doctrine?.brakeZone).toBe(true);

    const text = getTrackHotActionMessage(
      THUNDERHILL_EAST, corner, 'BRAKE', intermediate, 2, 'BRAKE_ZONE', 0.3,
    );
    expect(text).toContain('Turn 1 brake');
    expect(text).toContain('squeeze');
  });

  it('generates doctrine-keyed hot action text for Thunderhill maintenance corner', () => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 6)!;
    expect(corner.doctrine?.maintenance).toBe(true);

    const text = getTrackHotActionMessage(
      THUNDERHILL_EAST, corner, 'COAST', intermediate, 2, 'MID_CORNER', 0.3,
    );
    expect(text).toContain('maintenance throttle');
  });

  it('still works for Sonoma corners with doctrine properties', () => {
    const corner = SONOMA_RACEWAY.corners.find(c => c.id === 6)!;
    expect(corner.doctrine?.maintenance).toBe(true);

    const text = getTrackHotActionMessage(
      SONOMA_RACEWAY, corner, 'PUSH', intermediate, 2, 'MID_CORNER', 0.3,
    );
    expect(text!.toLowerCase()).toContain('distance is king');
  });

  it('returns null for corners without doctrine tags', () => {
    const untaggedCorner = { ...THUNDERHILL_EAST.corners[0], doctrine: undefined };
    const text = getTrackHotActionMessage(
      THUNDERHILL_EAST, untaggedCorner, 'BRAKE', intermediate, 2, 'BRAKE_ZONE', 0.3,
    );
    expect(text).toBeNull();
  });
});
