import Papa from 'papaparse';
import type { TelemetryFrame } from '../types';

/** Parse DMS coordinates like "35°29.340126 N" to decimal */
function parseCoordinate(coord: string): number {
  const match = coord.match(/(\d+)°([\d.]+)\s*([NSEW])/);
  if (!match) return parseFloat(coord) || 0;
  const degrees = parseInt(match[1], 10);
  const minutes = parseFloat(match[2]);
  const direction = match[3];
  let decimal = degrees + minutes / 60;
  if (direction === 'S' || direction === 'W') decimal = -decimal;
  return decimal;
}

/** Get numeric value from a row for any of the candidate column names */
function getVal(row: Record<string, string>, ...keys: string[]): number {
  for (const key of keys) {
    const v = row[key];
    if (v !== undefined && v !== '') {
      const n = parseFloat(v);
      if (!isNaN(n)) return n;
    }
  }
  return 0;
}

/** Get optional numeric value from a row for nullable/diagnostic columns */
function getOptionalVal(row: Record<string, string>, ...keys: string[]): number | undefined {
  for (const key of keys) {
    const v = row[key];
    if (v !== undefined && v !== '') {
      const n = parseFloat(v);
      if (!isNaN(n)) return n;
    }
  }
  return undefined;
}

/** Get string value from a row */
function getStr(row: Record<string, string>, ...keys: string[]): string {
  for (const key of keys) {
    if (row[key] !== undefined && row[key] !== '') return row[key];
  }
  return '';
}

export function parseTelemetryCSV(csvText: string): TelemetryFrame[] {
  // Strip comment lines (TrackAddict/RaceRender CSVs start with # comments)
  const lines = csvText.split('\n');
  const cleaned = lines.filter(l => !l.startsWith('#')).join('\n');

  const result = Papa.parse<Record<string, string>>(cleaned, {
    header: true,
    skipEmptyLines: true,
    dynamicTyping: false,
  });

  if (result.errors.length > 0) {
    console.warn('CSV parse warnings:', result.errors.slice(0, 3));
  }

  const frames: TelemetryFrame[] = [];

  for (const row of result.data) {
    const latStr = getStr(row, 'Latitude', 'lat', 'GPS_Lat');
    const lonStr = getStr(row, 'Longitude', 'lon', 'lng', 'GPS_Lon');

    if (!latStr && !lonStr) continue;

    const latitude = latStr.includes('°') ? parseCoordinate(latStr) : parseFloat(latStr) || 0;
    const longitude = lonStr.includes('°') ? parseCoordinate(lonStr) : parseFloat(lonStr) || 0;

    if (latitude === 0 && longitude === 0) continue;
    const vehicleDiagnostics = {
      engineLoadPercent: getOptionalVal(row, 'Engine Load (%)', 'ENGINE_LOAD', 'engineLoadPercent'),
      mafGramsPerSecond: getOptionalVal(row, 'MAF (g/s)', 'MAF', 'mafGramsPerSecond'),
      intakeTempC: getOptionalVal(row, 'Intake Temp (C)', 'INTAKE_TEMP', 'intakeTempC'),
      timingAdvanceDegrees: getOptionalVal(row, 'Timing Advance (deg)', 'TIMING_ADVANCE', 'timingAdvanceDegrees'),
      shortFuelTrim1Percent: getOptionalVal(row, 'Short Fuel Trim 1 (%)', 'SHORT_FUEL_TRIM_1', 'shortFuelTrim1Percent'),
      longFuelTrim1Percent: getOptionalVal(row, 'Long Fuel Trim 1 (%)', 'LONG_FUEL_TRIM_1', 'longFuelTrim1Percent'),
      shortFuelTrim2Percent: getOptionalVal(row, 'Short Fuel Trim 2 (%)', 'SHORT_FUEL_TRIM_2', 'shortFuelTrim2Percent'),
      longFuelTrim2Percent: getOptionalVal(row, 'Long Fuel Trim 2 (%)', 'LONG_FUEL_TRIM_2', 'longFuelTrim2Percent'),
      o2Bank1Sensor1Volts: getOptionalVal(row, 'O2 B1S1 (V)', 'O2_B1S1', 'o2Bank1Sensor1Volts'),
      o2Bank2Sensor1Volts: getOptionalVal(row, 'O2 B2S1 (V)', 'O2_B2S1', 'o2Bank2Sensor1Volts'),
    };
    const hasDiagnostics = Object.values(vehicleDiagnostics).some(value => typeof value === 'number');

    frames.push({
      time: getVal(row, 'Time', 'Elapsed time (s)', 'time', 'Time (s)'),
      latitude,
      longitude,
      altitude: getVal(row, 'Altitude (m)', 'Height (m)', 'Altitude', 'alt', 'GPS_Alt') || undefined,
      speed: getVal(row, 'Speed (MPH)', 'Speed (km/h)', 'Speed', 'speed', 'GPS_Speed'),
      rpm: getVal(row, 'Engine Speed (RPM) *OBD', 'Engine Speed (rpm)', 'RPM', 'rpm') || undefined,
      throttle: getVal(row, 'Throttle Position (%) *OBD', 'Throttle Position (%)', 'Throttle', 'throttle'),
      brake: getVal(row, 'Brake (calculated)', 'Brake Pressure (bar)', 'Brake', 'brake'),
      steering: getVal(row, 'Steering Angle (Degrees)', 'Steering', 'steering') || undefined,
      gLat: getVal(row, 'Accel X', 'Lateral acceleration (g)', 'G_Lat', 'gLat'),
      gLong: getVal(row, 'Accel Y', 'Longitudinal acceleration (g)', 'G_Long', 'gLong'),
      gear: getVal(row, 'Gear', 'gear') || undefined,
      coolantTempC: getVal(row, 'Coolant Temp (C)', 'Coolant Temperature (C)', 'coolantTempC') || undefined,
      oilTempC: getVal(row, 'Oil Temp (C)', 'Oil Temperature (C)', 'oilTempC') || undefined,
      vehicleDiagnostics: hasDiagnostics ? vehicleDiagnostics : undefined,
    });
  }

  return frames;
}
