import type { RecordedSessionArtifact } from '../types';
import { normalizeRecordedSessionArtifact } from '../utils/sessionCapture';

const STORAGE_KEY = 'koru.latestRecordedSession.v1';

export function storeLatestRecordedSession(session: RecordedSessionArtifact): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Ignore storage failures in constrained WebView contexts.
  }
}

export function loadLatestRecordedSession(): RecordedSessionArtifact | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return normalizeRecordedSessionArtifact(JSON.parse(raw));
  } catch {
    return null;
  }
}
