import { queries, sharesArtist } from '@/data/match/trackMatcher';
import type { MusicItem, Track } from '@/data/music/types';
import { trackLog } from './trackLog';

/**
 * "Play clean versions instead", as on the phone (CleanVersions.kt): finds the
 * clean version of an explicit song for the player to play in its place.
 *
 * Most explicit songs on YouTube Music also exist as a clean release: same
 * title, same artist, same length, no "E". [pick] takes that, and nothing that
 * is merely similar. Songs with no clean release often have a clean edit
 * someone uploaded to YouTube ("Eminem - Lose Yourself (Clean)"), which
 * [pickVideo] reads with stricter checks.
 */

/** An edit mutes words; it doesn't drop a verse. Anything further apart is another cut. */
export const MAX_LENGTH_DIFFERENCE_SEC = 15;

const EDITION_WORDS =
  'explicit|clean|dirty|edited|censored|radio edit|radio version|clean version|clean edit|explicit version|album version';
const EDITION_TAG = new RegExp(`[(\\[]\\s*(?:${EDITION_WORDS})\\s*[)\\]]`, 'g');
const EDITION_SUFFIX = new RegExp(`\\s+-\\s+(?:${EDITION_WORDS})\\s*$`, 'g');
const SOURCE_TAG = /[([]\s*from\s[^)\]]*[)\]]/g;
const SOURCE_SUFFIX = /\s+-\s+from\s.*$/g;
const FEATURING = /[([]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]/g;
const FEATURING_WORD = /^[([]\s*(?:feat\.?|ft\.?|featuring|with)\s|[)\]]$/g;
const FEATURING_BARE = /\b(?:feat\.?|ft\.?|featuring)\s.*$/g;
const BRACKETED = /[([][^)\]]*[)\]]/g;
const PART_BREAK = /\s+[-–—|]\s+|\s*\|\s*|:\s+/;
const TRAILING_CLEAN = /(?:\s+(?:clean|edited|radio edit|clean version|version))+$/;
const ARTIST_BREAK = /\s*(?:,|&| x | feat\.? | ft\.? | and )\s*/;
const CLEAN_MARK = /\b(?:clean|edited|radio edit|censored)\b/;
const REJECT =
  /\b(?:cover|karaoke|instrumental|remix|slowed|sped up|speed up|reverb|8d|nightcore|live|lip ?sync|reaction|fan video|parody|mashup|bass boosted|acapella|a cappella|loop|hours?|piano|guitar|drum|dance|choreography|tutorial|lesson|lofi|lo fi)\b/g;

const fold = (text: string) => text.normalize('NFD').replace(/\p{Mn}+/gu, '').toLowerCase();
const tidy = (text: string) => text.replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/\s+/g, ' ');
const artistOf = (track: Track) => track.artists.map((a) => a.name).join(', ');

/** The title without edition or source tags ("(Clean)", "(From "8 Mile")"), a featured credit, case or punctuation. */
export function coreTitle(title: string): string {
  return tidy(
    fold(title)
      .replace(EDITION_TAG, ' ')
      .replace(EDITION_SUFFIX, ' ')
      .replace(SOURCE_TAG, ' ')
      .replace(SOURCE_SUFFIX, ' ')
      .replace(FEATURING, ' '),
  );
}

/** The clean release of [explicit] among [candidates], the closest length first; null when there is none. */
export function pick(explicit: Track, candidates: Track[]): Track | null {
  const wanted = coreTitle(explicit.title);
  if (!wanted) return null;
  const matches = candidates.filter(
    (c) =>
      !c.explicit &&
      c.videoId !== explicit.videoId &&
      coreTitle(c.title) === wanted &&
      sharesArtist(artistOf(explicit), artistOf(c)),
  );
  return closest(explicit, matches, false);
}

/**
 * A clean edit someone uploaded to YouTube: taken only when it says it is
 * clean, names the song and the artist, isn't a cover, remix, slowed edit,
 * live take or the like, and runs within [MAX_LENGTH_DIFFERENCE_SEC] of the
 * original. A remix named for one of the song's own featured artists still
 * counts ("Dracula (JENNIE Remix)"), and an upload naming the featured artist
 * is preferred.
 */
