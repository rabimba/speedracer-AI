import type { RecordedSessionArtifact, TelemetryFrame, Track } from '../types';
import { haversineDistance, isValidGps } from './geoUtils';

interface CornerSliceSummary {
  cornerName: string;
  sampleCount: number;
  minSpeed: number;
  maxBrake: number;
  avgThrottle: number;
  peakLatG: number;
}

export function buildRecordedSessionAnalysisContext(
  session: RecordedSessionArtifact,
  track: Track,
): string {
  const frames = session.frames;
  const decisions = session.decisions;
  const decisionCounts = countBy(decisions.map((decision) => decision.path));
  const actionCounts = countBy(decisions.map((decision) => decision.action || 'NONE'));
  const cornerSummaries = summarizeCorners(frames, track).slice(0, 8);
  const topActions = Object.entries(actionCounts)
    .filter(([action]) => action !== 'NONE')
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([action, count]) => `${action}: ${count}`)
    .join(', ');

  const frameSummary = summarizeFrames(frames);
  const decisionsExcerpt = decisions
    .slice(0, 12)
    .map((decision) => {
      const relativeTime = ((decision.timestamp - session.startedAt) / 1000).toFixed(1);
      return `[${relativeTime}s] ${decision.path}/${decision.action ?? 'NONE'}: ${decision.text}`;
    })
    .join('\n');

  const cornerLines = cornerSummaries.length > 0
    ? cornerSummaries.map((corner) =>
      `${corner.cornerName}: minSpeed=${corner.minSpeed.toFixed(0)}mph, maxBrake=${corner.maxBrake.toFixed(0)}%, avgThrottle=${corner.avgThrottle.toFixed(0)}%, peakLatG=${corner.peakLatG.toFixed(2)}g, samples=${corner.sampleCount}`
    ).join('\n')
    : 'No reliable corner-local telemetry windows found.';

  return `Recorded coaching session summary

Track: ${session.trackName}
Mode: ${session.mode}
Coach: ${session.coachId}
Duration: ${session.summary.durationSeconds.toFixed(1)}s
Frames: ${frames.length}
Decisions: ${decisions.length}

Overall vehicle summary:
- Avg speed: ${frameSummary.avgSpeed.toFixed(1)} mph
- Max speed: ${frameSummary.maxSpeed.toFixed(1)} mph
- Max brake: ${frameSummary.maxBrake.toFixed(0)}%
- Max throttle: ${frameSummary.maxThrottle.toFixed(0)}%
- Peak lateral G: ${frameSummary.peakLatG.toFixed(2)}g
- Peak longitudinal G: ${frameSummary.peakLongG.toFixed(2)}g
- Vision frames: ${frameSummary.visionFrameCount}
- Primary telemetry source: ${frameSummary.primaryTelemetrySource}

Decision path counts:
- hot: ${decisionCounts.hot ?? 0}
- cold: ${decisionCounts.cold ?? 0}
- feedforward: ${decisionCounts.feedforward ?? 0}
- edge: ${decisionCounts.edge ?? 0}

Most common coaching actions:
${topActions || 'No action-tagged decisions recorded.'}

Corner summaries:
${cornerLines}

Sample coaching decisions:
${decisionsExcerpt || 'No decisions recorded.'}

Task:
1. Identify the biggest performance themes in this session.
2. Separate driver-technique issues from sensor/inference limitations.
3. Explain whether the hot path looked appropriate and whether the coaching timing looked believable.
4. Give the top three next-session priorities.`;
}

function summarizeFrames(frames: TelemetryFrame[]) {
  const safeLength = Math.max(frames.length, 1);
  const avgSpeed = frames.reduce((sum, frame) => sum + frame.speed, 0) / safeLength;
  const maxSpeed = Math.max(...frames.map((frame) => frame.speed), 0);
  const maxBrake = Math.max(...frames.map((frame) => frame.brake), 0);
  const maxThrottle = Math.max(...frames.map((frame) => frame.throttle), 0);
  const peakLatG = Math.max(...frames.map((frame) => Math.abs(frame.gLat)), 0);
  const peakLongG = Math.max(...frames.map((frame) => Math.abs(frame.gLong)), 0);
  const visionFrameCount = frames.filter((frame) => frame.vision).length;
  const telemetrySourceCounts = countBy(
    frames.map((frame) => frame.telemetrySource || 'unknown'),
  );
  const primaryTelemetrySource = Object.entries(telemetrySourceCounts)
    .sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'unknown';

  return {
    avgSpeed,
    maxSpeed,
    maxBrake,
    maxThrottle,
    peakLatG,
    peakLongG,
    visionFrameCount,
    primaryTelemetrySource,
  };
}

function summarizeCorners(frames: TelemetryFrame[], track: Track): CornerSliceSummary[] {
  return track.corners
    .map((corner) => {
      const samples = frames.filter((frame) => isNearCorner(frame, corner.lat, corner.lon, corner.apexDist));
      if (samples.length === 0) return null;
      const avgThrottle = samples.reduce((sum, frame) => sum + frame.throttle, 0) / samples.length;
      return {
        cornerName: corner.name,
        sampleCount: samples.length,
        minSpeed: Math.min(...samples.map((frame) => frame.speed)),
        maxBrake: Math.max(...samples.map((frame) => frame.brake)),
        avgThrottle,
        peakLatG: Math.max(...samples.map((frame) => Math.abs(frame.gLat))),
      };
    })
    .filter((summary): summary is CornerSliceSummary => summary !== null)
    .sort((a, b) => a.minSpeed - b.minSpeed);
}

function isNearCorner(frame: TelemetryFrame, lat: number, lon: number, apexDist: number): boolean {
  if (typeof frame.distance === 'number') {
    return Math.abs(frame.distance - apexDist) <= 120;
  }
  if (!isValidGps(frame.latitude, frame.longitude)) return false;
  return haversineDistance(frame.latitude, frame.longitude, lat, lon) <= 140;
}

function countBy(values: string[]): Record<string, number> {
  return values.reduce<Record<string, number>>((counts, value) => {
    counts[value] = (counts[value] ?? 0) + 1;
    return counts;
  }, {});
}
