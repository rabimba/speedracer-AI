import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const metadataPath = path.join(rootDir, 'pixel-android-app', 'models', 'current-model.json');

function fail(message) {
  console.error(message);
  process.exit(1);
}

async function run(command, args) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit' });
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} exited with code ${code}`));
    });
    child.on('error', reject);
  });
}

async function capture(command, args) {
  return await new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('exit', (code) => {
      if (code === 0) resolve(stdout.trim());
      else reject(new Error(`${command} exited with code ${code}: ${stderr.trim()}`));
    });
    child.on('error', reject);
  });
}

async function main() {
  let metadata;
  try {
    metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
  } catch {
    fail(`Missing ${metadataPath}. Run pixel:model:stage first.`);
  }

  const adb = process.env.ADB || 'adb';
  const models = Array.isArray(metadata.models) ? metadata.models : [metadata];

  await run(adb, ['start-server']);
  await run(adb, ['wait-for-device']);
  for (const model of models) {
    await run(adb, ['shell', 'mkdir', '-p', model.remoteRoot]);
    const localSize = (await stat(model.localPath)).size;
    const remoteSize = await capture(adb, ['shell', 'stat', '-c', '%s', model.remotePath])
      .then((value) => Number(value))
      .catch(() => 0);
    if (remoteSize === localSize) {
      console.log(`Model already pushed: ${model.remotePath}`);
      await run(adb, ['shell', 'ls', '-lh', model.remotePath]);
      continue;
    }
    await run(adb, ['push', model.localPath, model.remotePath]);
    await run(adb, ['shell', 'chmod', '644', model.remotePath]);
    await run(adb, ['shell', 'ls', '-lh', model.remotePath]);
    console.log(`Model pushed to ${model.remotePath}`);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