export function pickVideo(explicit: Track, videos: Track[]): Track | null {
  const wanted = coreTitle(explicit.title);
  if (!wanted) return null;
  const names = artistNames(explicit);
  const featured = featuredNames(explicit);
  const spaced = (video: Track) => ` ${tidy(fold(video.title))} `;
  const matches = videos.filter((video) => {
    if (video.videoId === explicit.videoId || video.explicit) return false;
    const folded = fold(video.title);
    if (!CLEAN_MARK.test(folded)) return false;
    const rejected = [...folded.matchAll(REJECT)].some(
      ([word]) => !wanted.includes(word) && !(word === 'remix' && featured.some((f) => spaced(video).includes(` ${f} remix `))),
    );
    if (rejected || !titleParts(video.title, names).includes(wanted)) return false;
    const core = ` ${coreTitle(video.title)} `;
    return names.some((n) => core.includes(` ${n} `)) || sharesArtist(artistOf(explicit), artistOf(video));
  });
  const namingFeatured = matches.filter((video) => featured.some((f) => spaced(video).includes(` ${f} `)));
  return closest(explicit, namingFeatured, true) ?? closest(explicit, matches, true);
}

/** The candidate nearest [explicit]'s length, the first on a tie; uploads need a known length. */
function closest(explicit: Track, candidates: Track[], requireLength: boolean): Track | null {
  let best: Track | null = null;
  let bestGap = Infinity;
  for (const candidate of candidates) {
    const known = explicit.durationSec != null && candidate.durationSec != null;
    if (!known && requireLength) continue;
    const gap = known ? Math.abs(explicit.durationSec! - candidate.durationSec!) : 0;
    if (gap <= MAX_LENGTH_DIFFERENCE_SEC && gap < bestGap) {
      best = candidate;
      bestGap = gap;
    }
  }
  return best;
}

/**
 * The pieces of an upload's title that could be the song's name: brackets
 * dropped, split at " - ", " | " and ": ", each without the artist's name, a
 * featured credit or a trailing "clean". Then all of them together, for a
 * title split over several pieces ("Artist - Song - Remix").
 */
function titleParts(title: string, names: string[]): string[] {
  const parts = fold(title)
    .replace(BRACKETED, ' ')
    .split(PART_BREAK)
    .map((part) => {
      let piece = ` ${tidy(part.replace(FEATURING_BARE, ' '))} `;
      for (const name of names) piece = piece.replaceAll(` ${name} `, ' ');
      return tidy(tidy(piece).replace(TRAILING_CLEAN, ''));
    })
    .filter(Boolean);
  return [...parts, parts.join(' ')];
}

const names = (list: string[]) => [...new Set(list.map((n) => tidy(fold(n))).filter((n) => n.length >= 2))];

function artistNames(track: Track): string[] {
  return names([...track.artists.map((a) => a.name), ...artistOf(track).split(ARTIST_BREAK)]);
}

/** The song's featured artists: its "(feat. …)" credit and every credited artist after the first. */
function featuredNames(track: Track): string[] {
  const credit = fold(track.title).match(FEATURING)?.[0]?.replace(FEATURING_WORD, ' ').split(ARTIST_BREAK) ?? [];
  return names([...credit, ...track.artists.slice(1).map((a) => a.name)]);
}

// ---- Lookups ---------------------------------------------------------------------

/** Where a clean version came from, named as the listener knows it. */
export type CleanSource = 'YouTube Music' | 'YouTube';

/** How a lookup searches, signed out; null when the search itself failed. Swapped out by tests. */
type Search = (query: string, type: 'song' | 'video') => Promise<Track[] | null>;

const signedOutSearch: Search = async (query, type) => {
  try {
    const { searchSignedOut } = await import('@/data/music/api');
    return await searchSignedOut(query, type);
  } catch (error) {
    trackLog.w(`clean-version search failed: ${String(error)}`);
    return null;
  }
};

