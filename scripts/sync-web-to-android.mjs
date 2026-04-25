import { cp, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const source = path.join(rootDir, 'koru-application', 'dist');
const destination = path.join(rootDir, 'pixel-android-app', 'app', 'src', 'main', 'assets', 'web');

await mkdir(destination, { recursive: true });
await cp(source, destination, { recursive: true, force: true });
console.log(`Copied ${path.relative(rootDir, source)} -> ${path.relative(rootDir, destination)}`);
