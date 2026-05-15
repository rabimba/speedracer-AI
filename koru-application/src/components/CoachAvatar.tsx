import { useEffect, useMemo, useState } from 'react';
import { Minimize2, Sparkles } from 'lucide-react';
import spriteSheet from '../assets/coach-avatar-sprite.svg';
import type { CoachAction, SSEConnectionStatus } from '../types';
import { COACHES, DEFAULT_COACH } from '../utils/coachingKnowledge';
import type { CoachMessage } from './CoachPanel';

type AvatarState = 'talk' | 'race' | 'data' | 'celebrate';

interface CoachAvatarProps {
  activeCoach: string;
  latestMessage: CoachMessage | null;
  status: SSEConnectionStatus;
  layout?: 'floating' | 'inline';
}

const RACE_ACTIONS = new Set<CoachAction>([
  'THROTTLE',
  'FULL_THROTTLE',
  'PUSH',
  'HUSTLE',
  'COMMIT',
  'TURN_IN',
  'ROTATE',
  'APEX',
]);

const CELEBRATE_ACTIONS = new Set<CoachAction>([
  'MAINTAIN',
  'PUSH',
  'FULL_THROTTLE',
  'HUSTLE',
]);

const FRAME_INTERVAL_MS: Record<AvatarState, number> = {
  talk: 240,
  race: 140,
  data: 220,
  celebrate: 180,
};

const ROW_BY_STATE: Record<AvatarState, number> = {
  talk: 0,
  race: 1,
  data: 2,
  celebrate: 3,
};

function resolveAvatarState(
  latestMessage: CoachMessage | null,
  status: SSEConnectionStatus,
  ageMs: number,
): AvatarState {
  if (!latestMessage || ageMs > 5500) {
    return status === 'connected' ? 'data' : 'talk';
  }

  if (latestMessage.text.startsWith('Saved ')) return 'celebrate';
  if (latestMessage.action && CELEBRATE_ACTIONS.has(latestMessage.action) && latestMessage.priority === 0) {
    return 'celebrate';
  }
  if (latestMessage.action && RACE_ACTIONS.has(latestMessage.action)) {
    return 'race';
  }
  if (latestMessage.path === 'edge' || latestMessage.backend === 'litertlm' || latestMessage.backend === 'aicore') {
    return 'data';
  }
  if (latestMessage.priority === 0) return 'celebrate';
  return 'talk';
}

function fallbackCopy(status: SSEConnectionStatus): string {
  switch (status) {
    case 'connected':
      return 'I am riding along. Tap in any time to see what the live coach is hearing.';
    case 'connecting':
      return 'Getting your live coaching session ready.';
    case 'error':
      return 'The session hit an issue. Reset the run and I will jump back in.';
    default:
      return 'Tap to bring your coach on screen before you head out.';
  }
}

export default function CoachAvatar({
  activeCoach,
  latestMessage,
  status,
  layout = 'floating',
}: CoachAvatarProps) {
  const [expanded, setExpanded] = useState(false);
  const [frameIndex, setFrameIndex] = useState(0);
  const [clock, setClock] = useState(0);
  const [cuePulse, setCuePulse] = useState(false);

  const coach = COACHES[activeCoach] || COACHES[DEFAULT_COACH];
  const latestMessageId = latestMessage?.id;

  useEffect(() => {
    const tick = () => setClock(Date.now());
    const initial = window.setTimeout(tick, 0);
    const timer = window.setInterval(tick, 1000);
    return () => {
      window.clearTimeout(initial);
      window.clearInterval(timer);
    };
  }, []);

  const ageMs = latestMessage ? clock - latestMessage.timestamp : Number.POSITIVE_INFINITY;
  const avatarState = useMemo(
    () => resolveAvatarState(latestMessage, status, ageMs),
    [ageMs, latestMessage, status],
  );

  useEffect(() => {
    const reset = window.setTimeout(() => setFrameIndex(0), 0);
    const timer = window.setInterval(() => {
      setFrameIndex((current) => (current + 1) % 4);
    }, FRAME_INTERVAL_MS[avatarState]);
    return () => {
      window.clearTimeout(reset);
      window.clearInterval(timer);
    };
  }, [avatarState]);

  useEffect(() => {
    if (!latestMessageId || expanded) return;

    const start = window.setTimeout(() => setCuePulse(true), 0);
    const timer = window.setTimeout(() => setCuePulse(false), 4200);
    return () => {
      window.clearTimeout(start);
      window.clearTimeout(timer);
    };
  }, [expanded, latestMessageId]);

  const buildSpriteStyle = (frameSize: number) => ({
    backgroundImage: `url(${spriteSheet})`,
    backgroundPosition: `-${frameIndex * frameSize}px -${ROW_BY_STATE[avatarState] * frameSize}px`,
    backgroundSize: `${frameSize * 4}px ${frameSize * 4}px`,
  });

  const launcherSpriteStyle = buildSpriteStyle(64);
  const expandedSpriteStyle = buildSpriteStyle(96);

  const metaLabel = latestMessage
    ? `${latestMessage.path.toUpperCase()}${latestMessage.backend ? ` · ${latestMessage.backend}` : ''}`
    : status === 'connected'
      ? 'LIVE SESSION READY'
      : 'COACH AVATAR';

  const summaryText = latestMessage?.text ?? fallbackCopy(status);

  return (
    <div className={`coach-avatar-dock coach-avatar-dock-${layout} ${expanded ? 'open' : 'closed'}`}>
      {!expanded && (
        <button
          type="button"
          className={`coach-avatar-launcher ${cuePulse ? 'pulse' : ''}`}
          onClick={() => setExpanded(true)}
          aria-label="Open coach avatar"
        >
          <div
            className={`coach-avatar-sprite-frame coach-avatar-sprite-mini state-${avatarState}`}
            style={launcherSpriteStyle}
          />
          <div className="coach-avatar-launcher-copy">
            <span>{coach.name}</span>
            <small>{latestMessage ? 'Tap for latest cue' : 'Summon coach avatar'}</small>
          </div>
          {cuePulse && <span className="coach-avatar-notification" />}
        </button>
      )}

      {expanded && (
        <div className="coach-avatar-card" role="dialog" aria-label="Coach avatar">
          <div className="coach-avatar-card-header">
            <div>
              <span className="coach-avatar-kicker">Interactive coach</span>
              <h4>{coach.name}</h4>
            </div>
            <button
              type="button"
              className="icon-btn"
              onClick={() => setExpanded(false)}
              aria-label="Minimize coach avatar"
            >
              <Minimize2 size={16} />
            </button>
          </div>

          <div className="coach-avatar-stage">
            <div className={`coach-avatar-shell state-${avatarState}`}>
              <div className="coach-avatar-scene" />
              <div className={`coach-avatar-sprite-frame state-${avatarState}`} style={expandedSpriteStyle} />
            </div>

            <div className="coach-avatar-bubble">
              <span className="coach-avatar-badge">
                <Sparkles size={12} />
                {metaLabel}
              </span>
              <p>{summaryText}</p>
              <div className="coach-avatar-style">{coach.style}</div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
