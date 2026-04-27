import type {
  CoachingDecision,
  LiveBackendStatus,
  RecordedSessionArtifact,
  SessionMode,
  TelemetryFrame,
} from '../types';

export interface NativeLiveSessionConfig {
  coachId: string;
  audioEnabled: boolean;
  trackName: string;
  sessionMode?: SessionMode;
  sourceUrl?: string;
}

export type AndroidBridgeEvent =
  | { type: 'telemetry_frame'; frame: TelemetryFrame }
  | { type: 'coaching_decision'; decision: CoachingDecision }
  | { type: 'backend_status'; status: LiveBackendStatus }
  | { type: 'session_saved'; session: RecordedSessionArtifact };

interface NativeBridgeHost {
  startLiveSession(configJson: string): void;
  stopLiveSession(): void;
  setActiveCoach(coachId: string): void;
  setAudioEnabled(enabled: boolean): void;
  requestBackendStatus(): void;
  getBridgeVersion(): string;
}

declare global {
  interface Window {
    AndroidBridge?: NativeBridgeHost;
    __koruDispatchNativeEvent?: (payload: AndroidBridgeEvent | string) => void;
    __koruPendingNativeEvents?: Array<AndroidBridgeEvent | string>;
  }
}

const BRIDGE_EVENT_NAME = 'koru-android';
const REQUIRED_ANDROID_BRIDGE_VERSION = 'android-bridge-2';

function getNativeBridgeHost(): NativeBridgeHost | null {
  if (typeof window === 'undefined') return null;
  return window.AndroidBridge ?? null;
}

function logBridge(message: string, detail?: unknown): void {
  if (detail === undefined) {
    console.info(`[KoruBridge] ${message}`);
    return;
  }
  console.info(`[KoruBridge] ${message}`, detail);
}

function warnBridge(message: string, detail?: unknown): void {
  if (detail === undefined) {
    console.warn(`[KoruBridge] ${message}`);
    return;
  }
  console.warn(`[KoruBridge] ${message}`, detail);
}

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

export function getAndroidBridgeVersion(): string | null {
  const bridge = getNativeBridgeHost();
  if (!bridge || typeof bridge.getBridgeVersion !== 'function') return null;

  try {
    return bridge.getBridgeVersion();
  } catch (error) {
    warnBridge('Failed to read native bridge version', error);
    return null;
  }
}

export function hasAndroidBridge(): boolean {
  return getAndroidBridgeVersion() === REQUIRED_ANDROID_BRIDGE_VERSION;
}

export function installAndroidBridgeDispatcher(): void {
  if (typeof window === 'undefined' || window.__koruDispatchNativeEvent) return;

  window.__koruDispatchNativeEvent = (payload: AndroidBridgeEvent | string) => {
    const detail = normalizeBridgeEvent(payload);
    if (!detail) return;
    window.dispatchEvent(new CustomEvent<AndroidBridgeEvent>(BRIDGE_EVENT_NAME, { detail }));
  };

  const pending = window.__koruPendingNativeEvents ?? [];
  window.__koruPendingNativeEvents = [];
  pending.forEach((payload) => {
    window.__koruDispatchNativeEvent?.(payload);
  });
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
  const bridge = getNativeBridgeHost();
  const version = getAndroidBridgeVersion();
  if (!bridge || version !== REQUIRED_ANDROID_BRIDGE_VERSION) {
    warnBridge('Skipping startLiveSession because the native bridge is unavailable or stale', {
      expected: REQUIRED_ANDROID_BRIDGE_VERSION,
      actual: version,
      config,
    });
    return;
  }

  logBridge(`startLiveSession version=${version}`, config);
  bridge.startLiveSession(JSON.stringify(config));
}

export function stopAndroidLiveSession(): void {
  const bridge = getNativeBridgeHost();
  const version = getAndroidBridgeVersion();
  if (!bridge || version !== REQUIRED_ANDROID_BRIDGE_VERSION) {
    warnBridge('Skipping stopLiveSession because the native bridge is unavailable or stale', {
      expected: REQUIRED_ANDROID_BRIDGE_VERSION,
      actual: version,
    });
    return;
  }

  logBridge(`stopLiveSession version=${version}`);
  bridge.stopLiveSession();
}

export function setAndroidCoach(coachId: string): void {
  const bridge = getNativeBridgeHost();
  const version = getAndroidBridgeVersion();
  if (!bridge || version !== REQUIRED_ANDROID_BRIDGE_VERSION) {
    warnBridge('Skipping setActiveCoach because the native bridge is unavailable or stale', {
      expected: REQUIRED_ANDROID_BRIDGE_VERSION,
      actual: version,
      coachId,
    });
    return;
  }

  logBridge(`setActiveCoach version=${version} coach=${coachId}`);
  bridge.setActiveCoach(coachId);
}

export function setAndroidAudioEnabled(enabled: boolean): void {
  const bridge = getNativeBridgeHost();
  const version = getAndroidBridgeVersion();
  if (!bridge || version !== REQUIRED_ANDROID_BRIDGE_VERSION) {
    warnBridge('Skipping setAudioEnabled because the native bridge is unavailable or stale', {
      expected: REQUIRED_ANDROID_BRIDGE_VERSION,
      actual: version,
      enabled,
    });
    return;
  }

  logBridge(`setAudioEnabled version=${version} enabled=${enabled}`);
  bridge.setAudioEnabled(enabled);
}

export function requestAndroidBackendStatus(): void {
  const bridge = getNativeBridgeHost();
  const version = getAndroidBridgeVersion();
  if (!bridge || version !== REQUIRED_ANDROID_BRIDGE_VERSION) {
    warnBridge('Skipping requestBackendStatus because the native bridge is unavailable or stale', {
      expected: REQUIRED_ANDROID_BRIDGE_VERSION,
      actual: version,
    });
    return;
  }

  logBridge(`requestBackendStatus version=${version}`);
  bridge.requestBackendStatus();
}
