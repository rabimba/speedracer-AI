import type { RecordedSessionArtifact, TelemetryFrame, Track } from '../types';
import { haversineDistance, isValidGps } from './geoUtils';

interface CornerSliceSummary {
  cornerName: string;
  sampleCount: number;
  minSpeed: number;
  maxBrake: number;
  avgThrottle: number;
  peakLatG: number;
  targetSpeed: number | undefined;
  targetDelta: number | undefined;
}

export function buildRecordedSessionAnalysisContext(
  session: RecordedSessionArtifact,
  track: Track,
): string {
const frames = session.frames;
  const decisions = session.decisions;
  const decisionCounts = countBy(decisions.map((decision) => decision.path));
  const actionCounts = countBy(decisions.map((decision) => decision.action || 'NONE'));
  const objectiveCounts = countBy(decisions.map((decision) => decision.objective || 'none'));
  const cornerSummaries = summarizeCorners(frames, track).slice(0, 8);
  const lapPaceSummary = summarizeLapPace(frames, track);
  const goalsSummary = session.sessionGoals.length > 0
    ? session.sessionGoals.map((goal) => `- ${goal.focus}: ${goal.description}`).join('\n')
    : '- No explicit pre-race goals were recorded.';
  const topActions = Object.entries(actionCounts)
    .filter(([action]) => action !== 'NONE')
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([action, count]) => `${action}: ${count}`)
    .join(', ');

  const frameSummary = summarizeFrames(frames);
  const hardwareSummary = summarizeHardware(frames);
  const vehicleDiagnosticsSummary = summarizeVehicleDiagnostics(frames);
  const canDiagnosticsSummary = summarizeCanDiagnostics(frames);
  const vboxSyncSummary = summarizeVboxSync(session);
  const decisionsExcerpt = decisions
    .slice(0, 12)
    .map((decision) => {
      const relativeTime = ((decision.timestamp - session.startedAt) / 1000).toFixed(1);
      const objective = decision.objective ? ` objective=${decision.objective}` : '';
      const cause = decision.causeId ? ` cause=${decision.causeId}` : '';
      return `[${relativeTime}s] ${decision.path}/${decision.action ?? 'NONE'}${objective}${cause}: ${decision.text}`;
    })
    .join('\n');

  const cornerLines = cornerSummaries.length > 0
    ? cornerSummaries.map((corner) =>
      `${corner.cornerName}: minSpeed=${corner.minSpeed.toFixed(0)}mph`
      + (typeof corner.targetDelta === 'number' ? `, targetDelta=${corner.targetDelta.toFixed(0)}mph` : '')
      + `, maxBrake=${corner.maxBrake.toFixed(0)}%, avgThrottle=${corner.avgThrottle.toFixed(0)}%, peakLatG=${corner.peakLatG.toFixed(2)}g, samples=${corner.sampleCount}`
    ).join('\n')
    : 'No reliable corner-local telemetry windows found.';

  return `Recorded coaching session summary

Track: ${session.trackName}
Mode: ${session.mode}
Coach: ${session.coachId}
Duration: ${session.summary.durationSeconds.toFixed(1)}s
Frames: ${frames.length} embedded preview / ${session.totalFrameCount ?? session.summary.frameCount ?? frames.length} total
Decisions: ${decisions.length}
Ended reason: ${session.endedReason ?? 'completed'}
Sidecars: ${[
    session.framesPath ? 'frames.ndjson' : null,
    session.decisionsPath ? 'decisions.ndjson' : null,
    session.audioEventsPath ? 'audio-events.ndjson' : null,
    session.canDumpPath ? 'can-slcan.txt' : null,
  ].filter(Boolean).join(', ') || 'none'}

VBOX sync:
${vboxSyncSummary}

Pre-race goals:
${goalsSummary}

Overall vehicle summary:
- Avg speed: ${frameSummary.avgSpeed.toFixed(1)} mph
- Max speed: ${frameSummary.maxSpeed.toFixed(1)} mph
- Max brake: ${frameSummary.maxBrake.toFixed(0)}%
- Max throttle: ${frameSummary.maxThrottle.toFixed(0)}%
- Max RPM: ${frameSummary.maxRpm?.toFixed(0) ?? 'n/a'}
- Max coolant: ${frameSummary.maxCoolantTempC?.toFixed(0) ?? 'n/a'} C
- Max oil temp: ${frameSummary.maxOilTempC?.toFixed(0) ?? 'n/a'} C
- Peak lateral G: ${frameSummary.peakLatG.toFixed(2)}g
- Peak longitudinal G: ${frameSummary.peakLongG.toFixed(2)}g
- Vision frames: ${frameSummary.visionFrameCount}
- Primary telemetry source: ${frameSummary.primaryTelemetrySource}

Lap and pace summary:
${lapPaceSummary}

Hardware trust summary:
${hardwareSummary}

Vehicle-health diagnostics:
${vehicleDiagnosticsSummary}

CAN/AiM diagnostics:
${canDiagnosticsSummary}

Decision path counts:
- hot: ${decisionCounts.hot ?? 0}
- cold: ${decisionCounts.cold ?? 0}
- feedforward: ${decisionCounts.feedforward ?? 0}
- edge: ${decisionCounts.edge ?? 0}

Most common coaching actions:
${topActions || 'No action-tagged decisions recorded.'}

Coaching objective counts:
${formatCountSummary(objectiveCounts, [
    'safety_recovery',
    'brake_entry',
    'brake_release',
    'line_vision',
    'rotate_wait',
    'maintenance_throttle',
    'exit_throttle',
    'smoothness',
    'none',
  ])}

Corner summaries:
${cornerLines}

Sample coaching decisions:
${decisionsExcerpt || 'No decisions recorded.'}

Task:
1. Identify the biggest lap-time losses and rank them by likely time saved.
2. Translate the evidence into live coaching changes for the next run: which hot/feedforward cues should fire, where, and why.
3. Separate driver-technique issues from sensor/inference limitations; do not overstate conclusions when RaceBox/OBD/CAN health was degraded.
4. Explain whether the hot path stayed appropriately immediate and whether cold/edge analysis should change future coaching rather than interrupt the current corner.
5. Keep vehicle-health diagnostics separate from lap-time coaching evidence; use fuel trim/MAF/O2 observations for setup review, not live interruption.
6. Give the top three next-session priorities with one concrete cue for each.`;
}

