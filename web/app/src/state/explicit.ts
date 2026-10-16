import { create } from 'zustand';

/**
 * "Block explicit songs" (#720), as on the phone (ExplicitFilter.kt): while
 * it's on, a song YouTube Music marks explicit is left out of AutoPlay,
 * skipped in the queue, and greyed out in lists, where tapping it asks first.
 * Songs are let through one at a time from that prompt or the song's menu.
 */
interface ExplicitState {
  block: boolean;
  /** "Play clean versions instead": blocked songs play their clean version (playback/cleanVersions.ts). */
  playClean: boolean;
  /** Video ids let through one at a time. */
  allowed: string[];
  /** A tapped blocked song waiting on the prompt; [play] is the tap, run once it's allowed. */
  prompt: { videoId: string; title: string; play: () => void } | null;
  /** A short notice ("Explicit song skipped"), cleared after a few seconds. */
  notice: string | null;
  setBlock: (block: boolean) => void;
  setPlayClean: (playClean: boolean) => void;
  setAllowed: (videoId: string, allowed: boolean) => void;
  ask: (prompt: NonNullable<ExplicitState['prompt']>) => void;
  /** The prompt's answer: let the song through, turn blocking off, or (null) cancel. */
  answer: (choice: 'allow' | 'turn-off' | null) => void;
  notify: (notice: string) => void;
}

const KEY = 'bitchord:explicit';

function load(): Pick<ExplicitState, 'block' | 'playClean' | 'allowed'> {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<ExplicitState>;
    return {
      block: saved.block === true,
      playClean: saved.playClean === true,
      allowed: Array.isArray(saved.allowed) ? saved.allowed : [],
    };
  } catch {
    return { block: false, playClean: false, allowed: [] };
  }
}

let noticeTimer: ReturnType<typeof setTimeout> | undefined;

export const useExplicit = create<ExplicitState>()((set, get) => ({
  ...load(),
  prompt: null,
  notice: null,
  setBlock(block) {
    set({ block });
    save();
  },
  setPlayClean(playClean) {
    set({ playClean });
    save();
  },
  setAllowed(videoId, allowed) {
    const rest = get().allowed.filter((id) => id !== videoId);
    set({ allowed: allowed ? [...rest, videoId] : rest });
    save();
  },
  ask(prompt) {
    set({ prompt });
  },
  answer(choice) {
    const { prompt } = get();
    set({ prompt: null });
    if (!prompt || !choice) return;
    if (choice === 'allow') get().setAllowed(prompt.videoId, true);
    else get().setBlock(false);
    prompt.play();
  },
  notify(notice) {
    clearTimeout(noticeTimer);
    set({ notice });
    noticeTimer = setTimeout(() => set({ notice: null }), 3000);
  },
}));

function save() {
  const { block, playClean, allowed } = useExplicit.getState();
  try {
    localStorage.setItem(KEY, JSON.stringify({ block, playClean, allowed }));
  } catch {
    // Storage off (a private window): the setting lasts for this visit.
  }
}

/** The rule (ExplicitFilter.blocks): blocking on, marked explicit, and not let through. */
export function isBlocked(
  song: { videoId: string; explicit: boolean },
  { block, allowed }: Pick<ExplicitState, 'block' | 'allowed'> = useExplicit.getState(),
): boolean {
  return block && song.explicit && !allowed.includes(song.videoId);
}

/**
 * Whether explicit songs play their clean versions: blocking and "Play clean
 * versions instead" both on. Then nothing looks explicit: rows aren't greyed
 * or gated, the E becomes a C, and an album listed in both editions shows only
 * the clean one.
 */
export const playsClean = ({ block, playClean }: Pick<ExplicitState, 'block' | 'playClean'> = useExplicit.getState()) =>
  block && playClean;

export const usePlaysClean = () => useExplicit(playsClean);

/** Whether a row is greyed out and asks before playing: [isBlocked], unless it will play clean. */
export function useBlocked(videoId: string, explicit: boolean): boolean {
  return useExplicit((s) => !playsClean(s) && isBlocked({ videoId, explicit }, s));
}

/** A row's tap: [play] as usual, unless the song is blocked, when it asks first. */
export function playUnlessBlocked(song: { videoId: string; title: string; explicit: boolean }, play: () => void) {
  if (isBlocked(song) && !playsClean()) useExplicit.getState().ask({ videoId: song.videoId, title: song.title, play });
  else play();
}
