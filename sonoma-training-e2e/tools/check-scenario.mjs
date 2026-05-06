import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const rootDir = resolve(new URL('..', import.meta.url).pathname);
const scenarioPath = resolve(rootDir, 'app/src/main/assets/scenarios/sonoma_beginner_training.v1.json');
const scenario = JSON.parse(readFileSync(scenarioPath, 'utf8'));

const requiredLabels = [
  'orientation_lap',
  'coached_mistake_lap',
  'recovery_lap',
  'late_brake_turn_11',
  'over_slow_coast_turn_6',
  'early_throttle_turn_2_3',
  'expected_p0_brake',
];

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(scenario.schemaVersion === 1, 'scenario schemaVersion must be 1');
assert(scenario.name === 'sonoma_beginner_training.v1', 'scenario name mismatch');
assert(scenario.trackName === 'Sonoma Raceway', 'track name mismatch');
assert(scenario.sampleRateHz === 10, 'sample rate must be 10Hz');
assert(Array.isArray(scenario.samples) && scenario.samples.length > 300, 'scenario must contain generated samples');

let previousTimestamp = -Infinity;
let previousBearing = null;
const labels = new Set();
for (const [index, sample] of scenario.samples.entries()) {
  assert(Number.isFinite(sample.timestamp), `sample ${index} missing timestamp`);
  assert(sample.timestamp > previousTimestamp, `sample ${index} timestamp is not strictly increasing`);
  assert(Number.isFinite(sample.lat) && Number.isFinite(sample.lon), `sample ${index} missing location`);
  assert(Number.isFinite(sample.speedMps) && sample.speedMps >= 0, `sample ${index} invalid speed`);
  assert(Number.isFinite(sample.bearingDeg) && sample.bearingDeg >= 0 && sample.bearingDeg < 360, `sample ${index} invalid bearing`);
  assert(Number.isFinite(sample.altitude), `sample ${index} missing altitude`);
  assert(Array.isArray(sample.labels), `sample ${index} labels must be an array`);
  sample.labels.forEach((label) => labels.add(label));
  if (previousBearing != null) {
    const rawDelta = Math.abs(sample.bearingDeg - previousBearing);
    const delta = Math.min(rawDelta, 360 - rawDelta);
    assert(delta <= 210, `sample ${index} bearing jump is implausible: ${delta}`);
  }
  previousTimestamp = sample.timestamp;
  previousBearing = sample.bearingDeg;
}

for (const label of requiredLabels) {
  assert(labels.has(label), `scenario missing label ${label}`);
}

assert(scenario.expected?.latencyBudgets?.hotPathP95Ms === 50, 'HOT p95 budget must be 50ms');
assert(scenario.expected?.latencyBudgets?.p0AudioDispatchMs === 100, 'P0 audio budget must be 100ms');

console.log(`Scenario OK: ${scenario.samples.length} samples, ${labels.size} labels`);