function summarizeFrames(frames: TelemetryFrame[]) {
  const safeLength = Math.max(frames.length, 1);
  const avgSpeed = frames.reduce((sum, frame) => sum + frame.speed, 0) / safeLength;
  const maxSpeed = Math.max(...frames.map((frame) => frame.speed), 0);
  const maxBrake = Math.max(...frames.map((frame) => frame.brake), 0);
  const maxThrottle = Math.max(...frames.map((frame) => frame.throttle), 0);
  const rpmValues = frames.map((frame) => frame.rpm).filter((value): value is number => typeof value === 'number');
  const coolantValues = frames.map((frame) => frame.coolantTempC).filter((value): value is number => typeof value === 'number');
  const oilValues = frames.map((frame) => frame.oilTempC).filter((value): value is number => typeof value === 'number');
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
    maxRpm: rpmValues.length ? Math.max(...rpmValues) : null,
    maxCoolantTempC: coolantValues.length ? Math.max(...coolantValues) : null,
    maxOilTempC: oilValues.length ? Math.max(...oilValues) : null,
    peakLatG,
    peakLongG,
    visionFrameCount,
    primaryTelemetrySource,
  };
}

function summarizeVboxSync(session: RecordedSessionArtifact): string {
  const sync = session.vboxSync;
  if (!sync?.enabled) {
    return '- No VBOX parity metadata recorded.';
  }
  const lines = [
    `- Source: ${sync.source}`,
    `- Status: ${sync.status}`,
    `- Trigger speed: ${sync.startSpeedMph.toFixed(2)} mph / ${sync.startSpeedKmh.toFixed(0)} km/h`,
    `- Pre-roll: ${sync.preRollSeconds.toFixed(0)}s, stop-below window: ${sync.stopBelowSeconds.toFixed(0)}s`,
  ];
  if (typeof sync.triggeredAtMs === 'number') {
    lines.push(`- Triggered at: ${sync.triggeredAtMs}`);
  }
  if (typeof sync.firstMotionFrameTimeSeconds === 'number') {
    lines.push(`- First motion frame: ${sync.firstMotionFrameTimeSeconds.toFixed(2)}s`);
  }
  if (typeof sync.lastObservedSpeedMph === 'number') {
    lines.push(`- Last observed speed: ${sync.lastObservedSpeedMph.toFixed(1)} mph`);
  }
  return lines.join('\n');
}

