import type { RecordedSessionArtifact, TelemetryFrame } from '../types';
import { parseTelemetryCSV } from './telemetryParser';

function isTelemetryFrame(value: unknown): value is TelemetryFrame {
  if (!value || typeof value !== 'object') return false;
  const frame = value as Partial<TelemetryFrame>;
  return typeof frame.time === 'number'
    && typeof frame.latitude === 'number'
    && typeof frame.longitude === 'number';
}

export function normalizeRecordedSessionArtifact(value: unknown): RecordedSessionArtifact | null {
  if (!value || typeof value !== 'object') return null;
  const session = value as Partial<RecordedSessionArtifact>;
  if (typeof session.id !== 'string' || !Array.isArray(session.frames) || !Array.isArray(session.decisions)) {
    return null;
  }
  if (!session.frames.every(isTelemetryFrame)) {
    return null;
  }
  return session as RecordedSessionArtifact;
}

export function parseTelemetryCaptureInput(
  rawText: string,
): { frames: TelemetryFrame[]; session: RecordedSessionArtifact | null } {
  const trimmed = rawText.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      const session = normalizeRecordedSessionArtifact(parsed);
      if (session) {
        return { frames: session.frames, session };
      }

      if (
        parsed
        && typeof parsed === 'object'
        && Array.isArray((parsed as { frames?: unknown[] }).frames)
        && (parsed as { frames: unknown[] }).frames.every(isTelemetryFrame)
      ) {
        return { frames: (parsed as { frames: TelemetryFrame[] }).frames, session: null };
      }

      if (Array.isArray(parsed) && parsed.every(isTelemetryFrame)) {
        return { frames: parsed, session: null };
      }
    } catch {
      // Fall through to CSV parsing.
    }
  }

  return { frames: parseTelemetryCSV(rawText), session: null };
}
