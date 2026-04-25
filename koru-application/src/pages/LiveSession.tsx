import { useState, useEffect, useRef, useCallback } from 'react';
import { useTTS } from '../hooks/useTTS';
import { THUNDERHILL_EAST } from '../data/trackData';
import TelemetryCharts from '../components/TelemetryCharts';
import TrackMap from '../components/TrackMap';
import CoachPanel, { type CoachMessage } from '../components/CoachPanel';
import GaugeCluster from '../components/GaugeCluster';
import type {
  LiveBackendStatus,
  TelemetryFrame,
  SSEConnectionStatus,
  TTSProvider,
} from '../types';
import { Radio, Unplug } from 'lucide-react';
import { LiveBackendAdapter } from '../services/liveBackendAdapter';
import { isNativeBackend } from '../services/sharedPhraseCatalog';
import { hasAndroidBridge } from '../services/androidBridge';

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
  const [nativeMode, setNativeMode] = useState(hasAndroidBridge());

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

  const handleConnect = useCallback(() => {
    if (status === 'connected') {
      adapterRef.current?.disconnect();
    } else {
      adapterRef.current?.connect(sseUrl.trim());
    }
  }, [status, sseUrl]);

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
          <input
            type="text"
            placeholder={nativeMode ? 'Optional mock telemetry override' : 'SSE URL or .txt file path'}
            value={sseUrl}
            onChange={e => setSseUrl(e.target.value)}
            className="sse-input"
          />
          <button
            className={`connect-btn ${status === 'connected' ? 'connected' : ''}`}
            onClick={handleConnect}
          >
            {status === 'connected' ? <><Unplug size={14} /> Disconnect</> : <><Radio size={14} /> Connect</>}
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
          <TrackMap track={THUNDERHILL_EAST} currentFrame={currentFrame ?? undefined} />
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