function summarizeCorners(frames: TelemetryFrame[], track: Track): CornerSliceSummary[] {
  return track.corners
    .map((corner) => {
      const samples = frames.filter((frame) => isNearCorner(frame, corner.lat, corner.lon, corner.apexDist, track.length));
      if (samples.length === 0) return null;
      const avgThrottle = samples.reduce((sum, frame) => sum + frame.throttle, 0) / samples.length;
      return {
        cornerName: corner.name,
        sampleCount: samples.length,
        minSpeed: Math.min(...samples.map((frame) => frame.speed)),
        maxBrake: Math.max(...samples.map((frame) => frame.brake)),
        avgThrottle,
        peakLatG: Math.max(...samples.map((frame) => Math.abs(frame.gLat))),
        targetSpeed: corner.targetSpeed,
        targetDelta: typeof corner.targetSpeed === 'number'
          ? Math.min(...samples.map((frame) => frame.speed)) - corner.targetSpeed
          : undefined,
      };
    })
    .filter((summary): summary is CornerSliceSummary => summary !== null)
    .sort((a, b) => a.minSpeed - b.minSpeed);
}

function summarizeLapPace(frames: TelemetryFrame[], track: Track): string {
  const distanceFrames = frames
    .filter((frame) => typeof frame.distance === 'number' && typeof frame.time === 'number')
    .sort((a, b) => a.time - b.time);
  if (distanceFrames.length < 2) {
    return '- No distance channel available for lap/sector estimates.';
  }

  const first = distanceFrames[0];
  const last = distanceFrames[distanceFrames.length - 1];
  const distanceCovered = (last.distance ?? 0) - (first.distance ?? 0);
  const duration = last.time - first.time;
  if (distanceCovered <= 0 || duration <= 0) {
    return '- Distance channel did not progress enough for lap/sector estimates.';
  }

  const averagePaceLapSeconds = duration * (track.length / distanceCovered);
  const estimatedCompletedLaps = Math.floor(distanceCovered / track.length);
  const sectorLines = track.sectors.map((sector) => {
    const sectorLength = sector.endDist - sector.startDist;
    const estimate = averagePaceLapSeconds * (sectorLength / track.length);
    return `  - ${sector.name}: estimated ${estimate.toFixed(1)}s at session-average pace`;
  }).join('\n');

  return [
    `- Distance covered: ${distanceCovered.toFixed(0)}m over ${duration.toFixed(1)}s`,
    `- Estimated completed laps by distance: ${estimatedCompletedLaps}`,
    `- Session-average lap pace: ${averagePaceLapSeconds.toFixed(1)}s vs track reference ${track.recordLap.toFixed(1)}s`,
    '- Approximate sector pacing:',
    sectorLines,
  ].join('\n');
}

