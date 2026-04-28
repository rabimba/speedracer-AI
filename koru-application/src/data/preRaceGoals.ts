import { COACHES } from '../utils/coachingKnowledge';
import type { CoachAction, SessionGoal, SessionGoalFocus, SessionMode } from '../types';

export interface PreRaceGoalOption {
  focus: SessionGoalFocus;
  label: string;
  description: string;
  prioritizedActions: CoachAction[];
}

export interface CoachRecommendation {
  coachId: string;
  title: string;
  rationale: string;
  samplePhrase: string;
}

export const PRE_RACE_GOAL_OPTIONS: PreRaceGoalOption[] = [
  {
    focus: 'braking',
    label: 'Braking technique',
    description: 'Harder initial squeeze and cleaner brake release.',
    prioritizedActions: ['THRESHOLD', 'SPIKE_BRAKE', 'TRAIL_BRAKE'],
  },
  {
    focus: 'throttle',
    label: 'Throttle commitment',
    description: 'Cleaner exit commitment and less hesitation on power.',
    prioritizedActions: ['HUSTLE', 'COMMIT', 'EARLY_THROTTLE', 'FULL_THROTTLE'],
  },
  {
    focus: 'vision',
    label: 'Vision / looking ahead',
    description: 'Eyes up, less target fixation, fewer rushed inputs.',
    prioritizedActions: ['PUSH', 'WAIT', 'COAST'],
  },
  {
    focus: 'lines',
    label: 'Racing lines',
    description: 'Turn-in, apex, and exit placement.',
    prioritizedActions: ['TURN_IN', 'APEX', 'ROTATE'],
  },
  {
    focus: 'smoothness',
    label: 'Smoothness / consistency',
    description: 'Calmer hands and feet, steadier release and throttle build.',
    prioritizedActions: ['COGNITIVE_OVERLOAD', 'LIFT_MID_CORNER', 'SPIKE_BRAKE'],
  },
  {
    focus: 'custom',
    label: 'Custom focus',
    description: 'A specific focus area you want the coach to remember.',
    prioritizedActions: [],
  },
];

const RECOMMENDATION_PREVIEWS: Record<string, string> = {
  tony: 'Commit! Full throttle now.',
  rachel: 'Smooth the release. Balance the platform.',
  aj: 'Brake later. Commit earlier.',
  garmin: 'Brake 5m later. +0.3s potential.',
  superaj: 'Trust it. Commit. Smooth release.',
};

export function buildSessionGoals(
  selectedFocuses: SessionGoalFocus[],
  customDescription: string,
): SessionGoal[] {
  return selectedFocuses.slice(0, 3).map((focus) => {
    const option = PRE_RACE_GOAL_OPTIONS.find((candidate) => candidate.focus === focus);
    const description = focus === 'custom'
      ? customDescription.trim() || 'Custom focus for this session.'
      : option?.description || focus;

    return {
      id: `goal-${focus}`,
      focus,
      description,
      source: 'pre_race_chat',
      prioritizedActions: option?.prioritizedActions ?? [],
    };
  });
}

export function recommendCoach(
  goals: SessionGoal[],
  sessionMode: SessionMode,
): CoachRecommendation {
  if (goals.length === 0) {
    return buildRecommendation(
      'superaj',
      'Best default before the first lap',
      sessionMode === 'camera_direct'
        ? 'This lane is mostly about quick adaptive feedback from limited signals, so Super AJ is the safest default.'
        : 'With no specific goal selected, Super AJ is the safest default because it can switch between confidence, technique, and urgency as needed.',
    );
  }

  const scores = new Map<string, number>([
    ['tony', 0],
    ['rachel', 0],
    ['aj', 0],
    ['garmin', 0],
    ['superaj', 1],
  ]);

  for (const goal of goals) {
    switch (goal.focus) {
      case 'braking':
        bump(scores, 'rachel', 4);
        bump(scores, 'garmin', 2);
        bump(scores, 'superaj', 1);
        break;
      case 'throttle':
        bump(scores, 'tony', 4);
        bump(scores, 'superaj', 2);
        bump(scores, 'aj', 1);
        break;
      case 'vision':
        bump(scores, 'superaj', 3);
        bump(scores, 'tony', 1);
        break;
      case 'lines':
        bump(scores, 'garmin', 3);
        bump(scores, 'rachel', 2);
        break;
      case 'smoothness':
        bump(scores, 'rachel', 3);
        bump(scores, 'superaj', 2);
        break;
      case 'custom':
        bump(scores, 'superaj', 2);
        break;
    }
  }

  if (sessionMode === 'camera_direct' || sessionMode === 'device_test') {
    bump(scores, 'superaj', 2);
  }
  if (goals.length >= 2) {
    bump(scores, 'superaj', 1);
  }

  const coachId = Array.from(scores.entries())
    .sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'superaj';

  const focusLabels = goals.map((goal) => goal.focus);
  if (coachId === 'rachel') {
    return buildRecommendation(
      coachId,
      'Best for technique cleanup',
      `Rachel fits best when the main work is ${naturalList(focusLabels)} and you want clear car-behavior explanations instead of hype.`,
    );
  }
  if (coachId === 'tony') {
    return buildRecommendation(
      coachId,
      'Best for confidence and commitment',
      `Tony fits best when the main work is ${naturalList(focusLabels)} and you want short confidence-building cues on entry and exit.`,
    );
  }
  if (coachId === 'garmin') {
    return buildRecommendation(
      coachId,
      'Best for line and data review',
      `Garmin fits best when the main work is ${naturalList(focusLabels)} and you want number-driven feedback tied to the line and speed trace.`,
    );
  }
  if (coachId === 'aj') {
    return buildRecommendation(
      coachId,
      'Best for blunt short commands',
      `AJ fits best when the goals are already clear and you want direct commands with no extra explanation.`,
    );
  }
  return buildRecommendation(
    'superaj',
    'Best all-around fit',
    `Super AJ fits best for ${naturalList(focusLabels)} because it can switch between calming the driver down, explaining technique, and pushing commitment when needed.`,
  );
}

function buildRecommendation(coachId: string, title: string, rationale: string): CoachRecommendation {
  return {
    coachId,
    title,
    rationale,
    samplePhrase: RECOMMENDATION_PREVIEWS[coachId] ?? COACHES[coachId]?.style ?? '',
  };
}

function bump(scores: Map<string, number>, coachId: string, amount: number): void {
  scores.set(coachId, (scores.get(coachId) ?? 0) + amount);
}

function naturalList(items: string[]): string {
  const labels = items.map((item) => PRE_RACE_GOAL_OPTIONS.find((option) => option.focus === item)?.label.toLowerCase() ?? item);
  if (labels.length <= 1) return labels[0] ?? 'mixed coaching';
  if (labels.length === 2) return `${labels[0]} and ${labels[1]}`;
  return `${labels.slice(0, -1).join(', ')}, and ${labels[labels.length - 1]}`;
}
