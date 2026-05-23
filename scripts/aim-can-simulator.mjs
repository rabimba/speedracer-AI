#!/usr/bin/env node
import fs from 'node:fs';
import process from 'node:process';
import { setTimeout as sleep } from 'node:timers/promises';

const FRAME_IDS = [0x420, 0x421, 0x422, 0x423, 0x424, 0x450, 0x451, 0x452];
const CAPTURED_STATIONARY = [
  't420800000000FECFCA00',
  't42180000FEFFDB000000',
  't42280C00000000000100',
  't42381E003B00FFFFE3FF',
  't42482F0077009FFFFECF',
  't45080000000000000000',
  't45180000060005000000',
  't45280000000000000000',
];

const defaults = {
  durationSeconds: 12,
  profile: 'lap',
  outPath: null,
  jsonOutPath: null,
  portPath: null,
  realTime: false,
  extraIds: false,
  brakeScalePsiPerRaw: 0.1,
  brakeZeroOffsetRaw: 10,
};

function usage() {
  return `Usage: node scripts/aim-can-simulator.mjs [options]

Options:
  --profile lap|stationary|brake-sweep|captured-stationary
  --duration <seconds>              default ${defaults.durationSeconds}
  --out <path>                      write SLCAN text dump instead of stdout
  --port <tty-path>                 stream SLCAN to an existing tty/pty path
  --real-time                       sleep between frames when streaming
  --json-out <path>                 write decoded expected samples
  --extra-ids                       include observed-but-undecoded standard IDs
  --brake-scale <psi-per-raw>       default ${defaults.brakeScalePsiPerRaw}
  --brake-zero-offset-raw <counts>  default ${defaults.brakeZeroOffsetRaw}
`;
}

function parseArgs(argv) {
  const opts = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => argv[++index] ?? '';
    if (arg === '--help' || arg === '-h') {
      process.stdout.write(usage());
      process.exit(0);
    } else if (arg === '--profile') {
      opts.profile = next();
    } else if (arg === '--duration') {
      opts.durationSeconds = Number(next());
    } else if (arg === '--out') {
      opts.outPath = next();
    } else if (arg === '--json-out') {
      opts.jsonOutPath = next();
    } else if (arg === '--port') {
      opts.portPath = next();
      opts.realTime = true;
    } else if (arg === '--real-time') {
      opts.realTime = true;
    } else if (arg === '--extra-ids') {
      opts.extraIds = true;
    } else if (arg === '--brake-scale') {
      opts.brakeScalePsiPerRaw = Number(next());
    } else if (arg === '--brake-zero-offset-raw') {
      opts.brakeZeroOffsetRaw = Number.parseInt(next(), 10);
    } else {
      throw new Error(`Unknown option: ${arg}\n${usage()}`);
    }
  }
  if (!['lap', 'stationary', 'brake-sweep', 'captured-stationary'].includes(opts.profile)) {
    throw new Error(`Unsupported profile: ${opts.profile}`);
  }
  if (!Number.isFinite(opts.durationSeconds) || opts.durationSeconds <= 0) {
    throw new Error('Duration must be a positive number.');
  }
  if (!Number.isFinite(opts.brakeScalePsiPerRaw) || opts.brakeScalePsiPerRaw <= 0) {
    throw new Error('Brake scale must be a positive number.');
  }
  if (!Number.isFinite(opts.brakeZeroOffsetRaw) || opts.brakeZeroOffsetRaw < 0) {
    throw new Error('Brake zero offset must be a non-negative raw count.');
  }
  return opts;
}

function u16(value) {
  const v = clamp(Math.round(value), 0, 0xffff);
  return [v & 0xff, (v >> 8) & 0xff];
}

function i16(value) {
  let v = Math.round(value);
  if (v < 0) v += 0x10000;
  return u16(v);
}

function i32(value) {
  let v = Math.round(value);
  if (v < 0) v += 0x100000000;
  return [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff];
}

