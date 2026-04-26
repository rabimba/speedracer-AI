import type {
  CoachingDecision,
  LiveBackendStatus,
  SessionMode,
  SSEConnectionStatus,
  TelemetryFrame,
  Track,
} from '../types';
import {
  hasAndroidBridge,
  requestAndroidBackendStatus,
  setAndroidAudioEnabled,
  setAndroidCoach,
  startAndroidLiveSession,
  stopAndroidLiveSession,
  subscribeAndroidBridge,
} from './androidBridge';
import { CoachingService } from './coachingService';
import { TelemetryStreamService } from './telemetryStreamService';

type Listener<T> = (value: T) => void;

interface LiveBackendAdapterOptions {
  apiKey: string | null;
  coachId: string;
  audioEnabled: boolean;
  track: Track;
}

const NATIVE_INITIAL_STATUS: LiveBackendStatus = {
  backend: 'deterministic',
  state: 'idle',
  detail: 'Waiting for Android live backend',
  lastUpdated: Date.now(),
  usesOnDeviceModel: false,
  supportedPaths: ['hot', 'feedforward', 'edge'],
};

const BROWSER_STATUS: LiveBackendStatus = {
  backend: 'browser',
  state: 'ready',
  detail: 'Browser telemetry + TypeScript coaching stack',
  lastUpdated: Date.now(),
  usesOnDeviceModel: false,
  supportedPaths: ['hot', 'cold', 'feedforward'],
};

export class LiveBackendAdapter {
  private readonly options: LiveBackendAdapterOptions;
  private readonly nativeMode = hasAndroidBridge();
  private readonly frameListeners = new Set<Listener<TelemetryFrame>>();
  private readonly statusListeners = new Set<Listener<SSEConnectionStatus>>();
  private readonly coachingListeners = new Set<Listener<CoachingDecision>>();
  private readonly backendListeners = new Set<Listener<LiveBackendStatus>>();

  private readonly stream = this.nativeMode ? null : TelemetryStreamService.getInstance();
  private readonly coach = this.nativeMode ? null : new CoachingService();
  private cleanup: Array<() => void> = [];
  private coachId: string;
  private audioEnabled: boolean;

  constructor(options: LiveBackendAdapterOptions) {
    this.options = options;
    this.coachId = options.coachId;
    this.audioEnabled = options.audioEnabled;

    if (this.nativeMode) {
      const unsubscribe = subscribeAndroidBridge((event) => {
        switch (event.type) {
          case 'telemetry_frame':
            this.frameListeners.forEach((listener) => listener(event.frame));
            break;
          case 'coaching_decision':
            this.coachingListeners.forEach((listener) => listener(event.decision));
            break;
          case 'backend_status':
            this.backendListeners.forEach((listener) => listener(event.status));
            this.statusListeners.forEach((listener) => listener(this.mapNativeStatus(event.status)));
            break;
        }
      });
      this.cleanup.push(unsubscribe);
      this.emitBackendStatus(NATIVE_INITIAL_STATUS);
      return;
    }

    if (!this.stream || !this.coach) return;

    this.coach.setTrack(options.track);
    this.coach.setCoach(options.coachId);
    if (options.apiKey) this.coach.setApiKey(options.apiKey);

    this.cleanup.push(this.stream.onStatus((status) => {
      this.statusListeners.forEach((listener) => listener(status));
    }));

    this.cleanup.push(this.stream.onFrame((frame) => {
      this.frameListeners.forEach((listener) => listener(frame));
      this.coach?.processFrame(frame);
    }));

    this.cleanup.push(this.coach.onCoaching((decision) => {
      this.coachingListeners.forEach((listener) => listener({
        ...decision,
        backend: 'browser',
      }));
    }));

    this.emitBackendStatus(BROWSER_STATUS);
  }

  onFrame(listener: Listener<TelemetryFrame>): () => void {
    this.frameListeners.add(listener);
    return () => this.frameListeners.delete(listener);
  }

  onStatus(listener: Listener<SSEConnectionStatus>): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  onCoaching(listener: Listener<CoachingDecision>): () => void {
    this.coachingListeners.add(listener);
    return () => this.coachingListeners.delete(listener);
  }

  onBackendStatus(listener: Listener<LiveBackendStatus>): () => void {
    this.backendListeners.add(listener);
    return () => this.backendListeners.delete(listener);
  }

  connect(sourceUrl?: string, sessionMode: SessionMode = 'telemetry'): void {
    if (this.nativeMode) {
      this.statusListeners.forEach((listener) => listener('connecting'));
      startAndroidLiveSession({
        coachId: this.coachId,
        audioEnabled: this.audioEnabled,
        trackName: this.options.track.name,
        sessionMode,
        sourceUrl: sourceUrl?.trim() || undefined,
      });
      requestAndroidBackendStatus();
      return;
    }

    const url = sourceUrl?.trim() || '/mock-telemetry.txt';
    this.stream?.connect(url);
  }

  disconnect(): void {
    if (this.nativeMode) {
      stopAndroidLiveSession();
      this.statusListeners.forEach((listener) => listener('disconnected'));
      return;
    }

    this.stream?.disconnect();
  }

  setCoach(coachId: string): void {
    this.coachId = coachId;
    if (this.nativeMode) {
      setAndroidCoach(coachId);
      return;
    }
    this.coach?.setCoach(coachId);
  }

  setApiKey(apiKey: string | null): void {
    if (!this.nativeMode && apiKey) {
      this.coach?.setApiKey(apiKey);
    }
  }

  setAudioEnabled(enabled: boolean): void {
    this.audioEnabled = enabled;
    if (this.nativeMode) {
      setAndroidAudioEnabled(enabled);
    }
  }

  requestBackendStatus(): void {
    if (this.nativeMode) {
      requestAndroidBackendStatus();
      return;
    }
    this.emitBackendStatus(BROWSER_STATUS);
  }

  destroy(): void {
    this.disconnect();
    this.cleanup.forEach((dispose) => dispose());
    this.cleanup = [];
  }

  isNativeMode(): boolean {
    return this.nativeMode;
  }

  private emitBackendStatus(status: LiveBackendStatus): void {
    this.backendListeners.forEach((listener) => listener(status));
  }

  private mapNativeStatus(status: LiveBackendStatus): SSEConnectionStatus {
    switch (status.state) {
      case 'idle':
        return 'disconnected';
      case 'starting':
        return 'connecting';
      case 'ready':
      case 'degraded':
        return 'connected';
      case 'unavailable':
      case 'error':
        return 'error';
    }
  }
}