function summarizeHardware(frames: TelemetryFrame[]): string {
  const healthFrames = frames.filter((frame) => frame.sourceHealth);
  const rpmFrames = frames.filter((frame) => typeof frame.rpm === 'number').length;
  const throttleFrames = frames.filter((frame) => frame.telemetrySource === 'racebox_obd_fusion' && typeof frame.rpm === 'number').length;
  if (healthFrames.length === 0) {
    return '- No hardware health metadata recorded. Treat sensor-source conclusions cautiously.';
  }

  const pct = (count: number) => `${((count / healthFrames.length) * 100).toFixed(0)}%`;
  const raceBoxFixGood = healthFrames.filter((frame) => frame.sourceHealth?.raceBoxFixGood === true).length;
  const obdConnected = healthFrames.filter((frame) => frame.sourceHealth?.obdConnected === true).length;
  const obdStale = healthFrames.filter((frame) => frame.sourceHealth?.obdStale === true).length;
  const canConnected = healthFrames.filter((frame) => frame.sourceHealth?.canConnected === true).length;
  const signUnverified = healthFrames.filter((frame) => frame.sourceHealth?.signUnverified === true).length;
  const fallbackStageCounts = countBy(
    healthFrames.map((frame) => frame.sourceHealth?.fallbackStage || 'unspecified'),
  );
  const motionSourceCounts = countBy(
    healthFrames.map((frame) => frame.sourceHealth?.motionSource || 'unspecified'),
  );
  const phoneFallbackFrames =
    (fallbackStageCounts.phone_obd_fusion ?? 0)
    + (fallbackStageCounts.phone_only ?? 0)
    + (fallbackStageCounts.aim_can_phone_motion ?? 0);
  const noLiveDataFrames = fallbackStageCounts.no_live_data ?? 0;
  const speedDeltas = healthFrames
    .map((frame) => frame.sourceHealth?.obdSpeedDeltaMph)
    .filter((value): value is number => typeof value === 'number');
  const avgSpeedDelta = speedDeltas.length
    ? speedDeltas.reduce((sum, value) => sum + Math.abs(value), 0) / speedDeltas.length
    : null;

  return [
    `- Hardware health frames: ${healthFrames.length}/${frames.length}`,
    `- RaceBox good fix rate: ${pct(raceBoxFixGood)}`,
    `- OBD connected rate: ${pct(obdConnected)}`,
    `- OBD stale rate: ${pct(obdStale)}`,
    `- CAN connected rate: ${pct(canConnected)}`,
    `- CAN sign-unverified frames: ${signUnverified}/${healthFrames.length}`,
    `- Fallback stages: ${formatCountSummary(fallbackStageCounts, ['aim_can_full', 'aim_can_racebox_motion', 'aim_can_phone_motion', 'full', 'racebox_only', 'phone_obd_fusion', 'phone_only', 'no_live_data', 'unspecified'])}`,
    `- Motion sources: ${formatCountSummary(motionSourceCounts, ['aim_can', 'racebox', 'phone', 'unspecified'])}`,
    `- Phone fallback frames: ${phoneFallbackFrames}/${healthFrames.length}`,
    `- No-live-data frames: ${noLiveDataFrames}/${healthFrames.length}`,
    `- OBD/RaceBox speed delta avg abs: ${avgSpeedDelta?.toFixed(1) ?? 'n/a'} mph`,
    `- RPM availability: ${rpmFrames}/${frames.length} frames`,
    `- Fused OBD enrichment frames: ${throttleFrames}/${frames.length}`,
    '- Treat telemetrySource as the transport wrapper; use fallback stages, motion sources, and CAN frame freshness for sensor trust.',
  ].join('\n');
}

function summarizeVehicleDiagnostics(frames: TelemetryFrame[]): string {
  const diagnosticsFrames = frames.filter((frame) => frame.vehicleDiagnostics);
  if (diagnosticsFrames.length === 0) {
    return '- No nested vehicle diagnostics recorded. Limit setup/health conclusions to core RPM/temp channels.';
  }

  const engineLoad = diagnosticValues(frames, (diagnostics) => diagnostics.engineLoadPercent);
  const maf = diagnosticValues(frames, (diagnostics) => diagnostics.mafGramsPerSecond);
  const intakeTemp = diagnosticValues(frames, (diagnostics) => diagnostics.intakeTempC);
  const timingAdvance = diagnosticValues(frames, (diagnostics) => diagnostics.timingAdvanceDegrees);
  const shortFuelTrim1 = diagnosticValues(frames, (diagnostics) => diagnostics.shortFuelTrim1Percent);
  const longFuelTrim1 = diagnosticValues(frames, (diagnostics) => diagnostics.longFuelTrim1Percent);
  const shortFuelTrim2 = diagnosticValues(frames, (diagnostics) => diagnostics.shortFuelTrim2Percent);
  const longFuelTrim2 = diagnosticValues(frames, (diagnostics) => diagnostics.longFuelTrim2Percent);
  const o2Bank1 = diagnosticValues(frames, (diagnostics) => diagnostics.o2Bank1Sensor1Volts);
  const o2Bank2 = diagnosticValues(frames, (diagnostics) => diagnostics.o2Bank2Sensor1Volts);

  return [
    `- Diagnostic frames: ${diagnosticsFrames.length}/${frames.length}`,
    `- Engine load max: ${maxNumber(engineLoad)?.toFixed(0) ?? 'n/a'}%`,
    `- MAF max: ${maxNumber(maf)?.toFixed(1) ?? 'n/a'} g/s`,
    `- Intake temp max: ${maxNumber(intakeTemp)?.toFixed(0) ?? 'n/a'} C`,
    `- Timing advance avg: ${avgNumber(timingAdvance)?.toFixed(1) ?? 'n/a'} deg`,
    `- Bank 1 fuel trim range: STFT ${rangeText(shortFuelTrim1)}%, LTFT ${rangeText(longFuelTrim1)}%`,
    `- Bank 2 fuel trim range: STFT ${rangeText(shortFuelTrim2)}%, LTFT ${rangeText(longFuelTrim2)}%`,
    `- O2 voltage samples: B1S1 ${o2Bank1.length}, B2S1 ${o2Bank2.length}`,
    '- Use these channels for setup/health review only; do not treat them as immediate lap-time cues.',
  ].join('\n');
}

