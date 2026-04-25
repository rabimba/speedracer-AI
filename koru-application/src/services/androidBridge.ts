import type { CoachingDecision, LiveBackendStatus, TelemetryFrame } from '../types';

export interface NativeLiveSessionConfig {
  coachId: string;
  audioEnabled: boolean;
  trackName: string;
  sourceUrl?: string;
}

export type AndroidBridgeEvent =
  | { type: 'telemetry_frame'; frame: TelemetryFrame }
  | { type: 'coaching_decision'; decision: CoachingDecision }
  | { type: 'backend_status'; status: LiveBackendStatus };

interface NativeBridgeHost {
  startLiveSession(configJson: string): void;
  stopLiveSession(): void;
  setActiveCoach(coachId: string): void;
  setAudioEnabled(enabled: boolean): void;
  requestBackendStatus(): void;
}

declare global {
  interface Window {
    AndroidBridge?: NativeBridgeHost;
    __koruDispatchNativeEvent?: (payload: AndroidBridgeEvent | string) => void;
  }
}

const BRIDGE_EVENT_NAME = 'koru-android';

function normalizeBridgeEvent(payload: AndroidBridgeEvent | string): AndroidBridgeEvent | null {
  if (typeof payload === 'string') {
    try {
      return JSON.parse(payload) as AndroidBridgeEvent;
    } catch {
      return null;
    }
  }
  return payload;
}

export function hasAndroidBridge(): boolean {
  return typeof window !== 'undefined' && typeof window.AndroidBridge !== 'undefined';
}

export function installAndroidBridgeDispatcher(): void {
  if (typeof window === 'undefined' || window.__koruDispatchNativeEvent) return;

  window.__koruDispatchNativeEvent = (payload: AndroidBridgeEvent | string) => {
    const detail = normalizeBridgeEvent(payload);
    if (!detail) return;
    window.dispatchEvent(new CustomEvent<AndroidBridgeEvent>(BRIDGE_EVENT_NAME, { detail }));
  };
}

export function subscribeAndroidBridge(
  listener: (event: AndroidBridgeEvent) => void,
): () => void {
  installAndroidBridgeDispatcher();
  const handler = (event: Event) => {
    const detail = (event as CustomEvent<AndroidBridgeEvent>).detail;
    listener(detail);
  };
  window.addEventListener(BRIDGE_EVENT_NAME, handler);
  return () => window.removeEventListener(BRIDGE_EVENT_NAME, handler);
}

export function startAndroidLiveSession(config: NativeLiveSessionConfig): void {
  window.AndroidBridge?.startLiveSession(JSON.stringify(config));
}

export function stopAndroidLiveSession(): void {
  window.AndroidBridge?.stopLiveSession();
}

export function setAndroidCoach(coachId: string): void {
  window.AndroidBridge?.setActiveCoach(coachId);
}

export function setAndroidAudioEnabled(enabled: boolean): void {
  window.AndroidBridge?.setAudioEnabled(enabled);
}

export function requestAndroidBackendStatus(): void {
  window.AndroidBridge?.requestBackendStatus();
}
