import { useState, useCallback } from 'react';
import { useGeminiCloud } from '../hooks/useGeminiCloud';
import { parseTelemetryCaptureInput } from '../utils/sessionCapture';
import TelemetryCharts from '../components/TelemetryCharts';
import { DEFAULT_TRACK } from '../data/trackData';
import type { RecordedSessionArtifact, TelemetryFrame } from '../types';
import { BarChart3, Upload, Loader } from 'lucide-react';
import ReactMarkdown from 'react-markdown';

interface AnalysisProps {
  apiKey: string | null;
}

export default function Analysis({ apiKey }: AnalysisProps) {
  const [lap1Frames, setLap1Frames] = useState<TelemetryFrame[]>([]);
  const [lap2Frames, setLap2Frames] = useState<TelemetryFrame[]>([]);
  const [lap1Session, setLap1Session] = useState<RecordedSessionArtifact | null>(null);
  const [lap2Session, setLap2Session] = useState<RecordedSessionArtifact | null>(null);
  const [comparisonResult, setComparisonResult] = useState('');
  const { generateFeedback, status } = useGeminiCloud(apiKey);

  const handleFile = useCallback((
    frameSetter: (frames: TelemetryFrame[]) => void,
    sessionSetter: (session: RecordedSessionArtifact | null) => void,
  ) =>
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        const parsed = parseTelemetryCaptureInput(reader.result as string);
        frameSetter(parsed.frames);
        sessionSetter(parsed.session);
      };
      reader.readAsText(file);
    }, []);

  const handleCompare = useCallback(async () => {
    if (lap1Frames.length === 0 || lap2Frames.length === 0) return;

    const summarize = (frames: TelemetryFrame[], label: string) => {
      const speeds = frames.map(f => f.speed);
      const maxSpeed = Math.max(...speeds);
      const avgSpeed = speeds.reduce((a, b) => a + b, 0) / speeds.length;
      const maxBrake = Math.max(...frames.map(f => f.brake));
      const maxThrottle = Math.max(...frames.map(f => f.throttle));
      const maxGLat = Math.max(...frames.map(f => Math.abs(f.gLat)));
      const maxRpm = Math.max(...frames.map(f => f.rpm ?? 0));
      const maxCoolant = Math.max(...frames.map(f => f.coolantTempC ?? 0));
      const maxOil = Math.max(...frames.map(f => f.oilTempC ?? 0));
      const diagnosticFrames = frames.filter((frame) => frame.vehicleDiagnostics);
      const maxEngineLoad = Math.max(...frames.map(f => f.vehicleDiagnostics?.engineLoadPercent ?? 0));
      const maxMaf = Math.max(...frames.map(f => f.vehicleDiagnostics?.mafGramsPerSecond ?? 0));
      const maxIntakeTemp = Math.max(...frames.map(f => f.vehicleDiagnostics?.intakeTempC ?? 0));
      const canDiagnosticFrames = frames.filter((frame) => frame.canVehicleDiagnostics);
      const maxCanBrakePsi = Math.max(...frames.map(f => f.canVehicleDiagnostics?.brakePressurePsi ?? 0));
      const maxCanPedal = Math.max(...frames.map(f => f.canVehicleDiagnostics?.pedalPositionPercent ?? 0));
      const minCanOilPressure = Math.min(
        ...frames
          .map(f => f.canVehicleDiagnostics?.oilPressurePsi)
          .filter((value): value is number => typeof value === 'number'),
      );
      const canConnectedFrames = frames.filter((frame) => frame.sourceHealth?.canConnected).length;
      const sourceCounts = frames.reduce<Record<string, number>>((counts, frame) => {
        const source = frame.telemetrySource ?? 'unknown';
        counts[source] = (counts[source] ?? 0) + 1;
        return counts;
      }, {});
      const primarySource = Object.entries(sourceCounts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'unknown';
      const healthFrames = frames.filter((frame) => frame.sourceHealth);
      const obdStaleFrames = healthFrames.filter((frame) => frame.sourceHealth?.obdStale).length;
      const raceBoxGoodFixFrames = healthFrames.filter((frame) => frame.sourceHealth?.raceBoxFixGood).length;
      const fallbackStageCounts = healthFrames.reduce<Record<string, number>>((counts, frame) => {
        const stage = frame.sourceHealth?.fallbackStage ?? 'unspecified';
        counts[stage] = (counts[stage] ?? 0) + 1;
        return counts;
      }, {});
      const motionSourceCounts = healthFrames.reduce<Record<string, number>>((counts, frame) => {
        const source = frame.sourceHealth?.motionSource ?? 'unspecified';
        counts[source] = (counts[source] ?? 0) + 1;
        return counts;
      }, {});
      const visionFrames = frames.filter((frame) => frame.vision);
      const visionSummary = visionFrames.length > 0
        ? ` AvgMotion=${(visionFrames.reduce((sum, frame) => sum + (frame.vision?.motionEnergy ?? 0), 0) / visionFrames.length).toFixed(2)}`
          + ` AvgContrast=${(visionFrames.reduce((sum, frame) => sum + (frame.vision?.centerContrast ?? 0), 0) / visionFrames.length).toFixed(2)}`
          + ` AvgBalance=${(visionFrames.reduce((sum, frame) => sum + Math.abs(frame.vision?.lateralBalance ?? 0), 0) / visionFrames.length).toFixed(2)}`
        : '';
      const hardwareSummary = healthFrames.length > 0
        ? ` RaceBoxGoodFix=${((raceBoxGoodFixFrames / healthFrames.length) * 100).toFixed(0)}% OBDStale=${((obdStaleFrames / healthFrames.length) * 100).toFixed(0)}%`
          + ` FallbackStages=${formatCountSummary(fallbackStageCounts)} MotionSources=${formatCountSummary(motionSourceCounts)}`
        : '';
      const diagnosticsSummary = diagnosticFrames.length > 0
        ? ` DiagnosticFrames=${diagnosticFrames.length} MaxLoad=${maxEngineLoad || 'n/a'}% MaxMAF=${maxMaf || 'n/a'}g/s MaxIntakeC=${maxIntakeTemp || 'n/a'}`
        : '';
      const canSummary = canDiagnosticFrames.length > 0
        ? ` CanFrames=${canDiagnosticFrames.length} CanConnected=${canConnectedFrames}/${frames.length} MaxCanBrakePsi=${maxCanBrakePsi || 'n/a'} MaxPedal=${maxCanPedal || 'n/a'}% MinOilPsi=${Number.isFinite(minCanOilPressure) ? minCanOilPressure : 'n/a'}`
        : '';
      return `${label}: Source=${primarySource} MaxSpeed=${maxSpeed.toFixed(0)}mph AvgSpeed=${avgSpeed.toFixed(0)}mph MaxBrake=${maxBrake.toFixed(0)}% MaxThrottle=${maxThrottle.toFixed(0)}% MaxRPM=${maxRpm || 'n/a'} MaxCoolantC=${maxCoolant || 'n/a'} MaxOilC=${maxOil || 'n/a'} MaxGLat=${maxGLat.toFixed(2)}g Frames=${frames.length}${hardwareSummary}${visionSummary}${diagnosticsSummary}${canSummary}`;
    };

    const trackName = lap1Session?.trackName ?? lap2Session?.trackName ?? DEFAULT_TRACK.name;
    const context = `Compare these two laps on ${trackName}:

${summarize(lap1Frames, 'Lap A')}

${summarize(lap2Frames, 'Lap B')}

Lap A sample (every 20th frame):
${lap1Frames.filter((_, i) => i % 20 === 0).slice(0, 30).map(f =>
  `Speed:${f.speed.toFixed(0)} Thr:${f.throttle.toFixed(0)} Brk:${f.brake.toFixed(0)} GLat:${f.gLat.toFixed(2)}`
  + (f.vision ? ` Motion:${f.vision.motionEnergy.toFixed(2)} Balance:${f.vision.lateralBalance.toFixed(2)} Contrast:${f.vision.centerContrast.toFixed(2)}` : '')
).join('\n')}

Lap B sample (every 20th frame):
${lap2Frames.filter((_, i) => i % 20 === 0).slice(0, 30).map(f =>
  `Speed:${f.speed.toFixed(0)} Thr:${f.throttle.toFixed(0)} Brk:${f.brake.toFixed(0)} GLat:${f.gLat.toFixed(2)}`
  + (f.vision ? ` Motion:${f.vision.motionEnergy.toFixed(2)} Balance:${f.vision.lateralBalance.toFixed(2)} Contrast:${f.vision.centerContrast.toFixed(2)}` : '')
).join('\n')}

Compare sector by sector and corner by corner. Identify where the biggest lap-time differences come from, whether the evidence is driver technique or hardware/sensor limitation, and what the live coach should do differently next run. Keep vehicle-health diagnostics separate from lap-time evidence.

Output:
1. Biggest time-loss corner or phase.
2. Evidence from speed, brake, throttle, RPM, G, and hardware health.
3. One hot/feedforward cue that should be delivered live.
4. One cold-path post-session drill or setup focus.`;

    const result = await generateFeedback('pro', context);
    setComparisonResult(result);
  }, [lap1Frames, lap2Frames, lap1Session, lap2Session, generateFeedback]);

  return (
    <div className="page analysis">
      <header className="page-header">
        <h1><BarChart3 size={18} /> Lap Analysis</h1>
      </header>

      <div className="analysis-upload-grid">
        <div className="analysis-upload">
          <label className="upload-btn upload-lap">
            <Upload size={14} />
            <span>Lap A {lap1Frames.length > 0 ? `(${lap1Frames.length} frames)` : ''}</span>
            <input type="file" accept=".csv,.txt,.json" onChange={handleFile(setLap1Frames, setLap1Session)} hidden />
          </label>
        </div>
        <div className="analysis-upload">
          <label className="upload-btn upload-lap">
            <Upload size={14} />
            <span>Lap B {lap2Frames.length > 0 ? `(${lap2Frames.length} frames)` : ''}</span>
            <input type="file" accept=".csv,.txt,.json" onChange={handleFile(setLap2Frames, setLap2Session)} hidden />
          </label>
        </div>
        <button
          className="analyze-btn"
          onClick={handleCompare}
          disabled={lap1Frames.length === 0 || lap2Frames.length === 0 || status.state === 'loading'}
        >
          {status.state === 'loading' ? <><Loader size={14} className="spin" /> Comparing...</> : 'Compare Laps'}
        </button>
      </div>

      <div className="analysis-charts">
        {lap1Frames.length > 0 && (
          <div className="analysis-lap">
            <h3>Lap A</h3>
            <TelemetryCharts frames={lap1Frames} maxPoints={500} />
          </div>
        )}
        {lap2Frames.length > 0 && (
          <div className="analysis-lap">
            <h3>Lap B</h3>
            <TelemetryCharts frames={lap2Frames} maxPoints={500} />
          </div>
        )}
      </div>

      {comparisonResult && (
        <div className="analysis-result">
          <h3>AI Lap Comparison</h3>
          <ReactMarkdown>{comparisonResult}</ReactMarkdown>
        </div>
      )}

      {lap1Frames.length === 0 && lap2Frames.length === 0 && (
        <div className="empty-state">
          <BarChart3 size={40} />
          <h2>Compare Two Laps</h2>
          <p>Upload two CSV files to compare telemetry side-by-side with AI analysis</p>
        </div>
      )}
    </div>
  );
}

function formatCountSummary(counts: Record<string, number>): string {
  return Object.entries(counts)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([key, count]) => `${key}:${count}`)
    .join('|') || 'none';
}
