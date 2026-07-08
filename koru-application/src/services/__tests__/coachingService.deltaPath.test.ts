import { describe, it, expect, beforeEach } from 'vitest';
import { CoachingService } from '../coachingService';
import { THUNDERHILL_EAST, SONOMA_RACEWAY } from '../../data/trackData';
import type { CoachingDecision, TelemetryFrame } from '../../types';

describe('CoachingService DELTA path', () => {
  let service: CoachingService;
  let decisions: CoachingDecision[];

  beforeEach(() => {
    service = new CoachingService();
    decisions = [];
    service.onCoaching(msg => decisions.push(msg));
  });

  const createFrame = (overrides: Partial<TelemetryFrame> = {}): TelemetryFrame => ({
    time: 0,
    latitude: 0,
    longitude: 0,
    speed: 60,
    throttle: 50,
    brake: 0,
    gLat: 0,
    gLong: 0,
    ...overrides,
  });

  const driveThunderhillCorner = (
    cornerId: number,
    apexTime: number,
    exitTime: number,
    apexSpeed: number,
  ): void => {
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === cornerId)!;

    // Apex frame — driver is at the corner apex (below reference speed).
    // gLat kept at 0.5 to avoid triggering the THROTTLE hot-path rule
    // (gLat > 0.6 && throttle < 50) which would block the timing gate.
    // The GPS detector still classifies APEX by distance, not G-force.
    service.processFrame(createFrame({
      time: apexTime,
      latitude: corner.lat,
      longitude: corner.lon,
      speed: apexSpeed,
      brake: 6,
      throttle: 14,
      gLat: 0.5,
      gLong: -0.06,
      distance: corner.apexDist,
    }));

    // Exit frame — driver has moved past the corner onto the straight.
    // Use invalid GPS (0,0) so the detector falls back to G-force classification
    // and reports ACCELERATION instead of staying locked to the corner.
    service.processFrame(createFrame({
      time: exitTime,
      latitude: 0,
      longitude: 0,
      speed: apexSpeed + 10,
      throttle: 35,
      brake: 0,
      gLat: 0.1,
      gLong: 0.05,
      distance: corner.exitDist + 50,
    }));
  };

  it('emits a delta cue at corner exit when the driver was slower than the reference at apex', () => {
    service.setTrack(THUNDERHILL_EAST);
    const corner = THUNDERHILL_EAST.corners.find(c => c.id === 1)!;

    driveThunderhillCorner(1, 10.2, 12.0, 48);

    const deltaDecision = decisions.find(d => d.causeId?.startsWith('delta_'));
    expect(deltaDecision).toBeDefined();
    expect(deltaDecision!.priority).toBe(2);
    expect(deltaDecision!.cornerId).toBe(corner.id);
    expect(deltaDecision!.cornerName).toBe(corner.name);
    expect(deltaDecision!.objective).toBeDefined();
    expect(deltaDecision!.causeId).toContain('delta_');
  });

  it('does not emit delta cues when the track has no reference trace', () => {
    const unknownTrack = { ...SONOMA_RACEWAY, name: 'Mystery Track' };
    service.setTrack(unknownTrack);

    const corner = unknownTrack.corners[0];
    service.processFrame(createFrame({
      time: 10,
      latitude: corner.lat,
      longitude: corner.lon,
      speed: 40,
      distance: 300,
    }));
    service.processFrame(createFrame({
      time: 12,
      latitude: corner.lat + 0.001,
      longitude: corner.lon + 0.001,
      speed: 55,
      throttle: 70,
      distance: 400,
    }));

    const deltaDecision = decisions.find(d => d.causeId?.startsWith('delta_'));
    expect(deltaDecision).toBeUndefined();
  });

  it('respects the per-corner cooldown and does not spam delta cues', () => {
    service.setTrack(THUNDERHILL_EAST);

    driveThunderhillCorner(1, 10.2, 12.0, 48);
    const firstCount = decisions.filter(d => d.causeId?.startsWith('delta_')).length;

    // Drive the same corner again within the cooldown
    driveThunderhillCorner(1, 13.0, 15.0, 48);

    const afterCount = decisions.filter(d => d.causeId?.startsWith('delta_')).length;
    expect(afterCount).toBe(firstCount);
  });

  it('skips non-actionable MAINTAIN cues from the delta layer', () => {
    service.setTrack(THUNDERHILL_EAST);

    // Drive at the reference speed → delta should be ~0 → MAINTAIN
    driveThunderhillCorner(1, 10.2, 12.0, 62);

    const deltaDecision = decisions.find(d => d.causeId?.startsWith('delta_'));
    expect(deltaDecision).toBeUndefined();
  });

  it('attaches a structured causeId encoding the delta direction', () => {
    service.setTrack(THUNDERHILL_EAST);

    driveThunderhillCorner(1, 10.2, 12.0, 48);

    const deltaDecision = decisions.find(d => d.causeId?.startsWith('delta_'));
    expect(deltaDecision).toBeDefined();
    expect(deltaDecision!.causeId).toMatch(/^delta_Turn 1 brake_/);
  });

  it('fires delta coaching for Thunderhill Cyclone (corner 7)', () => {
    service.setTrack(THUNDERHILL_EAST);

    driveThunderhillCorner(7, 53.5, 55.0, 35);

    const deltaDecision = decisions.find(d => d.causeId?.startsWith('delta_'));
    expect(deltaDecision).toBeDefined();
    expect(deltaDecision!.cornerId).toBe(7);
  });
});
