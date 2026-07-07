import { describe, expect, it } from 'vitest';
import { CornerPhaseDetector, THUNDERHILL_EAST, getTrackByName } from '../../index.js';
import type { TelemetryFrame } from '../../index.js';

function makeFrame(overrides: Partial<TelemetryFrame> = {}): TelemetryFrame {
  return {
    time: 0,
    latitude: 0,
    longitude: 0,
    speed: 60,
    throttle: 0,
    brake: 0,
    gLat: 0,
    gLong: 0,
    ...overrides,
  };
}

describe('track catalog', () => {
  it('keeps Thunderhill East as a full GPS anchored course', () => {
    expect(THUNDERHILL_EAST.corners).toHaveLength(15);
    expect(THUNDERHILL_EAST.corners.every((corner) => corner.entryLat && corner.entryLon)).toBe(true);
    expect(THUNDERHILL_EAST.corners.every((corner) => typeof corner.targetSpeed === 'number')).toBe(true);
    expect(getTrackByName('Thunderhill')).toBe(THUNDERHILL_EAST);
  });

  it('detects a Thunderhill Cyclone entry from GPS', () => {
    const corner = THUNDERHILL_EAST.corners.find((item) => item.name.includes('Cyclone approach'));
    expect(corner).toBeDefined();

    const detector = new CornerPhaseDetector();
    detector.setTrack(THUNDERHILL_EAST);

    const detection = detector.detect(makeFrame({
      latitude: corner!.entryLat,
      longitude: corner!.entryLon,
      speed: 62,
      brake: 8,
      gLat: 0.1,
      gLong: -0.1,
    }));

    expect(detection.cornerId).toBe(corner!.id);
    expect(detection.phase).toBe('TURN_IN');
  });
});
