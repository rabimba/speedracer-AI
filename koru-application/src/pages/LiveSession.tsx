import { useState, useEffect, useRef, useCallback } from 'react';
import { useTTS } from '../hooks/useTTS';
import { THUNDERHILL_EAST } from '../data/trackData';
import TelemetryCharts from '../components/TelemetryCharts';
import TrackMap from '../components/TrackMap';
import CoachPanel, { type CoachMessage } from '../components/CoachPanel';
import GaugeCluster from '../components/GaugeCluster';
import type {
  LiveBackendStatus,
  SessionMode,
  TelemetryFrame,
  TelemetrySourceKind,
  SSEConnectionStatus,
  TTSProvider,
} from '../types';
import { Radio, Unplug } from 'lucide-react';
import { LiveBackendAdapter } from '../services/liveBackendAdapter';
import { isNativeBackend } from '../services/sharedPhraseCatalog';
import { hasAndroidBridge, subscribeAndroidBridge } from '../services/androidBridge';
import { storeLatestRecordedSession } from '../services/recordedSessionStore';

interface LiveSessionProps {
  apiKey: string | null;
}

export default function LiveSession({ apiKey }: LiveSessionProps) {
  const [frames, setFrames] = useState<TelemetryFrame[]>([]);
  const [messages, setMessages] = useState<CoachMessage[]>([]);
  const [status, setStatus] = useState<SSEConnectionStatus>('disconnected');
  const [activeCoach, setActiveCoach] = useState('superaj');
  const [audioEnabled, setAudioEnabled] = useState(true);
  const [sseUrl, setSseUrl] = useState('');
  const [backendStatus, setBackendStatus] = useState<LiveBackendStatus | null>(null);
  const [nativeMode, setNativeMode] = useState(() => hasAndroidBridge());
  const [sessionMode, setSessionMode] = useState<SessionMode>('telemetry');
  const [telemetrySource, setTelemetrySource] = useState<TelemetrySourceKind>('phone_imu_gps');

  const adapterRef = useRef<LiveBackendAdapter | null>(null);
  const audioEnabledRef = useRef(audioEnabled);
  const initialConfigRef = useRef({ apiKey, activeCoach, audioEnabled });
  const { speak, setProvider, provider } = useTTS(apiKey, activeCoach);
  const speakRef = useRef(speak);

  useEffect(() => {
    speakRef.current = speak;
  }, [speak]);

  useEffect(() => {
    audioEnabledRef.current = audioEnabled;
    adapterRef.current?.setAudioEnabled(audioEnabled);
  }, [audioEnabled]);

  useEffect(() => {
    const initialConfig = initialConfigRef.current;
    const adapter = new LiveBackendAdapter({
      apiKey: initialConfig.apiKey,
      coachId: initialConfig.activeCoach,
      audioEnabled: initialConfig.audioEnabled,
      track: THUNDERHILL_EAST,
    });
    adapterRef.current = adapter;
    setNativeMode(adapter.isNativeMode());

    const unsubStatus = adapter.onStatus(setStatus);
    const unsubFrame = adapter.onFrame((frame) => {
      setFrames(prev => [...prev.slice(-500), frame]);
    });

    const unsubCoach = adapter.onCoaching((msg) => {
      const coachMsg: CoachMessage = {
        id: `${Date.now()}-${Math.random()}`,
        path: msg.path,
        text: msg.text,
        timestamp: Date.now(),
        backend: msg.backend,
        priority: msg.priority,
        confidence: msg.confidence,
      };
      setMessages(prev => [...prev, coachMsg]);

      if (audioEnabledRef.current && msg.text && !isNativeBackend(msg.backend)) {
        speakRef.current(msg.text);
      }
    });

    const unsubBackend = adapter.onBackendStatus((nextStatus) => {
      setNativeMode(isNativeBackend(nextStatus.backend));
      setBackendStatus(nextStatus);
    });

    adapter.requestBackendStatus();

    return () => {
      unsubStatus();
      unsubFrame();
      unsubCoach();
      unsubBackend();
      adapter.destroy();
      adapterRef.current = null;
    };
  }, []);

  useEffect(() => {
    adapterRef.current?.setApiKey(apiKey);
  }, [apiKey]);

  useEffect(() => {
    if (!nativeMode) return;

    return subscribeAndroidBridge((event) => {
      if (event.type !== 'session_saved') return;
      storeLatestRecordedSession(event.session);
      setMessages((prev) => [
        ...prev,
        {
          id: `saved-${event.session.id}`,
          path: 'edge',
          text: `Saved ${event.session.summary.frameCount} fused frames for replay analysis.`,
          timestamp: Date.now(),
          backend: 'deterministic',
          priority: 2,
        },
      ]);
    });
  }, [nativeMode]);

  const handleConnect = useCallback(() => {
    if (status !== 'disconnected') {
      adapterRef.current?.disconnect();
    } else {
      setFrames([]);
      setMessages([]);
      adapterRef.current?.connect(
        sseUrl.trim(),
        nativeMode ? sessionMode : 'telemetry',
        telemetrySource,
      );
    }
  }, [nativeMode, sessionMode, sseUrl, status, telemetrySource]);

  const handleCoachChange = useCallback((id: string) => {
    setActiveCoach(id);
    adapterRef.current?.setCoach(id);
  }, []);

  const currentFrame = frames[frames.length - 1] || null;

  return (
    <div className="page live-session">
      <header className="page-header">
        <h1><Radio size={20} /> Live Session</h1>
        <div className="live-controls">
          {nativeMode && (
            <select
              className="tts-select"
              value={sessionMode}
              onChange={e => setSessionMode(e.target.value as SessionMode)}
            >
              <option value="telemetry">Telemetry + Camera Fusion</option>
              <option value="device_test">Device Camera + GPS Test</option>
              <option value="camera_direct">Camera Feedback (Debug)</option>
            </select>
          )}
          {nativeMode && sessionMode === 'telemetry' && (
            <select
              className="tts-select"
              value={telemetrySource}
              onChange={e => setTelemetrySource(e.target.value as TelemetrySourceKind)}
            >
              <option value="synthetic">Synthetic Telemetry</option>
              <option value="phone_imu_gps">Phone IMU + GPS</option>
              <option value="racebox_ble">RaceBox BLE</option>
              <option value="obd_bluetooth">OBD Bluetooth</option>
            </select>
          )}
          {!nativeMode && (
            <input
              type="text"
              placeholder="SSE URL or .txt file path"
              value={sseUrl}
              onChange={e => setSseUrl(e.target.value)}
              className="sse-input"
            />
          )}
          <button
            className={`connect-btn ${status === 'connected' ? 'connected' : ''}`}
            onClick={handleConnect}
          >
            {status !== 'disconnected'
              ? <><Unplug size={14} /> {
                nativeMode && sessionMode === 'camera_direct'
                  ? 'Stop Feedback'
                  : nativeMode && sessionMode === 'device_test'
                    ? 'Stop Device Test'
                    : 'Disconnect'
              }</>
              : <><Radio size={14} /> {
                nativeMode && sessionMode === 'camera_direct'
                  ? 'Start Feedback'
                  : nativeMode && sessionMode === 'device_test'
                    ? 'Start Device Test'
                    : 'Connect'
              }</>}
          </button>
          <span className={`status-badge status-${status}`}>{status}</span>
          {!nativeMode && (
            <select
              className="tts-select"
              value={provider}
              onChange={e => setProvider(e.target.value as TTSProvider)}
            >
              <option value="browser">Browser TTS</option>
              <option value="gemini">Gemini TTS</option>
            </select>
          )}
        </div>
      </header>

      <div className="live-grid">
        {/* Left: Gauges + Track */}
        <div className="live-left">
          <GaugeCluster frame={currentFrame} />
          {nativeMode && sessionMode === 'device_test' ? (
            <div className="panel" style={{ padding: '1rem' }}>
              <strong>Device Test Mode</strong>
              <p style={{ marginTop: '0.5rem' }}>
                Using phone camera, GPS, and IMU only. Track-specific feedforward is disabled in this lane.
              </p>
            </div>
          ) : (
            <TrackMap track={THUNDERHILL_EAST} currentFrame={currentFrame ?? undefined} />
          )}
        </div>

        {/* Center: Charts */}
        <div className="live-center">
          <TelemetryCharts frames={frames} />
        </div>

        {/* Right: Coach */}
        <div className="live-right">
          <CoachPanel
            messages={messages}
            activeCoach={activeCoach}
            onCoachChange={handleCoachChange}
            audioEnabled={audioEnabled}
            onAudioToggle={() => setAudioEnabled(!audioEnabled)}
            backendStatus={backendStatus}
          />
        </div>
      </div>
    </div>
  );
}
