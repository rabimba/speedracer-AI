# Hardware Validation

This document is the field checklist for the native `RaceBox + OBDLink` source on the 2024 Subaru GR86.

## Implemented Software Path

- Android source: `racebox_obd_fusion`
- Host: native Pixel Android app, `Telemetry + Camera Fusion`
- RaceBox Mini transport: BLE GATT UART, high connection priority requested after connect
- OBDLink MX+ transport: paired Bluetooth Classic RFCOMM, standard read-only OBD-II polling
- OBDLink EX transport: USB OTG serial at 115200 8N1, selected automatically when attached and permitted
- OBD transport selector: `Auto`, `Bluetooth MX+`, or `USB EX`; `Auto` tries USB EX first, then Bluetooth MX+
- RaceBox role: primary position, speed, heading, lateral G, longitudinal G, altitude, and frame clock
- OBDLink role: RPM, OBD speed sanity check, throttle position, coolant temperature, and oil temperature when standard PIDs respond
- Cold-path diagnostics: engine load, MAF, intake temperature, timing advance, fuel trims, and selected O2 voltages when supported
- Conservative unavailable channels: `steering = null`; brake remains inferred from RaceBox longitudinal G until a verified GR86 brake channel exists

## Bench Setup

1. Pair `OBDLink MX+` in Android Bluetooth settings before opening Koru, or attach `OBDLink EX` through USB OTG.
2. Charge and power on RaceBox Mini.
3. Install the debug Android app on the Pixel.
4. Open `Live Session`.
5. Select `Telemetry + Camera Fusion`.
6. Select `RaceBox + OBDLink`.
7. Select `OBD Auto` for normal testing, or force `Bluetooth MX+` / `USB EX` for transport isolation.
8. Grant Bluetooth scan/connect permissions for RaceBox. Grant USB permission if Android prompts for OBDLink EX.
9. Start the session with audio disabled for first validation.

Expected UI/backend status:

- RaceBox progresses from scanning to notifications active.
- OBDLink progresses from USB or paired-device connect to live polling.
- Backend status reports the active OBD transport and supported Mode 01 PIDs in `sourceHealth`.
- Backend status may show `DEGRADED` until RaceBox has a good 3D fix and OBD has fresh samples.

## GR86 Ignition-On Test

Run this before any moving test:

1. Plug OBDLink MX+ or OBDLink EX into the GR86 OBD-II port.
2. Turn ignition on.
3. Start `RaceBox + OBDLink`.
4. Confirm recorded frames include:
   - `telemetrySource = racebox_obd_fusion`
   - `sourceHealth.obdConnected = true`
   - `rpm` present when the engine is running
   - `throttle` present and changing with pedal input
   - `coolantTempC` present if PID `0105` responds
   - `oilTempC` present if PID `015C` responds
   - `vehicleDiagnostics` present only for supported diagnostic PIDs
   - `sourceHealth.obdSupportedPids` contains only discovered Mode 01 PIDs

Do not proceed to a track test if OBD disconnects repeatedly while parked.

## Low-Speed Road Test

1. Keep audio disabled.
2. Drive for 10 minutes below normal road speeds.
3. Confirm:
   - RaceBox speed and OBD speed are within a plausible range of each other.
   - `sourceHealth.obdStale` returns to `false` after transient polling gaps.
   - no app crash, ANR, foreground-service stop, or Bluetooth permission error occurs.
   - saved session artifact contains `sourceHealth` entries.

## Track-Readiness Gate

Before Sonoma coaching with audio enabled:

- Complete three 20-minute dual-Bluetooth sessions without RaceBox or OBDLink disconnecting.
- Confirm frame drops are under 1% for each source.
- Confirm OBD stale intervals do not coincide with false throttle coaching.
- Confirm failed or unsupported diagnostic PIDs do not refresh stale RPM/throttle/temp values.
- Confirm RaceBox fix stays good through the whole session.
- Verify P0 audio still dispatches promptly while both Bluetooth clients are active.
- Repeat the 20-minute test once with `Bluetooth MX+` and once with `USB EX` before treating the setup as track-ready.

## Known V1 Limits

- No enhanced GR86 steering angle, true brake pressure, wheel speed, or CAN-only channels yet.
- No cross-correlation time-sync calibration yet; RaceBox is the frame clock and OBD values are treated as slower enrichment.
- OBD continuous-channel interpolation is not yet implemented; stale OBD values are ignored after the fixed freshness window.
- The app never sends DTC clear or write/configuration commands to the vehicle in this path.
- Enhanced GR86 channels remain phase two; the fusion interface is ready for a later direct-CAN adapter without changing the coaching engine.

## References

- RaceBox Mini: https://www.racebox.pro/products/racebox-mini
- RaceBox Mini/Micro protocol docs: https://www.racebox.pro/products/mini-micro-protocol-documentation?k=67c166d0bda80de96505efba
- OBDLink MX+: https://www.obdlink.com/products/obdlink-mxp/
- OBDLink/STN command manual: https://www.scantool.net/scantool/downloads/98/stn1100-frpm.pdf
- usb-serial-for-android: https://github.com/mik3y/usb-serial-for-android
- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
