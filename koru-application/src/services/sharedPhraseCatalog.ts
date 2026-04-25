import phraseCatalogData from '../data/coaching-phrases.json';
import type { CoachAction, RuntimeBackend, SkillLevel } from '../types';

type PhraseEntry = {
  default: string;
  beginner?: string;
  advanced?: string;
  personas?: Record<string, string>;
};

type PhraseCatalog = {
  version: number;
  actions: Partial<Record<CoachAction, PhraseEntry>>;
};

const phraseCatalog = phraseCatalogData as PhraseCatalog;

export function preloadSharedPhraseCatalog(): PhraseCatalog {
  return phraseCatalog;
}

export function isNativeBackend(backend: RuntimeBackend | undefined): boolean {
  return backend === 'aicore' || backend === 'litertlm' || backend === 'deterministic';
}

export function phraseIdFor(
  action: CoachAction,
  skillLevel: SkillLevel = 'INTERMEDIATE',
  coachId?: string,
): string {
  const skillKey = skillLevel.toLowerCase();
  if (coachId) return `${action}:${skillKey}:${coachId}`;
  return `${action}:${skillKey}`;
}

export function resolvePhraseText(options: {
  action?: CoachAction;
  skillLevel?: SkillLevel;
  coachId?: string;
  fallbackText?: string;
}): string {
  const { action, skillLevel = 'INTERMEDIATE', coachId, fallbackText = '' } = options;
  if (!action) return fallbackText;

  const entry = phraseCatalog.actions[action];
  if (!entry) return fallbackText;

  if (coachId && entry.personas?.[coachId]) return entry.personas[coachId];
  if (skillLevel === 'BEGINNER' && entry.beginner) return entry.beginner;
  if (skillLevel === 'ADVANCED' && entry.advanced) return entry.advanced;
  return entry.default;
}
