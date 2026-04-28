import { describe, expect, it } from 'vitest';
import { SONOMA_RACEWAY } from '../../data/trackData';
import {
  getTrackHotActionMessage,
  getTrackPromptContext,
  shouldSuppressTrackAction,
} from '../../data/trackExpertise';

describe('track expertise doctrine', () => {
  const turn3 = SONOMA_RACEWAY.corners.find((corner) => corner.id === 3)!;
  const turn6 = SONOMA_RACEWAY.corners.find((corner) => corner.id === 6)!;

  it('suppresses premature throttle coaching at Sonoma Turn 3 before exit', () => {
    expect(
      shouldSuppressTrackAction(
        SONOMA_RACEWAY,
        turn3,
        'THROTTLE',
        'BEGINNER',
        2,
        'TURN_IN',
        0.2,
      ),
    ).toBe(true);
  });

  it('suppresses push coaching in the Sonoma Carousel', () => {
    expect(
      shouldSuppressTrackAction(
        SONOMA_RACEWAY,
        turn6,
        'PUSH',
        'INTERMEDIATE',
        3,
        'MID_CORNER',
        0.18,
      ),
    ).toBe(true);
  });

  it('returns Sonoma-specific hot-path text for Turn 3 exit throttle', () => {
    expect(
      getTrackHotActionMessage(
        SONOMA_RACEWAY,
        turn3,
        'THROTTLE',
        'BEGINNER',
        3,
        'EXIT',
        0.18,
      ),
    ).toContain('late apex finished');
  });

  it('injects both T-Rod and Ross doctrine into the prompt context', () => {
    const prompt = getTrackPromptContext(SONOMA_RACEWAY, turn3, 'BEGINNER', 2);
    expect(prompt).toContain('T-Rod advice');
    expect(prompt).toContain('Coach one change at a time');
    expect(prompt).toContain('Turn 3');
  });
});
