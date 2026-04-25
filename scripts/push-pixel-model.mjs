import { mkdir, readFile } from 'node:fs/promises';
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

async function main() {
  let metadata;
  try {
    metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
  } catch {
    fail(`Missing ${metadataPath}. Run pixel:model:stage first.`);
  }

  await run('adb', ['start-server']);
  await run('adb', ['wait-for-device']);
  await run('adb', ['shell', 'mkdir', '-p', metadata.remoteRoot]);
  await run('adb', ['push', metadata.localPath, metadata.remotePath]);
  await run('adb', ['shell', 'chmod', '644', metadata.remotePath]);
  await run('adb', ['shell', 'ls', '-lh', metadata.remotePath]);

  console.log(`Model pushed to ${metadata.remotePath}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
