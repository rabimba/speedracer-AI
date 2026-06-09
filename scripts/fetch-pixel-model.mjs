import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, rename, stat, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const modelRoot = path.join(rootDir, 'pixel-android-app', 'models');
const metadataPath = path.join(modelRoot, 'current-model.json');

const DEFAULT_NATIVE_URL = 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm';
const DEFAULT_NPU_URL = 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm';
const DEFAULT_VERSION = 'gemma-4-e2b-it';
const DEFAULT_FILENAME = 'gemma-4-E2B-it.litertlm';
const DEFAULT_NPU_FILENAME = 'gemma-4-E2B-it_Google_Tensor_G5.litertlm';
const DEFAULT_SHA256 = '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c';
const DEFAULT_NPU_SHA256 = '62faebcfd101acb841c33249530430397e031eb17d4dd3d2a71193d135705f27';

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
  const requestedVersion = env('KORU_PIXEL_MODEL_VERSION', DEFAULT_VERSION);
  const specs = [
    {
      role: 'gpu_cpu',
      sourceUrl: env('KORU_PIXEL_MODEL_URL', DEFAULT_NATIVE_URL),
      filename: env('KORU_PIXEL_MODEL_FILENAME', DEFAULT_FILENAME),
      sha256: env('KORU_PIXEL_MODEL_SHA256', DEFAULT_SHA256),
    },
    {
      role: 'npu_tensor_g5',
      sourceUrl: env('KORU_PIXEL_NPU_MODEL_URL', DEFAULT_NPU_URL),
      filename: env('KORU_PIXEL_NPU_MODEL_FILENAME', DEFAULT_NPU_FILENAME),
      sha256: env('KORU_PIXEL_NPU_MODEL_SHA256', DEFAULT_NPU_SHA256),
    },
  ];

  const prepared = [];
  for (const spec of specs) {
    if (spec.filename.endsWith('-web.task')) {
      fail([
        `Refusing to stage ${spec.filename} for the native Android backend.`,
        'That file is the web/WebGPU artifact from the Hugging Face repo.',
        `For the native Pixel backend, use ${DEFAULT_FILENAME} and ${DEFAULT_NPU_FILENAME} instead.`,
      ].join('\n'));
    }

    if (!spec.filename.endsWith('.litertlm') && !spec.filename.endsWith('.task')) {
      fail(`Unsupported model filename: ${spec.filename}. Expected .litertlm or .task`);
    }

    const versionDir = path.join(modelRoot, requestedVersion);
    const destinationPath = path.join(versionDir, spec.filename);
    await mkdir(versionDir, { recursive: true });

    if (await exists(destinationPath)) {
      if (spec.sha256) {
        const actualSha = await sha256(destinationPath);
        if (actualSha === spec.sha256) {
          console.log(`Model already present and verified: ${destinationPath}`);
        } else {
          console.log(`Existing model checksum mismatch; replacing ${destinationPath}`);
          console.log(`Expected: ${spec.sha256}`);
          console.log(`Actual:   ${actualSha}`);
          await unlink(destinationPath);
          await downloadWithCurl(spec.sourceUrl, destinationPath);
        }
      } else {
        console.log(`Model already present: ${destinationPath}`);
      }
    } else {
      console.log(`Downloading ${spec.filename} to ${destinationPath}`);
      await downloadWithCurl(spec.sourceUrl, destinationPath);
    }

    if (spec.sha256) {
      const actualSha = await sha256(destinationPath);
      if (actualSha !== spec.sha256) {
        fail(`SHA256 mismatch for ${destinationPath}\nExpected: ${spec.sha256}\nActual:   ${actualSha}`);
      }
      console.log(`Verified SHA256 for ${spec.filename}`);
    }

    prepared.push({
      role: spec.role,
      sourceUrl: spec.sourceUrl,
      version: requestedVersion,
      filename: spec.filename,
      sha256: spec.sha256,
      localPath: destinationPath,
      remoteRoot: `/data/local/tmp/koru/models/${requestedVersion}`,
      remotePath: `/data/local/tmp/koru/models/${requestedVersion}/${spec.filename}`,
    });
  }

  const primary = prepared[0];
  const metadata = {
    ...primary,
    models: prepared,
    preparedAt: new Date().toISOString(),
  };

  await writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, 'utf8');
  console.log(`Wrote ${path.relative(rootDir, metadataPath)}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