function summarizeCanDiagnostics(frames: TelemetryFrame[]): string {
  const canFrames = frames.filter((frame) => frame.canVehicleDiagnostics || frame.sourceHealth?.canConnected);
  if (canFrames.length === 0) {
    return '- No CAN/AiM diagnostics recorded.';
  }

  const brakePressure = canDiagnosticValues(frames, (diagnostics) => diagnostics.brakePressurePsi);
  const brakePressureCalibrated = canDiagnosticValues(frames, (diagnostics) => diagnostics.brakePressureCalibratedPsi);
  const brakePressureRaw = canDiagnosticValues(frames, (diagnostics) => diagnostics.brakePressureRaw);
  const pedalPosition = canDiagnosticValues(frames, (diagnostics) => diagnostics.pedalPositionPercent);
  const pedalPositionRaw = canDiagnosticValues(frames, (diagnostics) => diagnostics.pedalPositionRaw);
  const oilPressure = canDiagnosticValues(frames, (diagnostics) => diagnostics.oilPressurePsi);
  const oilFilterTemp = canDiagnosticValues(frames, (diagnostics) => diagnostics.oilFilterTempC);
  const batteryVoltage = canDiagnosticValues(frames, (diagnostics) => diagnostics.batteryVoltage);
  const canHealthFrames = frames.filter((frame) => frame.sourceHealth?.canConnected || frame.sourceHealth?.canFrameAgesMs);
  const staleCounts: Record<string, number> = {};
  canHealthFrames.forEach((frame) => {
    Object.entries(frame.sourceHealth?.canFrameStale ?? {}).forEach(([id, stale]) => {
      if (stale) staleCounts[id] = (staleCounts[id] ?? 0) + 1;
    });
  });
  const fallbackStageCounts = countBy(
    canHealthFrames.map((frame) => frame.sourceHealth?.fallbackStage || 'unspecified'),
  );
  const rawSamples = frames
    .map((frame) => frame.sourceHealth?.rawCanSample)
    .filter((value): value is string => typeof value === 'string' && value.length > 0)
    .slice(-3);
  const rawSamplesById = latestRawSamplesById(frames);
  const observedIds = Object.keys(rawSamplesById).sort();

  return [
    `- CAN diagnostic frames: ${canFrames.length}/${frames.length}`,
    `- CAN fallback stages: ${formatCountSummary(fallbackStageCounts, ['aim_can_full', 'aim_can_racebox_motion', 'aim_can_phone_motion', 'racebox_only', 'phone_only', 'no_live_data', 'unspecified'])}`,
    `- CAN stale frame counts: ${formatCountSummary(staleCounts, ['0x420', '0x421', '0x422', '0x423', '0x424', '0x450', '0x451', '0x452'])}`,
    `- Brake pressure max: ${maxNumber(brakePressure)?.toFixed(0) ?? 'n/a'} psi`,
    `- Brake calibrated max: ${maxNumber(brakePressureCalibrated)?.toFixed(0) ?? 'n/a'} psi`,
    `- Brake raw max: ${maxNumber(brakePressureRaw)?.toFixed(0) ?? 'n/a'}`,
    `- Pedal position max: ${maxNumber(pedalPosition)?.toFixed(0) ?? 'n/a'}%`,
    `- Pedal raw max: ${maxNumber(pedalPositionRaw)?.toFixed(0) ?? 'n/a'}`,
    `- Oil pressure min/max: ${rangeText(oilPressure)} psi`,
    `- Oil filter temp max: ${maxNumber(oilFilterTemp)?.toFixed(0) ?? 'n/a'} C`,
    `- Battery voltage range: ${rangeText(batteryVoltage)} V`,
    `- Observed CAN IDs: ${observedIds.join(', ') || 'n/a'}`,
    `- Raw CAN samples: ${rawSamples.join(', ') || 'n/a'}`,
    `- Latest raw samples by ID: ${formatRawSamples(rawSamplesById)}`,
    '- Signed CAN channels are calibration-limited until lateral/inline/steering/yaw sign validation is captured.',
  ].join('\n');
}

