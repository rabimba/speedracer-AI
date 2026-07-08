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

export const THUNDERHILL_GOLD_TRACE: ReferenceTraceSample[] = [
  { time: 7.8, distance: 305, cornerId: 1, phase: 'BRAKE_ZONE', speed: 92, brake: 72, throttle: 0, gLat: 0.14, gLong: -0.96, trackUse: 0.90 },
  { time: 10.2, distance: 400, cornerId: 1, phase: 'APEX', speed: 62, brake: 6, throttle: 14, gLat: 1.04, gLong: -0.06, trackUse: 0.84 },
  { time: 22.5, distance: 890, cornerId: 2, phase: 'BRAKE_ZONE', speed: 78, brake: 54, throttle: 0, gLat: 0.16, gLong: -0.78, trackUse: 0.87 },
  { time: 25.0, distance: 985, cornerId: 2, phase: 'APEX', speed: 54, brake: 4, throttle: 16, gLat: 0.92, gLong: -0.02, trackUse: 0.80 },
  { time: 28.8, distance: 1134, cornerId: 3, phase: 'BRAKE_ZONE', speed: 70, brake: 38, throttle: 0, gLat: 0.12, gLong: -0.62, trackUse: 0.84 },
  { time: 31.0, distance: 1229, cornerId: 3, phase: 'APEX', speed: 60, brake: 8, throttle: 20, gLat: 0.88, gLong: 0.02, trackUse: 0.78 },
  { time: 43.5, distance: 1723, cornerId: 6, phase: 'BRAKE_ZONE', speed: 82, brake: 48, throttle: 0, gLat: 0.18, gLong: -0.70, trackUse: 0.86 },
  { time: 46.0, distance: 1818, cornerId: 6, phase: 'APEX', speed: 54, brake: 4, throttle: 16, gLat: 0.94, gLong: -0.04, trackUse: 0.80 },
  { time: 51.0, distance: 2024, cornerId: 7, phase: 'BRAKE_ZONE', speed: 68, brake: 36, throttle: 0, gLat: 0.20, gLong: -0.58, trackUse: 0.82 },
  { time: 53.5, distance: 2119, cornerId: 7, phase: 'APEX', speed: 50, brake: 6, throttle: 18, gLat: 0.90, gLong: -0.02, trackUse: 0.76 },
  { time: 58.5, distance: 2317, cornerId: 8, phase: 'BRAKE_ZONE', speed: 66, brake: 32, throttle: 0, gLat: 0.22, gLong: -0.52, trackUse: 0.80 },
  { time: 61.0, distance: 2412, cornerId: 8, phase: 'APEX', speed: 56, brake: 8, throttle: 22, gLat: 0.86, gLong: 0.04, trackUse: 0.78 },
  { time: 73.5, distance: 2919, cornerId: 10, phase: 'BRAKE_ZONE', speed: 88, brake: 58, throttle: 0, gLat: 0.14, gLong: -0.82, trackUse: 0.88 },
  { time: 76.0, distance: 3014, cornerId: 10, phase: 'APEX', speed: 70, brake: 4, throttle: 20, gLat: 1.00, gLong: 0.02, trackUse: 0.84 },
  { time: 87.0, distance: 3462, cornerId: 11, phase: 'BRAKE_ZONE', speed: 82, brake: 46, throttle: 0, gLat: 0.18, gLong: -0.68, trackUse: 0.85 },
  { time: 89.5, distance: 3557, cornerId: 11, phase: 'APEX', speed: 64, brake: 6, throttle: 16, gLat: 0.92, gLong: -0.04, trackUse: 0.80 },
  { time: 92.0, distance: 3691, cornerId: 12, phase: 'APEX', speed: 52, brake: 8, throttle: 14, gLat: 0.84, gLong: -0.02, trackUse: 0.76 },
  { time: 98.5, distance: 3873, cornerId: 13, phase: 'APEX', speed: 58, brake: 6, throttle: 22, gLat: 0.88, gLong: 0.06, trackUse: 0.82 },
  { time: 110.0, distance: 4380, cornerId: 14, phase: 'APEX', speed: 68, brake: 4, throttle: 38, gLat: 0.92, gLong: 0.10, trackUse: 0.86 },
  { time: 116.0, distance: 4536, cornerId: 15, phase: 'APEX', speed: 72, brake: 0, throttle: 58, gLat: 0.78, gLong: 0.18, trackUse: 0.90 },
];
