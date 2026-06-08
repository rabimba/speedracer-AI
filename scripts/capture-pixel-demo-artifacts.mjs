#!/usr/bin/env node
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const outputDir = resolve(repoRoot, process.env.KORU_ARTIFACT_DIR ?? 'submission-artifacts/media');
const appId = process.env.KORU_ANDROID_APP_ID ?? 'com.trustableai.koru.debug';
const apkPath = resolve(repoRoot, 'pixel-android-app/app/build/outputs/apk/debug/app-debug.apk');
const remoteDir = '/sdcard/Download';

function findAdb() {
  const candidates = [
    process.env.ADB,
    process.env.ANDROID_HOME ? join(process.env.ANDROID_HOME, 'platform-tools/adb') : null,
    process.env.ANDROID_SDK_ROOT ? join(process.env.ANDROID_SDK_ROOT, 'platform-tools/adb') : null,
    join(process.env.HOME ?? '', 'Library/Android/sdk/platform-tools/adb'),
  ].filter(Boolean);

  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  return 'adb';
}

const adb = findAdb();

function adbText(args, options = {}) {
  try {
    return execFileSync(adb, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (error) {
    if (options.allowFailure) return error.stdout?.toString() ?? '';
    throw error;
  }
}

function adbInherit(args, options = {}) {
  try {
    execFileSync(adb, args, { stdio: 'inherit' });
  } catch (error) {
    if (!options.allowFailure) throw error;
  }
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function requireUnlockedDevice() {
  const devices = adbText(['devices', '-l']);
  if (!/\bdevice\b.*\bmodel:/.test(devices)) {
    throw new Error(`No online Android device found.\n${devices}`);
  }
  adbInherit(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], { allowFailure: true });
  const trust = adbText(['shell', 'dumpsys', 'trust'], { allowFailure: true });
  const policy = adbText(['shell', 'dumpsys', 'window', 'policy'], { allowFailure: true });
  const locked = trust.includes('deviceLocked=1') || (policy.includes('showing=true') && policy.includes('secure=true'));
  if (locked) {
    throw new Error('Pixel is connected but still secure-locked. Unlock it, leave it on the home screen, then rerun this command.');
  }
}

function screenSize() {
  const output = adbText(['shell', 'wm', 'size']);
  const match = output.match(/(\d+)x(\d+)/);
  if (!match) return { width: 1080, height: 2424 };
  return { width: Number(match[1]), height: Number(match[2]) };
}

function tap(x, y) {
  adbInherit(['shell', 'input', 'tap', String(Math.round(x)), String(Math.round(y))], { allowFailure: true });
}

function capturePng(name) {
  const remotePath = `${remoteDir}/${name}`;
  const localPath = join(outputDir, name);
  adbInherit(['shell', 'screencap', '-p', remotePath]);
  adbInherit(['pull', remotePath, localPath]);
  console.log(`captured ${localPath}`);
}

async function recordInteractionVideo(width, height) {
  const remoteVideo = `${remoteDir}/koru-demo-interaction.mp4`;
  const localVideo = join(outputDir, 'koru-demo-interaction.mp4');
  adbInherit(['shell', 'rm', '-f', remoteVideo], { allowFailure: true });

  const recorder = spawn(adb, ['shell', 'screenrecord', '--time-limit', '14', remoteVideo], { stdio: 'inherit' });
  await sleep(1500);
  tap(width / 2, height - 96);
  await sleep(1300);
  tap(width * 5 / 6, height - 96);
  await sleep(1600);
  tap(width / 6, height - 96);
  await sleep(1200);
  tap(width / 2, height * 0.72);
  await new Promise((resolveRecord) => recorder.on('close', resolveRecord));
  adbInherit(['pull', remoteVideo, localVideo]);
  console.log(`recorded ${localVideo}`);
}

async function main() {
  mkdirSync(outputDir, { recursive: true });
  requireUnlockedDevice();

  if (existsSync(apkPath) && process.env.KORU_SKIP_INSTALL !== '1') {
    adbInherit(['install', '-r', apkPath]);
  }

  for (const permission of [
    'android.permission.CAMERA',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.POST_NOTIFICATIONS',
  ]) {
    adbInherit(['shell', 'pm', 'grant', appId, permission], { allowFailure: true });
  }

  adbInherit(['shell', 'monkey', '-p', appId, '-c', 'android.intent.category.LAUNCHER', '1']);
  await sleep(3000);

  const { width, height } = screenSize();
  capturePng('pixel-setup-screen.png');
  tap(width * 5 / 6, height - 96);
  await sleep(1200);
  capturePng('pixel-diagnostics-screen.png');
  tap(width / 2, height - 96);
  await sleep(1200);
  capturePng('pixel-paddock-screen.png');
  await recordInteractionVideo(width, height);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