function slcan(id, bytes) {
  const payload = bytes.map((byte) => byte.toString(16).toUpperCase().padStart(2, '0')).join('');
  return `t${id.toString(16).toUpperCase().padStart(3, '0')}8${payload}`;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function fahrenheitRaw(valueF) {
  return Math.round(valueF * 10);
}

function brakeRawFromCalibratedPsi(calibratedPsi, opts) {
  return Math.round((Math.max(0, calibratedPsi) / opts.brakeScalePsiPerRaw) + opts.brakeZeroOffsetRaw);
}

function stateAt(t, opts) {
  if (opts.profile === 'stationary' || opts.profile === 'captured-stationary') {
    return {
      speedMph: 0,
      rpm: 840,
      throttlePercent: 0,
      brakeCalibratedPsi: 0,
      brakeSwitch: false,
      steeringDeg: 0,
      lateralG: 0.01,
      inlineG: 0,
      yawRate: 0,
      pitchRate: 0,
      rollRate: 0,
      latitude: 38.16272,
      longitude: -122.455,
    };
  }

  const period = opts.profile === 'brake-sweep' ? 8 : 14;
  const phase = t % period;
  const cornerWave = Math.sin((phase / period) * Math.PI * 2);
  const braking = opts.profile === 'brake-sweep'
    ? phase >= 2 && phase < 5
    : phase >= 4.2 && phase < 6.8;
  const exiting = opts.profile === 'brake-sweep'
    ? phase >= 5.7
    : phase >= 8.2;
  const brakeRamp = braking
    ? Math.sin(((phase - (opts.profile === 'brake-sweep' ? 2 : 4.2)) / (opts.profile === 'brake-sweep' ? 3 : 2.6)) * Math.PI)
    : 0;
  const brakeCalibratedPsi = clamp(brakeRamp * (opts.profile === 'brake-sweep' ? 850 : 720), 0, 900);
  const throttlePercent = exiting
    ? clamp(18 + (phase - (opts.profile === 'brake-sweep' ? 5.7 : 8.2)) * 18, 0, 92)
    : braking
      ? 0
      : clamp(38 + Math.sin(phase * 0.8) * 18, 0, 75);
  const speedMph = opts.profile === 'brake-sweep'
    ? clamp(72 - Math.max(0, phase - 2) * 13 + Math.max(0, phase - 5.7) * 16, 0, 95)
    : clamp(78 + Math.sin((phase / period) * Math.PI * 2 - 0.8) * 28 - brakeRamp * 18, 32, 112);
  const inlineG = braking
    ? -clamp(0.15 + brakeRamp * 0.92, 0, 1.15)
    : throttlePercent > 25
      ? clamp((throttlePercent - 20) / 150, 0, 0.48)
      : 0.02;
  const lateralG = opts.profile === 'brake-sweep'
    ? 0.03 * Math.sin(t * 3)
    : clamp(cornerWave * 1.05, -1.25, 1.25);
  const steeringDeg = lateralG * 22;
  const yawRate = lateralG * 28;
  const pitchRate = inlineG * 4;
  const rollRate = lateralG * 3;
  const meters = t * speedMph * 0.44704;
  return {
    speedMph,
    rpm: clamp(900 + speedMph * 48 + throttlePercent * 18, 800, 7600),
    throttlePercent,
    brakeCalibratedPsi,
    brakeSwitch: brakeCalibratedPsi > 18,
    steeringDeg,
    lateralG,
    inlineG,
    yawRate,
    pitchRate,
    rollRate,
    latitude: 38.16272 + meters / 111_320,
    longitude: -122.455 + Math.sin(t / 9) * 0.00025,
  };
}

function frameLinesAt(t, opts) {
  if (opts.profile === 'captured-stationary') {
    return CAPTURED_STATIONARY.slice();
  }
  const s = stateAt(t, opts);
  const brakeRaw = brakeRawFromCalibratedPsi(s.brakeCalibratedPsi, opts);
  const pedalRaw = Math.round(clamp(s.throttlePercent, 0, 100) / 0.01);
  const wheelJitter = Math.sin(t * 9) * 0.25;
  const frames = [
    slcan(0x420, [
      ...u16(s.rpm),
      ...u16(s.speedMph * 10),
      ...u16(0),
      ...u16(fahrenheitRaw(190 + Math.sin(t / 60) * 6)),
    ]),
    slcan(0x421, [
      ...u16(22 * 10),
      ...i16(s.rollRate * 10),
      ...u16(fahrenheitRaw(205 + Math.sin(t / 45) * 10)),
      ...u16((45 + s.rpm / 220) * 10),
    ]),
    slcan(0x422, [
      ...u16(brakeRaw),
      ...u16(pedalRaw),
      ...u16(s.brakeSwitch ? 1 : 0),
      ...i16(s.pitchRate * 10),
    ]),
    slcan(0x423, [
      ...i16(s.steeringDeg * 10),
      ...i16(s.yawRate * 10),
      ...i16(s.lateralG * 100),
      ...i16(s.inlineG * 100),
    ]),
    slcan(0x424, [
      ...u16(8.6 * 100),
      ...u16((s.rpm > 1000 ? 13.9 : 12.5) * 10),
      ...i16((0.01 + Math.sin(t * 4) * 0.02) * 100),
      ...u16(0),
    ]),
    slcan(0x450, [
      ...u16((s.speedMph + wheelJitter) * 10),
      ...u16((s.speedMph - wheelJitter) * 10),
      ...u16((s.speedMph + wheelJitter * 0.7) * 10),
      ...u16((s.speedMph - wheelJitter * 0.5) * 10),
    ]),
    slcan(0x451, [
      ...u16(s.speedMph * 10),
      ...u16(fahrenheitRaw(68)),
      ...u16(fahrenheitRaw(198 + Math.sin(t / 50) * 8)),
      ...u16(Math.abs(s.lateralG) > 1.1 ? 1 : 0),
    ]),
    slcan(0x452, [
      ...i32(s.latitude / 0.0000001),
      ...i32(s.longitude / 0.0000001),
    ]),
  ];
  if (opts.extraIds) {
    frames.push(slcan(0x425, [...u16(brakeRaw), ...u16(pedalRaw), ...u16(0), ...u16(0)]));
    frames.push(slcan(0x453, [...i32(s.latitude / 0.0000001), ...i32(s.longitude / 0.0000001)]));
  }
  return frames;
}

function dueFrameIds(tick, opts) {
  if (opts.profile === 'captured-stationary') return FRAME_IDS;
  const ids = [0x421, 0x422, 0x423, 0x424];
  if (tick % 5 === 0) ids.push(0x420, 0x450, 0x451);
  if (tick % 3 === 0) ids.push(0x452);
  return ids;
}

function buildDump(opts) {
  const lines = [];
  const expected = [];
  const ticks = Math.ceil(opts.durationSeconds / 0.02);
  for (let tick = 0; tick < ticks; tick += 1) {
    const t = tick * 0.02;
    const allLines = frameLinesAt(t, opts);
    const byId = new Map(allLines.map((line) => [Number.parseInt(line.slice(1, 4), 16), line]));
    const ids = dueFrameIds(tick, opts);
    ids.forEach((id) => {
      const line = byId.get(id);
      if (line) lines.push(line);
    });
    if (opts.extraIds && tick % 5 === 0) {
      const s = stateAt(t, opts);
      const brakeRaw = brakeRawFromCalibratedPsi(s.brakeCalibratedPsi, opts);
      const pedalRaw = Math.round(clamp(s.throttlePercent, 0, 100) / 0.01);
      lines.push(slcan(0x425, [...u16(brakeRaw), ...u16(pedalRaw), ...u16(0), ...u16(0)]));
      lines.push(slcan(0x453, [...i32(s.latitude / 0.0000001), ...i32(s.longitude / 0.0000001)]));
    }
    if (tick % 5 === 0) {
      const s = stateAt(t, opts);
      const brakeRaw = brakeRawFromCalibratedPsi(s.brakeCalibratedPsi, opts);
      expected.push({
        timeSeconds: Number(t.toFixed(2)),
        speedMph: Number(s.speedMph.toFixed(2)),
        rpm: Math.round(s.rpm),
        throttlePercent: Number(s.throttlePercent.toFixed(2)),
        brakePressureRaw: brakeRaw,
        brakePressurePsi: Number((brakeRaw * opts.brakeScalePsiPerRaw).toFixed(2)),
        brakePressureZeroOffsetRaw: opts.brakeZeroOffsetRaw,
        brakePressureCalibratedPsi: Number(((brakeRaw - opts.brakeZeroOffsetRaw) * opts.brakeScalePsiPerRaw).toFixed(2)),
        brakeSwitch: s.brakeSwitch,
        lateralG: Number(s.lateralG.toFixed(3)),
        inlineG: Number(s.inlineG.toFixed(3)),
        latitude: Number(s.latitude.toFixed(7)),
        longitude: Number(s.longitude.toFixed(7)),
      });
    }
  }
  return { lines, expected };
}

async function writeRealTime(lines, writable) {
  for (const line of lines) {
    writable.write(`${line}\r`);
    await sleep(4);
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const { lines, expected } = buildDump(opts);
  if (opts.jsonOutPath) {
    fs.writeFileSync(opts.jsonOutPath, `${JSON.stringify({ options: opts, expected }, null, 2)}\n`);
  }
  if (opts.portPath) {
    const stream = fs.createWriteStream(opts.portPath, { flags: 'a' });
    await writeRealTime(lines, stream);
    stream.end();
    return;
  }
  const body = `${lines.join('\r')}\r`;
  if (opts.outPath) {
    fs.writeFileSync(opts.outPath, body);
  } else if (opts.realTime) {
    await writeRealTime(lines, process.stdout);
  } else {
    process.stdout.write(body);
  }
}

main().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exit(1);
});
