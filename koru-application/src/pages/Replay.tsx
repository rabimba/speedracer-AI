import { useState, useRef, useCallback, useEffect, startTransition } from 'react';
import { CoachingService } from '../services/coachingService';
import { useGeminiCloud } from '../hooks/useGeminiCloud';
import { useTTS } from '../hooks/useTTS';
import { usePredictiveCoaching } from '../hooks/usePredictiveCoaching';
import { loadLatestRecordedSession } from '../services/recordedSessionStore';
import { DEFAULT_TRACK, getTrackByName } from '../data/trackData';
import TelemetryCharts from '../components/TelemetryCharts';
import PlaybackControls from '../components/PlaybackControls';
import GaugeCluster from '../components/GaugeCluster';
import TrackMap from '../components/TrackMap';
import CoachPanel, { type CoachMessage } from '../components/CoachPanel';
import type { RecordedSessionArtifact, TelemetryFrame, TTSProvider } from '../types';
import { Upload } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import { parseTelemetryCaptureInput } from '../utils/sessionCapture';
import { buildRecordedSessionAnalysisContext } from '../utils/sessionAnalysis';

interface ReplayProps {
  apiKey: string | null;
}

export default function Replay({ apiKey }: ReplayProps) {
  const [frames, setFrames] = useState<TelemetryFrame[]>([]);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [messages, setMessages] = useState<CoachMessage[]>([]);
  const [activeCoach, setActiveCoach] = useState('superaj');
  const [audioEnabled, setAudioEnabled] = useState(true);
  const [analysisResult, setAnalysisResult] = useState('');
  const [recordedSession, setRecordedSession] = useState<RecordedSessionArtifact | null>(null);
  const activeTrack = getTrackByName(recordedSession?.trackName ?? DEFAULT_TRACK.name);

  const { generateFeedback, status: cloudStatus } = useGeminiCloud(apiKey);
  const { speak, setProvider, provider } = useTTS(apiKey, activeCoach);
  const { checkLookahead } = usePredictiveCoaching(activeTrack);

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const coachRef = useRef(new CoachingService());

  // Wire up coaching service
  useEffect(() => {
    if (apiKey) coachRef.current.setApiKey(apiKey);
  }, [apiKey]);

  useEffect(() => {
    coachRef.current.setTrack(activeTrack);
  }, [activeTrack]);

  useEffect(() => {
    coachRef.current.setCoach(activeCoach);
  }, [activeCoach]);

  const audioEnabledRef = useRef(audioEnabled);
  useEffect(() => { audioEnabledRef.current = audioEnabled; }, [audioEnabled]);

  const speakRef = useRef(speak);
  useEffect(() => { speakRef.current = speak; }, [speak]);

  useEffect(() => {
    const unsub = coachRef.current.onCoaching(msg => {
      const coachMsg: CoachMessage = {
        id: `coach-${Date.now()}-${Math.random()}`,
        path: msg.path,
        text: msg.text,
        timestamp: Date.now(),
        backend: 'browser',
      };
      setMessages(prev => [...prev, coachMsg]);

      // Speak coaching messages
      if (audioEnabledRef.current && msg.text) {
        speakRef.current(msg.text);
      }
    });
    return unsub;
  }, []);

  // File upload
  const handleFileUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = () => {
      const text = reader.result as string;
      const parsed = parseTelemetryCaptureInput(text);
      setFrames(parsed.frames);
      setRecordedSession(parsed.session);
      setCurrentIdx(0);
      setIsPlaying(false);
      setMessages([]);
      setAnalysisResult('');
    };
    reader.readAsText(file);
  }, []);

  const handleLoadLatestCapture = useCallback(() => {
    const latest = loadLatestRecordedSession();
    if (!latest) return;
    setFrames(latest.frames);
    setRecordedSession(latest);
    setCurrentIdx(0);
    setIsPlaying(false);
    setMessages([]);
    setAnalysisResult('');
  }, []);

  // Playback loop
  useEffect(() => {
    if (isPlaying && frames.length > 0) {
      const intervalMs = Math.max(10, 100 / speed);
      timerRef.current = setInterval(() => {
        setCurrentIdx(prev => {
          if (prev >= frames.length - 1) {
            setIsPlaying(false);
            return prev;
          }
          return prev + 1;
        });
      }, intervalMs);
    } else {
      if (timerRef.current) clearInterval(timerRef.current);
    }
    return () => { if (timerRef.current) clearInterval(timerRef.current); };
  }, [isPlaying, frames.length, speed]);

  // Run coaching engine + predictive coaching on frame change
  useEffect(() => {
    if (frames.length === 0) return;
    const frame = frames[currentIdx];
    if (!frame) return;

    if (recordedSession?.decisions.length) {
      const cutoffMs = recordedSession.startedAt + frame.time * 1000;
      setMessages(
        recordedSession.decisions
          .filter((decision) => decision.timestamp <= cutoffMs)
          .map((decision) => ({
            id: `${decision.timestamp}-${decision.path}-${decision.text}`,
            path: decision.path,
            text: decision.text,
            timestamp: decision.timestamp,
            backend: decision.backend,
            priority: decision.priority,
            confidence: decision.confidence,
          })),
      );
      return;
    }

    // Process through hot/cold/feedforward coaching engine
    coachRef.current.processFrame(frame);

    // Predictive coaching lookahead
    const zone = checkLookahead(frame);
    if (zone) {
      const msg: CoachMessage = {
        id: `pred-${Date.now()}`,
        path: 'feedforward',
        text: `Ahead: ${zone.cornerName} — ${zone.advice} (lost ${Math.abs(zone.speedDelta).toFixed(0)} mph last lap)`,
        timestamp: Date.now(),
        backend: 'browser',
      };
      startTransition(() => {
        setMessages(prev => [...prev, msg]);
      });
      if (audioEnabled) speak(msg.text);
    }
  }, [audioEnabled, checkLookahead, currentIdx, frames, recordedSession, speak]);

  // AI Analysis
  const handleAnalyze = useCallback(async () => {
    if (frames.length === 0) return;
    const result = recordedSession
      ? await generateFeedback('pro', buildRecordedSessionAnalysisContext(recordedSession, activeTrack))
      : await generateFeedback(
        'flash',
        frames
          .slice(Math.max(0, currentIdx - 100), Math.min(frames.length, currentIdx + 101))
          .map((frame, index) =>
            `[${index}] Track:${activeTrack.name} Speed:${frame.speed.toFixed(0)} Thr:${frame.throttle.toFixed(0)} Brk:${frame.brake.toFixed(0)} GLat:${frame.gLat.toFixed(2)} GLong:${frame.gLong.toFixed(2)}`
            + (frame.vision
              ? ` VisionMotion:${frame.vision.motionEnergy.toFixed(2)} VisionBalance:${frame.vision.lateralBalance.toFixed(2)} VisionContrast:${frame.vision.centerContrast.toFixed(2)}`
              : '')
          )
          .join('\n'),
      );
    setAnalysisResult(result);
    if (result) {
      const msg: CoachMessage = {
        id: `cloud-${Date.now()}`,
        path: 'cold',
        text: result,
        timestamp: Date.now(),
        backend: 'browser',
      };
      setMessages(prev => [...prev, msg]);
    }
  }, [frames, currentIdx, generateFeedback]);

  const currentFrame = frames[currentIdx] || null;

  return (
    <div className="page replay">
      <header className="page-header">
        <h1>Replay</h1>
        <div className="replay-controls">
          <label className="upload-btn">
            <Upload size={14} />
            <span>Upload CSV / JSON</span>
            <input type="file" accept=".csv,.txt,.json" onChange={handleFileUpload} hidden />
          </label>
          <button
            className="upload-btn"
            onClick={handleLoadLatestCapture}
            disabled={!loadLatestRecordedSession()}
          >
            Load Latest Capture
          </button>
          <button
            className="analyze-btn"
            onClick={handleAnalyze}
            disabled={frames.length === 0 || cloudStatus.state === 'loading'}
          >
            {cloudStatus.state === 'loading'
              ? 'Analyzing...'
              : recordedSession
                ? 'AI Session Analysis'
                : 'AI Window Analysis'}
          </button>
          <select
            className="tts-select"
            value={provider}
            onChange={e => setProvider(e.target.value as TTSProvider)}
          >
            <option value="browser">Browser TTS</option>
            <option value="gemini">Gemini TTS</option>
          </select>
        </div>
      </header>

      {frames.length === 0 ? (
        <div className="empty-state">
          <Upload size={40} />
          <h2>Upload Session Data</h2>
          <p>Drop a CSV or recorded JSON session to begin replay analysis</p>
        </div>
      ) : (
        <>
          <PlaybackControls
            isPlaying={isPlaying}
            onPlayPause={() => setIsPlaying(!isPlaying)}
            currentFrame={currentIdx}
            totalFrames={frames.length}
            onSeek={setCurrentIdx}
            speed={speed}
            onSpeedChange={setSpeed}
          />

          <div className="replay-grid">
            <div className="replay-left">
              <GaugeCluster frame={currentFrame} />
              <TrackMap track={activeTrack} currentFrame={currentFrame ?? undefined} />
            </div>
            <div className="replay-center">
              <TelemetryCharts frames={frames.slice(Math.max(0, currentIdx - 100), currentIdx + 1)} />
              {analysisResult && (
                <div className="analysis-card">
                  <h3>AI Analysis</h3>
                  <ReactMarkdown>{analysisResult}</ReactMarkdown>
                </div>
              )}
            </div>
            <div className="replay-right">
              <CoachPanel
                messages={messages}
                activeCoach={activeCoach}
                onCoachChange={setActiveCoach}
                audioEnabled={audioEnabled}
                onAudioToggle={() => setAudioEnabled(!audioEnabled)}
              />
            </div>
          </div>
        </>
      )}
    </div>
  );
}
