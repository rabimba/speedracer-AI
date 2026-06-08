import { ref } from 'vue';
import { buildDemoSession, type PaddockSession } from '../lib/paddockDemo';

const STORAGE_KEY = 'trustable.paddock.sessions.v1';

export function useRecordedSessions() {
  const sessions = ref<PaddockSession[]>(loadSessions());
  if (sessions.value.length === 0) {
    sessions.value = [buildDemoSession()];
    persist();
  }

  function addSession(session: PaddockSession) {
    sessions.value = [session, ...sessions.value.filter((candidate) => candidate.id !== session.id)].slice(0, 12);
    persist();
  }

  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value));
  }

  return { sessions, addSession };
}

function loadSessions(): PaddockSession[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) as PaddockSession[] : [];
  } catch {
    return [];
  }
}
