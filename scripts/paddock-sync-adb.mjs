#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import process from 'node:process';

const args = parseArgs(process.argv.slice(2));
const adb = process.env.ADB || 'adb';
const pkg = args.package || 'com.trustableai.koru.debug';
const out = resolve(args.out || 'paddock-sync/latest-recorded-session.json');

if (args.pullSession) {
  mkdirSync(dirname(out), { recursive: true });
  const command = `latest=$(ls -t files/recorded_sessions/*.json 2>/dev/null | head -1); [ -n "$latest" ] && cat "$latest"`;
  const result = run([adb, ...deviceArgs(args.device), 'exec-out', 'run-as', pkg, 'sh', '-c', command]);
  writeFileSync(out, result.stdout);
  console.log(`Pulled latest recorded session to ${out}`);
}

if (args.pushPlan) {
  const planPath = resolve(args.pushPlan);
  if (!existsSync(planPath)) {
    throw new Error(`Learning Plan file does not exist: ${planPath}`);
  }
  const remoteDir = 'files/learning_plans';
  const remoteTmp = `${remoteDir}/incoming.learning-plan.tmp`;
  const remoteFinal = `${remoteDir}/active.learning-plan.json`;
  run([adb, ...deviceArgs(args.device), 'shell', 'run-as', pkg, 'mkdir', '-p', remoteDir]);
  run([adb, ...deviceArgs(args.device), 'push', planPath, `/data/local/tmp/trustable-learning-plan.json`]);
  run([adb, ...deviceArgs(args.device), 'shell', 'run-as', pkg, 'cp', '/data/local/tmp/trustable-learning-plan.json', remoteTmp]);
  run([adb, ...deviceArgs(args.device), 'shell', 'run-as', pkg, 'mv', remoteTmp, remoteFinal]);
  console.log(`Pushed Learning Plan atomically to ${pkg}:${remoteFinal}`);
}

if (!args.pullSession && !args.pushPlan) {
  console.log(usage());
}

function parseArgs(argv) {
  const parsed = { pullSession: false, pushPlan: '', package: '', device: '', out: '' };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => argv[++index] ?? '';
    if (arg === '--pull-session') parsed.pullSession = true;
    else if (arg === '--push-plan') parsed.pushPlan = next();
    else if (arg === '--package') parsed.package = next();
    else if (arg === '--device') parsed.device = next();
    else if (arg === '--out') parsed.out = next();
    else if (arg === '--help' || arg === '-h') {
      console.log(usage());
      process.exit(0);
    } else {
      throw new Error(`Unknown option: ${arg}`);
    }
  }
  return parsed;
}

function deviceArgs(serial) {
  return serial ? ['-s', serial] : [];
}

function run(command) {
  const result = spawnSync(command[0], command.slice(1), { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${command.join(' ')} failed\n${result.stderr || result.stdout}`);
  }
  return result;
}

function usage() {
  return `Usage:
  node scripts/paddock-sync-adb.mjs --pull-session --out paddock-sync/latest.json
  node scripts/paddock-sync-adb.mjs --push-plan learning-plan.json --package com.trustableai.koru.debug

Options:
  --device SERIAL       ADB device serial
  --package PACKAGE    Android package name, default com.trustableai.koru.debug
  --pull-session       Pull latest recorded session from app-private storage
  --push-plan PATH     Push verified Learning Plan via temp file then atomic rename`;
}
