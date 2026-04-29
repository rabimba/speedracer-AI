import { useState, useEffect, useRef, useCallback } from 'react';
import { useTTS } from '../hooks/useTTS';
import { PRE_RACE_GOAL_OPTIONS, buildSessionGoals, recommendCoach } from '../data/preRaceGoals';
import { DEFAULT_TRACK, TRACKS, getTrackByName } from '../data/trackData';
import TelemetryCharts from '../components/TelemetryCharts';
import TrackMap from '../components/TrackMap';
import CoachAvatar from '../components/CoachAvatar';
import CoachPanel, { type CoachMessage } from '../components/CoachPanel';
import GaugeCluster from '../components/GaugeCluster';
import type {
  LiveBackendStatus,
  SessionMode,
  SessionGoalFocus,
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
import { COACHES } from '../utils/coachingKnowledge';

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
  const [trackName, setTrackName] = useState(DEFAULT_TRACK.name);
  const [selectedGoalFocuses, setSelectedGoalFocuses] = useState<SessionGoalFocus[]>([]);
  const [customGoalDescription, setCustomGoalDescription] = useState('');
  const [coachChoiceMode, setCoachChoiceMode] = useState<'auto' | 'manual'>('auto');

  const adapterRef = useRef<LiveBackendAdapter | null>(null);
  const audioEnabledRef = useRef(audioEnabled);
  const initialConfigRef = useRef({ apiKey, activeCoach, audioEnabled });
  const { speak, setProvider, provider } = useTTS(apiKey, activeCoach);
  const speakRef = useRef(speak);
  const selectedTrack = getTrackByName(trackName);
  const sessionGoals = buildSessionGoals(selectedGoalFocuses, customGoalDescription);
  const coachRecommendation = recommendCoach(sessionGoals, sessionMode);
  const recommendedCoach = COACHES[coachRecommendation.coachId];
  const sessionLocked = status !== 'disconnected';

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
      track: DEFAULT_TRACK,
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
        action: msg.action,
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
    adapterRef.current?.setTrack(selectedTrack);
  }, [selectedTrack]);

  useEffect(() => {
    adapterRef.current?.setSessionGoals(sessionGoals);
  }, [sessionGoals]);

  useEffect(() => {
    if (coachChoiceMode !== 'auto') return;
    if (activeCoach === coachRecommendation.coachId) return;
    setActiveCoach(coachRecommendation.coachId);
    adapterRef.current?.setCoach(coachRecommendation.coachId);
  }, [activeCoach, coachChoiceMode, coachRecommendation.coachId]);

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
      adapterRef.current?.setSessionGoals(sessionGoals);
      adapterRef.current?.connect(
        sseUrl.trim(),
        nativeMode ? sessionMode : 'telemetry',
        telemetrySource,
      );
    }
  }, [nativeMode, sessionGoals, sessionMode, sseUrl, status, telemetrySource]);

  const handleCoachChange = useCallback((id: string) => {
    setCoachChoiceMode('manual');
    setActiveCoach(id);
    adapterRef.current?.setCoach(id);
  }, []);

  const handleGoalToggle = useCallback((focus: SessionGoalFocus) => {
    if (sessionLocked) return;
    setSelectedGoalFocuses((previous) => {
      if (previous.includes(focus)) {
        return previous.filter((value) => value !== focus);
      }
      if (previous.length >= 3) {
        return previous;
      }
      return [...previous, focus];
    });
  }, [sessionLocked]);

  const handleUseRecommendedCoach = useCallback(() => {
    if (sessionLocked) return;
    setCoachChoiceMode('auto');
    setActiveCoach(coachRecommendation.coachId);
    adapterRef.current?.setCoach(coachRecommendation.coachId);
  }, [coachRecommendation.coachId, sessionLocked]);

  const currentFrame = frames[frames.length - 1] || null;
  const latestMessage = messages[messages.length - 1] ?? null;

  return (
    <div className={`page live-session ${nativeMode ? 'live-session-native' : ''}`}>
      <header className="page-header">
        <h1><Radio size={20} /> Live Session</h1>
        <div className="live-controls">
          <select
            className="tts-select"
            value={trackName}
            onChange={e => setTrackName(e.target.value)}
          >
            {TRACKS.map((track) => (
              <option key={track.name} value={track.name}>
                {track.name}
              </option>
            ))}
          </select>
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

      {nativeMode && (
        <section className="coach-avatar-inline-zone">
          <CoachAvatar
            activeCoach={activeCoach}
            latestMessage={latestMessage}
            status={status}
            layout="inline"
          />
        </section>
      )}

      <section className="pre-race-panel">
        <div className="pre-race-panel-header">
          <div>
            <h2>Pre-Race Setup</h2>
            <p>Pick up to 3 focus areas. The live coach will bias realtime cues toward these goals.</p>
          </div>
          <div className="pre-race-goal-count">{sessionGoals.length}/3 goals</div>
        </div>

        <div className="pre-race-goals">
          {PRE_RACE_GOAL_OPTIONS.map((goal) => {
            const selected = selectedGoalFocuses.includes(goal.focus);
            return (
              <button
                key={goal.focus}
                type="button"
                className={`pre-race-goal-chip ${selected ? 'active' : ''}`}
                onClick={() => handleGoalToggle(goal.focus)}
                disabled={sessionLocked}
              >
                <strong>{goal.label}</strong>
                <span>{goal.description}</span>
              </button>
            );
          })}
        </div>

        {selectedGoalFocuses.includes('custom') && (
          <input
            className="sse-input pre-race-custom-input"
            type="text"
            value={customGoalDescription}
            onChange={(event) => setCustomGoalDescription(event.target.value)}
            placeholder="Describe your custom focus for this session"
            disabled={sessionLocked}
          />
        )}

        <div className="pre-race-recommendation">
          <div className="pre-race-recommendation-copy">
            <span className="pre-race-recommendation-kicker">Recommended coach</span>
            <h3>{recommendedCoach?.name ?? coachRecommendation.coachId}</h3>
            <p className="pre-race-recommendation-title">{coachRecommendation.title}</p>
            <p>{coachRecommendation.rationale}</p>
            <p className="pre-race-recommendation-sample">Sample cue: “{coachRecommendation.samplePhrase}”</p>
            {coachChoiceMode === 'manual' && activeCoach !== coachRecommendation.coachId && (
              <p className="pre-race-recommendation-note">
                You are currently overriding the recommendation with {COACHES[activeCoach]?.name ?? activeCoach}.
              </p>
            )}
          </div>
          <button
            type="button"
            className="connect-btn"
            onClick={handleUseRecommendedCoach}
            disabled={sessionLocked || activeCoach === coachRecommendation.coachId}
          >
            Use Recommended Coach
          </button>
        </div>
      </section>

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
            <TrackMap track={selectedTrack} currentFrame={currentFrame ?? undefined} />
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
