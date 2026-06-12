import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateRun } from './validate-report.mjs';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, '..');
const dir = mkdtempSync(resolve(tmpdir(), 'sonoma-e2e-validator-'));
const scenarioPath = resolve(dir, 'scenario.json');
const goodArtifactPath = resolve(rootDir, 'fixtures/good-recorded-session.schema2.json');
const badArtifactPath = resolve(rootDir, 'fixtures/bad-recorded-session.schema1.json');
const logcatPath = resolve(dir, 'logcat.txt');
const instrumentationPath = resolve(dir, 'instrumentation.txt');
const metadataPath = resolve(dir, 'metadata.json');

const scenario = {
  expected: {
    minFrames: 1,
    requiredAudioStatuses: ['CLIP_STARTED', 'TTS_STARTED', 'TTS_QUEUED'],
    latencyBudgets: {
      hotPathP95Ms: 50,
      p0AudioDispatchMs: 100,
    },
    optionalAccelerator: 'MEDIAPIPE_LITERT',
  },
};

writeFileSync(scenarioPath, JSON.stringify(scenario));
writeFileSync(logcatPath, 'Telemetry loop started source=phone_imu_gps cameraFusion=false\n');
writeFileSync(instrumentationPath, 'OK (1 test)\n');
writeFileSync(metadataPath, JSON.stringify({ startedAtMs: 1000 }));

const good = validateRun({
  scenarioPath,
  artifactPath: goodArtifactPath,
  logcatPath,
  instrumentationPath,
  metadataPath,
});
assert(good.summary.fail === 0, `good fixture should pass, got ${good.summary.fail} failures`);
assert(good.summary.skip === 1, 'good fixture should skip optional GPU assertion');

const bad = validateRun({
  scenarioPath,
  artifactPath: badArtifactPath,
  logcatPath,
  instrumentationPath,
  metadataPath,
});
assert(bad.summary.fail > 0, 'bad fixture should fail validation');

console.log('Validator OK');
