import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, '..');
const outputPath = resolve(rootDir, 'app/src/main/assets/scenarios/sonoma_beginner_training.v1.json');
const sampleRateHz = 10;

const points = {
  front: { lat: 38.16172, lon: -122.45435, speedMps: 36, labels: ['front_straight'] },
  t1Entry: { lat: 38.16200, lon: -122.45500, speedMps: 31, labels: ['turn_1_entry'] },
  t1Apex: { lat: 38.16180, lon: -122.45550, speedMps: 25, labels: ['turn_1_apex'] },
  t2Entry: { lat: 38.16150, lon: -122.45620, speedMps: 34, labels: ['turn_2_entry'] },
  t2Apex: { lat: 38.16120, lon: -122.45680, speedMps: 30, labels: ['turn_2_apex'] },
  t3Entry: { lat: 38.16080, lon: -122.45720, speedMps: 26, labels: ['turn_3_entry'] },
  t3Apex: { lat: 38.16050, lon: -122.45750, speedMps: 22, labels: ['turn_3_apex'] },
  t3aEntry: { lat: 38.16030, lon: -122.45780, speedMps: 22, labels: ['turn_3a_entry'] },
  t6Entry: { lat: 38.15950, lon: -122.45720, speedMps: 29, labels: ['turn_6_entry'] },
  t6Apex: { lat: 38.15910, lon: -122.45620, speedMps: 28, labels: ['turn_6_apex'] },
  t7Entry: { lat: 38.15970, lon: -122.45430, speedMps: 28, labels: ['turn_7_entry'] },
  t910Entry: { lat: 38.16000, lon: -122.45360, speedMps: 37, labels: ['turn_10_entry'] },
  t910Apex: { lat: 38.16040, lon: -122.45320, speedMps: 35, labels: ['turn_10_apex'] },
  t11Entry: { lat: 38.16120, lon: -122.45330, speedMps: 38, labels: ['turn_11_entry'] },
  t11Apex: { lat: 38.16100, lon: -122.45300, speedMps: 20, labels: ['turn_11_apex'] },
  t12Entry: { lat: 38.16130, lon: -122.45380, speedMps: 35, labels: ['turn_12_entry'] },
};

function clonePoint(point, overrides = {}) {
  return {
    ...point,
    ...overrides,
    labels: [...point.labels, ...(overrides.labels ?? [])],
  };
}

function lapPoints(lapIndex) {
  const lapLabel = lapIndex === 0 ? 'orientation_lap' : lapIndex === 1 ? 'coached_mistake_lap' : 'recovery_lap';
  const route = [
    points.front,
    points.t1Entry,
    points.t1Apex,
    points.t2Entry,
    points.t2Apex,
    points.t3Entry,
    points.t3Apex,
    points.t3aEntry,
    points.t6Entry,
    points.t6Apex,
    points.t7Entry,
    points.t910Entry,
    points.t910Apex,
    points.t11Entry,
    points.t11Apex,
    points.t12Entry,
    points.front,
  ];

  if (lapIndex === 1) {
    route[3] = clonePoint(points.t2Entry, {
      speedMps: 39,
      labels: ['early_throttle_turn_2_3'],
    });
    route[4] = clonePoint(points.t2Apex, {
      speedMps: 43,
      labels: ['early_throttle_turn_2_3'],
    });
    route[8] = clonePoint(points.t6Entry, {
      speedMps: 18,
      labels: ['over_slow_coast_turn_6'],
    });
    route[9] = clonePoint(points.t6Apex, {
      speedMps: 17,
      labels: ['over_slow_coast_turn_6'],
    });
    route[13] = clonePoint(points.t11Entry, {
      speedMps: 45,
      labels: ['late_brake_turn_11', 'expected_p0_brake'],
    });
    route[14] = clonePoint(points.t11Apex, {
      speedMps: 43,
      labels: ['late_brake_turn_11', 'expected_p0_brake'],
    });
  }

  if (lapIndex === 2) {
    route[13] = clonePoint(points.t11Entry, {
      speedMps: 30,
      labels: ['recovered_turn_11_brake_timing'],
    });
    route[14] = clonePoint(points.t11Apex, {
      speedMps: 22,
      labels: ['recovered_turn_11_brake_timing'],
    });
  }

  return route.map((point) => clonePoint(point, { labels: [lapLabel] }));
}

