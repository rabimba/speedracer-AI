import { copyFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const source = path.join(rootDir, 'shared', 'coaching-phrases.json');
const destinations = [
  path.join(rootDir, 'koru-application', 'src', 'data', 'coaching-phrases.json'),
  path.join(rootDir, 'pixel-android-app', 'app', 'src', 'main', 'assets', 'coaching-phrases.json'),
];

for (const destination of destinations) {
  await mkdir(path.dirname(destination), { recursive: true });
  await copyFile(source, destination);
  console.log(`Synced ${path.relative(rootDir, source)} -> ${path.relative(rootDir, destination)}`);
}