let search = signedOutSearch;
/** Answers by explicit video id, "there is none" (null) included. Failed lookups aren't kept. */
const answers = new Map<string, Track | null>();
const inFlight = new Map<string, Promise<Track | null>>();
/** Where each clean version handed out came from, by its own id. */
const sources = new Map<string, CleanSource>();

/** Where the clean version playing as [videoId], or found for the explicit song [videoId], came from. */
export function cleanSourceOf(videoId: string): CleanSource | null {
  return sources.get(answers.get(videoId)?.videoId ?? videoId) ?? null;
}

/**
 * The clean version of [track], looked up if it hasn't been; null when there
 * is none or the lookup couldn't run. Callers asking at once share a lookup.
 * Searches YouTube Music's Songs for "<title> <artist>", then with "clean"
 * added, then YouTube's videos for an uploaded clean edit.
 */
export function findClean(track: Track): Promise<Track | null> {
  if (!track.explicit) return Promise.resolve(null);
  if (answers.has(track.videoId)) return Promise.resolve(answers.get(track.videoId)!);
  let lookup = inFlight.get(track.videoId);
  if (!lookup) {
    lookup = lookUp(track)
      .catch(() => null)
      .finally(() => inFlight.delete(track.videoId));
    inFlight.set(track.videoId, lookup);
  }
  return lookup;
}

async function lookUp(song: Track): Promise<Track | null> {
  const name = queries({ title: song.title, artist: artistOf(song) })[0];
  if (!name) return null;
  const steps = [
    [name, 'song'],
    [`${name} clean`, 'song'],
    [`${name} clean`, 'video'],
  ] as const;
  // Rows with no length (Home cards) take it from the song's own search result.
  let target = song;
  let failed = false;
  for (const [query, type] of steps) {
    const rows = await search(query, type);
    if (!rows) {
      failed = true;
      continue;
    }
    let clean: Track | null;
    if (type === 'video') {
      const upload = pickVideo(target, rows);
      clean = upload && asCleanUpload(song, upload);
    } else {
      if (target.durationSec == null) target = { ...target, durationSec: rows.find((r) => r.videoId === song.videoId)?.durationSec ?? null };
      clean = pick(target, rows);
      if (clean) sources.set(clean.videoId, 'YouTube Music');
    }
    if (clean) {
      trackLog.i(`clean version of '${song.title}': ${clean.videoId} from ${cleanSourceOf(clean.videoId)}, via "${query}"`, song.videoId);
      answers.set(song.videoId, clean);
      return clean;
    }
  }
  trackLog.i(`no clean version of '${song.title}'${failed ? ' (a search failed; will ask again)' : ''}`, song.videoId);
  if (!failed) answers.set(song.videoId, null);
  return null;
}

/**
 * [upload], a clean edit of [explicit] uploaded to YouTube, as the clean
 * version of that song: on the surface it is the song (its title, artist,
 * album and art), playing the upload. [cleanSourceOf] still says "YouTube".
 */
function asCleanUpload(explicit: Track, upload: Track): Track {
  sources.set(upload.videoId, 'YouTube');
  return { ...explicit, videoId: upload.videoId, durationSec: upload.durationSec ?? explicit.durationSec, explicit: false };
}

/** For tests: a stand-in search, and every answer forgotten. */
export function resetCleanVersions(stub: Search = signedOutSearch) {
  search = stub;
  answers.clear();
  inFlight.clear();
  sources.clear();
}

// ---- Editions ----------------------------------------------------------------------

/**
 * An album listed in both of its editions shows only the clean one: the
 * explicit card is left out when a card of the same release without the badge
 * (same title once edition tags are set aside, same subtitle) is in [among].
 * One listed alone stays, and its songs still play clean.
 */
export function cleanEditions(items: MusicItem[], among: MusicItem[] = items): MusicItem[] {
  const isRelease = (item: MusicItem) => item.kind === 'album' || item.kind === 'playlist';
  const key = (item: MusicItem) => `${coreTitle(item.title)}|${item.subtitle.trim().toLowerCase()}`;
  const clean = new Set(among.filter((i) => isRelease(i) && !i.explicit).map(key));
  if (!clean.size) return items;
  return items.filter((i) => !(isRelease(i) && i.explicit && clean.has(key(i))));
}