function metersBetween(a, b) {
  const earthRadius = 6371000;
  const dLat = toRadians(b.lat - a.lat);
  const dLon = toRadians(b.lon - a.lon);
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earthRadius * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function bearingBetween(a, b) {
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const dLon = toRadians(b.lon - a.lon);
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x =
    Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (toDegrees(Math.atan2(y, x)) + 360) % 360;
}

function toRadians(degrees) {
  return degrees * Math.PI / 180;
}

function toDegrees(radians) {
  return radians * 180 / Math.PI;
}

function interpolate(a, b, fraction) {
  return {
    lat: a.lat + (b.lat - a.lat) * fraction,
    lon: a.lon + (b.lon - a.lon) * fraction,
    speedMps: a.speedMps + (b.speedMps - a.speedMps) * fraction,
    labels: [...new Set([...a.labels, ...b.labels])],
  };
}

function generateSamples() {
  let timestamp = 0;
  const samples = [];
  for (let lap = 0; lap < 3; lap += 1) {
    const route = lapPoints(lap);
    for (let index = 0; index < route.length - 1; index += 1) {
      const start = route[index];
      const end = route[index + 1];
      const distance = metersBetween(start, end);
      const avgSpeed = Math.max(12, (start.speedMps + end.speedMps) / 2);
      const durationSec = Math.min(5.5, Math.max(1.6, distance / avgSpeed));
      const segmentSamples = Math.max(2, Math.round(durationSec * sampleRateHz));
      const bearing = bearingBetween(start, end);
      for (let sampleIndex = 0; sampleIndex < segmentSamples; sampleIndex += 1) {
        const fraction = sampleIndex / segmentSamples;
        const point = interpolate(start, end, fraction);
        samples.push({
          timestamp: Number(timestamp.toFixed(2)),
          lat: Number(point.lat.toFixed(7)),
          lon: Number(point.lon.toFixed(7)),
          speedMps: Number(point.speedMps.toFixed(2)),
          bearingDeg: Number(bearing.toFixed(1)),
          altitude: 32.0,
          labels: point.labels,
        });
        timestamp += 1 / sampleRateHz;
      }
    }
  }
  return samples;
}

const scenario = {
  schemaVersion: 1,
  name: 'sonoma_beginner_training.v1',
  trackName: 'Sonoma Raceway',
  sampleRateHz,
  description: 'Three-lap beginner field-test simulation: orientation, coached mistakes, and recovery.',
  expected: {
    minFrames: 120,
    requiredDecisions: ['FEEDFORWARD', 'HOT', 'P0_BRAKE'],
    requiredAudioStatuses: ['CLIP_STARTED', 'TTS_STARTED', 'TTS_QUEUED'],
    latencyBudgets: {
      hotPathP95Ms: 50,
      p0AudioDispatchMs: 100,
    },
    optionalAccelerator: 'MEDIAPIPE_LITERT',
  },
  trainingEvents: [
    { label: 'late_brake_turn_11', expected: 'P0 BRAKE / Brake now' },
    { label: 'over_slow_coast_turn_6', expected: 'COLD or post-session coaching context' },
    { label: 'early_throttle_turn_2_3', expected: 'COLD or EDGE enrichment context' },
    { label: 'expected_p0_brake', expected: 'P0 audio evidence linked to decision ID' },
  ],
  samples: generateSamples(),
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(scenario, null, 2)}\n`);
console.log(`Wrote ${scenario.samples.length} samples to ${outputPath}`);
