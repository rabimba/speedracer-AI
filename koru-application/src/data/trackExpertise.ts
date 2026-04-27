import type { Corner, SkillLevel, Track } from '../types';
import {
  TROD_CORNER_ADVICE,
  TROD_INSIGHTS,
  TROD_SESSION_PROGRESSION,
  getInsightsForCorner,
  type SonomaCornerId,
} from './trodCoachingData';
import { SONOMA_RACEWAY } from './trackData';

type SessionPhase = 1 | 2 | 3;

const SONOMA_TRACK_PRIMER = [
  'Sonoma Raceway full circuit: 2.505 miles with 160 feet of elevation change and three timing sectors.',
  'Sector 1 runs from start-finish through Turns 2, 3, and 3A. Rhythm, late apex timing, and vision over the hill matter most.',
  'Sector 2 covers the technical middle of the lap and the Carousel. Distance management, maintenance throttle, and patience dominate here.',
  'Sector 3 rewards setup more than aggression. Sacrifice Turn 9 to straighten the car for the final braking and exit sequence.',
].join(' ');

const ROSS_PEDAGOGY_PRIMER = [
  'Coach one change at a time. Start with marks and line, then add shifts, then trail braking, then throttle commitment.',
  'Vision leads the car: look through the corner, turn your head, and keep scanning further ahead rather than staring at the apex.',
  'Trail braking is a handoff from braking force to cornering force. Bleed brake pressure as steering angle rises to keep the front tires loaded.',
  'Smooth inputs win. Abrupt releases and stabs upset balance, while later apexes and better exits usually matter more than hero entry speed.',
].join(' ');

function isSonomaTrack(track: Track | null | undefined): boolean {
  return track?.name === SONOMA_RACEWAY.name;
}

function mapSonomaCornerId(corner: Corner | null | undefined): SonomaCornerId | null {
  if (!corner) return null;
  const normalized = corner.name.toLowerCase().replace(/[^a-z0-9]/g, '');
  switch (normalized) {
    case 'turn2':
      return 'turn2';
    case 'turn3':
      return 'turn3';
    case 'turn3a':
      return 'turn3a';
    case 'turn6':
    case 'carousel':
      return 'turn6';
    case 'turn7':
      return 'turn7';
    case 'turn910':
    case 'turns910':
    case 'turn9turn10':
      return 'turn9_10';
    default:
      return null;
  }
}

function sessionFocusForPhase(sessionPhase: SessionPhase) {
  return TROD_SESSION_PROGRESSION[Math.min(sessionPhase, TROD_SESSION_PROGRESSION.length) - 1];
}

function fallbackSonomaAdvice(corner: Corner): string {
  switch (corner.name) {
    case 'Turn 1':
      return 'Set the platform early, brake straight, and keep the hands calm over the first direction change.';
    case 'Turn 11':
      return 'This is one of the biggest braking zones on the lap. Finish the heavy brake work in a straight line and protect the exit.';
    case 'Turn 12':
      return 'Unwind the wheel early and prioritize exit speed all the way to start-finish.';
    default:
      return corner.advice;
  }
}

export function getTrackFeedforwardMessage(
  track: Track | null | undefined,
  corner: Corner,
  skillLevel: SkillLevel,
  sessionPhase: SessionPhase,
): string {
  if (!isSonomaTrack(track)) {
    return `${corner.name}: ${corner.advice}`;
  }

  const sonomaCornerId = mapSonomaCornerId(corner);
  const sessionFocus = sessionFocusForPhase(sessionPhase);

  if (sonomaCornerId) {
    const cornerAdvice = TROD_CORNER_ADVICE[sonomaCornerId];
    if (skillLevel === 'BEGINNER') {
      return `${corner.name}: ${cornerAdvice.keyPhrases.slice(0, 2).join(' ')} Focus this lap: ${sessionFocus.focus}.`;
    }
    if (skillLevel === 'ADVANCED') {
      return `${corner.name}: ${cornerAdvice.trodAdvice} Common miss: ${cornerAdvice.beginnerMistakes[0]}.`;
    }
    return `${corner.name}: ${cornerAdvice.trodAdvice}`;
  }

  const fallback = fallbackSonomaAdvice(corner);
  return skillLevel === 'BEGINNER'
    ? `${corner.name}: ${fallback} Focus this lap: ${sessionFocus.focus}.`
    : `${corner.name}: ${fallback}`;
}

export function getTrackPromptContext(
  track: Track | null | undefined,
  corner: Corner | null,
  skillLevel: SkillLevel,
  sessionPhase: SessionPhase,
): string {
  if (!isSonomaTrack(track)) return '';

  const sessionFocus = sessionFocusForPhase(sessionPhase);
  const sonomaCornerId = mapSonomaCornerId(corner);
  const insights = sonomaCornerId
    ? getInsightsForCorner(sonomaCornerId)
    : TROD_INSIGHTS.filter((insight) => insight.cornerIds.length === 0);

  const insightLines = insights
    .slice(0, 4)
    .map((insight) => `- ${insight.category}: ${insight.insight}`)
    .join('\n');

  const cornerBlock = corner == null
    ? ''
    : sonomaCornerId
      ? `Corner dossier for ${corner.name}:
- Priority: ${TROD_CORNER_ADVICE[sonomaCornerId].priority}
- T-Rod advice: ${TROD_CORNER_ADVICE[sonomaCornerId].trodAdvice}
- Common beginner mistakes: ${TROD_CORNER_ADVICE[sonomaCornerId].beginnerMistakes.join('; ')}
`
      : `Corner dossier for ${corner.name}:
- ${fallbackSonomaAdvice(corner)}
`;

  return `TRACK-SPECIFIC DOMAIN LAYER:
${SONOMA_TRACK_PRIMER}

COACHING PEDAGOGY:
${ROSS_PEDAGOGY_PRIMER}

CURRENT SESSION FOCUS:
- Step ${sessionFocus.step}: ${sessionFocus.focus}
- ${sessionFocus.description}
- Driver skill level right now: ${skillLevel}
- Relevant readiness signals: ${sessionFocus.readinessSignals.join('; ')}

${cornerBlock}RELEVANT INSIGHTS:
${insightLines}

When coaching Sonoma, prioritize exit quality from Turns 3, 7, 11, and 12, protect maintenance throttle in the Carousel, and do not overload the driver with more than one new change at a time.`;
}
