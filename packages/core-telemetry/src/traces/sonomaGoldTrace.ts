import type { CornerPhase } from '../types/cornerPhase.js';

export interface ReferenceTraceSample {
  time: number;
  distance: number;
  cornerId: number | null;
  phase: CornerPhase;
  speed: number;
  brake: number;
  throttle: number;
  gLat: number;
  gLong: number;
  trackUse: number;
}

export const SONOMA_GOLD_TRACE: ReferenceTraceSample[] = [
  { time: 8.2, distance: 300, cornerId: 2, phase: 'BRAKE_ZONE', speed: 84, brake: 68, throttle: 0, gLat: 0.18, gLong: -0.92, trackUse: 0.88 },
  { time: 10.1, distance: 390, cornerId: 2, phase: 'APEX', speed: 70, brake: 8, throttle: 18, gLat: 1.08, gLong: -0.08, trackUse: 0.82 },
  { time: 17.8, distance: 560, cornerId: 3, phase: 'BRAKE_ZONE', speed: 72, brake: 56, throttle: 0, gLat: 0.12, gLong: -0.74, trackUse: 0.91 },
  { time: 20.2, distance: 640, cornerId: 3, phase: 'APEX', speed: 52, brake: 4, throttle: 16, gLat: 0.92, gLong: -0.04, trackUse: 0.86 },
  { time: 28.4, distance: 850, cornerId: 31, phase: 'APEX', speed: 45, brake: 6, throttle: 14, gLat: 0.84, gLong: -0.02, trackUse: 0.78 },
  { time: 52.0, distance: 1760, cornerId: 6, phase: 'MID_CORNER', speed: 65, brake: 0, throttle: 28, gLat: 1.02, gLong: 0.04, trackUse: 0.74 },
  { time: 67.5, distance: 2190, cornerId: 7, phase: 'APEX', speed: 52, brake: 3, throttle: 18, gLat: 0.96, gLong: -0.03, trackUse: 0.80 },
  { time: 88.6, distance: 3230, cornerId: 910, phase: 'MID_CORNER', speed: 58, brake: 12, throttle: 24, gLat: 1.12, gLong: -0.06, trackUse: 0.84 },
  { time: 101.2, distance: 3700, cornerId: 11, phase: 'APEX', speed: 42, brake: 5, throttle: 10, gLat: 0.74, gLong: -0.04, trackUse: 0.81 },
  { time: 110.1, distance: 3990, cornerId: 12, phase: 'APEX', speed: 80, brake: 0, throttle: 64, gLat: 0.88, gLong: 0.16, trackUse: 0.90 },
];