function latestRawSamplesById(frames: TelemetryFrame[]): Record<string, string> {
  const samples: Record<string, string> = {};
  frames.forEach((frame) => {
    Object.entries(frame.canVehicleDiagnostics?.rawFrameSamples ?? {}).forEach(([id, raw]) => {
      if (typeof raw === 'string' && raw.length > 0) samples[id] = raw;
    });
    Object.entries(frame.sourceHealth?.rawCanSamplesById ?? {}).forEach(([id, raw]) => {
      if (typeof raw === 'string' && raw.length > 0) samples[id] = raw;
    });
  });
  return samples;
}

function formatRawSamples(samples: Record<string, string>): string {
  const entries = Object.entries(samples).sort(([a], [b]) => a.localeCompare(b));
  if (entries.length === 0) return 'n/a';
  return entries.slice(0, 12).map(([id, raw]) => `${id}=${raw}`).join(', ');
}

function isNearCorner(frame: TelemetryFrame, lat: number, lon: number, apexDist: number, trackLength: number): boolean {
  if (typeof frame.distance === 'number') {
    const lapDistance = lapDistanceMeters(frame.distance, trackLength);
    return Math.abs(shortestLapDelta(lapDistance, apexDist, trackLength)) <= 120;
  }
  if (!isValidGps(frame.latitude, frame.longitude)) return false;
  return haversineDistance(frame.latitude, frame.longitude, lat, lon) <= 140;
}

function lapDistanceMeters(distance: number, trackLength: number): number {
  if (!Number.isFinite(trackLength) || trackLength <= 0) return distance;
  return ((distance % trackLength) + trackLength) % trackLength;
}

function shortestLapDelta(distance: number, target: number, trackLength: number): number {
  if (!Number.isFinite(trackLength) || trackLength <= 0) return distance - target;
  const delta = distance - target;
  if (delta > trackLength / 2) return delta - trackLength;
  if (delta < -trackLength / 2) return delta + trackLength;
  return delta;
}

function diagnosticValues(
  frames: TelemetryFrame[],
  selector: (diagnostics: NonNullable<TelemetryFrame['vehicleDiagnostics']>) => number | undefined,
): number[] {
  return frames
    .map((frame) => frame.vehicleDiagnostics)
    .filter((diagnostics): diagnostics is NonNullable<TelemetryFrame['vehicleDiagnostics']> => diagnostics !== undefined)
    .map(selector)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
}

function canDiagnosticValues(
  frames: TelemetryFrame[],
  selector: (diagnostics: NonNullable<TelemetryFrame['canVehicleDiagnostics']>) => number | undefined,
): number[] {
  return frames
    .map((frame) => frame.canVehicleDiagnostics)
    .filter((diagnostics): diagnostics is NonNullable<TelemetryFrame['canVehicleDiagnostics']> => diagnostics !== undefined)
    .map(selector)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
}

function maxNumber(values: number[]): number | null {
  return values.length > 0 ? Math.max(...values) : null;
}

function minNumber(values: number[]): number | null {
  return values.length > 0 ? Math.min(...values) : null;
}

function avgNumber(values: number[]): number | null {
  return values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
}

function rangeText(values: number[]): string {
  const min = minNumber(values);
  const max = maxNumber(values);
  return min === null || max === null ? 'n/a' : `${min.toFixed(1)}..${max.toFixed(1)}`;
}

function countBy(values: string[]): Record<string, number> {
  return values.reduce<Record<string, number>>((counts, value) => {
    counts[value] = (counts[value] ?? 0) + 1;
    return counts;
  }, {});
}

function formatCountSummary(counts: Record<string, number>, preferredOrder: string[]): string {
  const orderedKeys = [
    ...preferredOrder.filter((key) => counts[key] !== undefined),
    ...Object.keys(counts)
      .filter((key) => !preferredOrder.includes(key))
      .sort(),
  ];
  return orderedKeys.length > 0
    ? orderedKeys.map((key) => `${key}=${counts[key]}`).join(', ')
    : 'none';
}
