# AiM CAN SLCAN simulator

This sidecar generates RH-02 PRO / CANable-style SLCAN text frames for the AiM MXP CAN2 mapping used by the Android app.

The simulator emits standard 11-bit frames shaped as:

```text
t<ID><DLC><DATA>\r
```

It covers the mapped frames `0x420-0x424` and `0x450-0x452`, with realistic AiM frame rates:

- `0x421-0x424`: 50 Hz
- `0x452`: about 20 Hz
- `0x420`, `0x450`, `0x451`: 10 Hz

Examples:

```sh
node scripts/aim-can-simulator.mjs --profile stationary --duration 5 --out /tmp/aim-stationary.slcan --json-out /tmp/aim-stationary.expected.json
node scripts/aim-can-simulator.mjs --profile brake-sweep --duration 12 --extra-ids --out /tmp/aim-brake-sweep.slcan
node scripts/aim-can-simulator.mjs --profile lap --duration 30 --real-time
```

To stream into a local pseudo-terminal for parser testing:

```sh
socat -d -d pty,raw,echo=0 pty,raw,echo=0
node scripts/aim-can-simulator.mjs --profile lap --duration 20 --port /dev/ttysXXX
```

Brake calibration defaults:

- Raw brake pressure is generated as `calibratedPsi / 0.1 + 10`.
- Decoded measured brake pressure is `raw * 0.1`.
- App-calibrated brake pressure subtracts the suspected `+10` raw-count offset, equivalent to `1.0 psi`.

Use these flags to test calibration hypotheses:

```sh
node scripts/aim-can-simulator.mjs --profile brake-sweep --brake-scale 0.1 --brake-zero-offset-raw 10
node scripts/aim-can-simulator.mjs --profile brake-sweep --brake-scale 1 --brake-zero-offset-raw 10
```

The `captured-stationary` profile replays the stationary sample shape observed from the attached CANable, including the mapped frame IDs and raw values, without trying to correct impossible/uninitialized sensor channels.
