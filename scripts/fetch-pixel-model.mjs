import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const modelRoot = path.join(rootDir, 'pixel-android-app', 'models');
const metadataPath = path.join(modelRoot, 'current-model.json');

const DEFAULT_NATIVE_URL = 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm';
const DEFAULT_VERSION = 'gemma-4-e2b-it';
const DEFAULT_FILENAME = 'gemma-4-E2B-it.litertlm';
const DEFAULT_SHA256 = 'ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42';

function env(name, fallback) {
  return process.env[name] || fallback;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

function parseFilename(url) {
  return path.basename(new URL(url).pathname);
}

function inferVersion(filename) {
  return filename
    .replace(/\.(litertlm|task)$/i, '')
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
}

async function exists(filePath) {
  try {
    await stat(filePath);
    return true;
  } catch {
    return false;
  }
}

async function sha256(filePath) {
  const hash = createHash('sha256');
  const stream = createReadStream(filePath);
  for await (const chunk of stream) {
    hash.update(chunk);
  }
  return hash.digest('hex');
}

async function downloadWithCurl(url, outPath) {
  await mkdir(path.dirname(outPath), { recursive: true });
  const partialPath = `${outPath}.partial`;

  await new Promise((resolve, reject) => {
    const child = spawn('curl', [
      '-L',
      '--fail',
      '--continue-at', '-',
      '--output', partialPath,
      url,
    ], { stdio: 'inherit' });
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`curl exited with code ${code}`));
    });
    child.on('error', reject);
  });

  await rename(partialPath, outPath);
}

async function main() {
  const requestedUrl = env('KORU_PIXEL_MODEL_URL', DEFAULT_NATIVE_URL);
  const requestedFilename = env('KORU_PIXEL_MODEL_FILENAME', parseFilename(requestedUrl) || DEFAULT_FILENAME);
  const requestedVersion = env('KORU_PIXEL_MODEL_VERSION', inferVersion(requestedFilename) || DEFAULT_VERSION);
  const requestedSha = env('KORU_PIXEL_MODEL_SHA256', DEFAULT_SHA256);

  if (requestedFilename.endsWith('-web.task')) {
    fail([
      `Refusing to stage ${requestedFilename} for the native Android backend.`,
      'That file is the web/WebGPU artifact from the Hugging Face repo.',
      `For the native Pixel backend, use ${DEFAULT_FILENAME} instead.`,
      `Suggested URL: ${DEFAULT_NATIVE_URL}`,
    ].join('\n'));
  }

  if (!requestedFilename.endsWith('.litertlm') && !requestedFilename.endsWith('.task')) {
    fail(`Unsupported model filename: ${requestedFilename}. Expected .litertlm or .task`);
  }

  const versionDir = path.join(modelRoot, requestedVersion);
  const destinationPath = path.join(versionDir, requestedFilename);
  await mkdir(versionDir, { recursive: true });

  if (!(await exists(destinationPath))) {
    console.log(`Downloading ${requestedFilename} to ${destinationPath}`);
    await downloadWithCurl(requestedUrl, destinationPath);
  } else {
    console.log(`Model already present: ${destinationPath}`);
  }

  if (requestedSha) {
    const actualSha = await sha256(destinationPath);
    if (actualSha !== requestedSha) {
      fail(`SHA256 mismatch for ${destinationPath}\nExpected: ${requestedSha}\nActual:   ${actualSha}`);
    }
    console.log(`Verified SHA256 for ${requestedFilename}`);
  }

  const metadata = {
    sourceUrl: requestedUrl,
    version: requestedVersion,
    filename: requestedFilename,
    sha256: requestedSha,
    localPath: destinationPath,
    remoteRoot: `/data/local/tmp/koru/models/${requestedVersion}`,
    remotePath: `/data/local/tmp/koru/models/${requestedVersion}/${requestedFilename}`,
    preparedAt: new Date().toISOString(),
  };

  await writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, 'utf8');
  console.log(`Wrote ${path.relative(rootDir, metadataPath)}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
