import { beforeEach, describe, expect, it } from 'vitest';
import type { MusicItem, Track } from '@/data/music/types';
import { cleanEditions, cleanSourceOf, findClean, pick, pickVideo, resetCleanVersions } from './cleanVersions';

// The cases of CleanVersionsTest.kt, so both apps pick the same clean versions.

function song(videoId: string, { title = 'Rap God', artist = 'Eminem', length = 364 as number | null, explicit = false } = {}): Track {
  const artists = artist.split(', ').map((name) => ({ name, id: null }));
  return { videoId, title, artists, album: null, durationSec: length, explicit, thumbnail: null, index: null, plays: null };
}
const video = (id: string, title: string, channel: string, length: number | null) => song(id, { title, artist: channel, length });
const explicit = song('explicit', { explicit: true });

beforeEach(() => resetCleanVersions(async () => []));

describe('matching', () => {
  it('picks the clean release of the same song', () => {
    const candidates = [song('explicit', { explicit: true }), song('other', { title: 'Rap Killa', artist: 'Karan Aujla' }), song('clean')];
    expect(pick(explicit, candidates)?.videoId).toBe('clean');
  });

  it("doesn't take edition and soundtrack tags for a different song", () => {
    expect(pick(explicit, [song('tagged', { title: 'Rap God (Clean)' })])?.videoId).toBe('tagged');
    const loseYourself = song('e', { title: 'Lose Yourself', length: 321, explicit: true });
    const soundtrack = song('st', { title: 'Lose Yourself (From "8 Mile" Soundtrack)', length: 322 });
    expect(pick(loseYourself, [soundtrack])?.videoId).toBe('st');
  });

  it('never picks explicit rows, other versions, other artists or other cuts', () => {
    const candidates = [
      song('dirty-again', { explicit: true }),
      song('live', { title: 'Rap God (Live)' }),
      song('remix', { title: 'Rap God Remix' }),
      song('cover', { artist: 'Kane Vortex' }),
      song('short', { length: 210 }),
    ];
    expect(pick(explicit, candidates)).toBeNull();
  });

  it('takes the closest length', () => {
    expect(pick(explicit, [song('near', { length: 370 }), song('nearest', { length: 365 })])?.videoId).toBe('nearest');
  });

  it("picks an upload that says it's the clean edit", () => {
    const e = song('e', { title: 'Lose Yourself', length: 321, explicit: true });
    const uploads = [
      video('lyrics', 'Eminem - Lose Yourself (Clean) | Lyrics', 'Throwback Hits', 321),
      video('short', 'Lose yourself clean', 'Lucien Whittenberger', 304),
      video('other', 'The Way I Am (Clean Version)', 'Eminem', 303),
    ];
    expect(pickVideo(e, uploads)?.videoId).toBe('lyrics');
  });

  it("never picks uploads that aren't the clean edit", () => {
    const e = song('e', { title: 'Gold Digger', artist: 'Kanye West', length: 208, explicit: true });
    const uploads = [
      video('not-clean', 'Kanye West - Gold Digger', 'Kanye West', 208),
      video('cover', 'HALEY CAN RAP??? GOLD DIGGER COVER (CLEAN)', 'Haley Leathers', 210),
      video('no-artist', 'Gold Digger (Clean)', 'Random Channel', 208),
      video('no-length', 'Kanye West - Gold Digger (Clean)', 'Only Clean Edits', null),
      video('slowed', 'Kanye West - Gold Digger (Clean) slowed', 'Edits', 208),
    ];
    expect(pickVideo(e, uploads)).toBeNull();
  });

  it("takes a featured artist's own remix as the featured version", () => {
    const uploads = [
      video('solo', 'Tame Impala - Dracula (clean version)', 'McDuxx', 206),
      video('jennie', 'Tame Impala, JENNIE - Dracula (JENNIE Remix) (Clean - Lyrics) (feat. JENNIE)', 'Tame Impala', 211),
    ];
    const withJennie = song('e', { title: 'Dracula (feat. JENNIE)', artist: 'Tame Impala', length: 210, explicit: true });
    expect(pickVideo(withJennie, uploads)?.videoId).toBe('jennie');
    const solo = song('e2', { title: 'Dracula', artist: 'Tame Impala', length: 206, explicit: true });
    expect(pickVideo(solo, uploads)?.videoId).toBe('solo');
  });

  it('reads an upload naming the song across several pieces', () => {
    const remix = song('e', { title: 'Costa Rica [Remix] (feat. The Kid LAROI)', artist: 'Bankrol Hayden', length: 115, explicit: true });
    const uploads = [
      video('original', 'Bankrol Hayden - Costa Rica (Clean - Lyrics)', 'Polar Records', 114),
      video('remix', '[CLEAN] Bankrol Hayden - Costa Rica (feat. The Kid LAROI) - Remix', 'Link - Edits', 116),
    ];
    expect(pickVideo(remix, uploads)?.videoId).toBe('remix');
  });
});

describe('lookups', () => {
  it('tries songs before uploads, and dresses an upload as the song', async () => {
    const asked: string[] = [];
    resetCleanVersions(async (query, type) => {
      asked.push(`${query}/${type}`);
      return type === 'video' ? [video('upload', 'Eminem - Rap God (Clean)', 'Edits', 365)] : [];
    });
    const clean = await findClean(explicit);
    expect(asked).toHaveLength(3);
    expect(clean).toMatchObject({ videoId: 'upload', title: 'Rap God', explicit: false });
    expect(clean?.artists.map((a) => a.name)).toEqual(['Eminem']);
    expect(cleanSourceOf('upload')).toBe('YouTube');
    expect(cleanSourceOf('explicit')).toBe('YouTube');
  });

  it('remembers an answer, and having none', async () => {
    let searches = 0;
    resetCleanVersions(async () => (searches++, [song('clean')]));
    await findClean(explicit);
    await findClean(explicit);
    expect(searches).toBe(1);
    expect(cleanSourceOf('clean')).toBe('YouTube Music');
    expect(cleanSourceOf('unrelated')).toBeNull();
    searches = 0;
    resetCleanVersions(async () => (searches++, []));
    expect(await findClean(explicit)).toBeNull();
    expect(await findClean(explicit)).toBeNull();
    expect(searches).toBe(3);
  });

  it("doesn't remember a failed search as none", async () => {
    let searches = 0;
    resetCleanVersions(async () => (searches++, null));
    await findClean(explicit);
    await findClean(explicit);
    expect(searches).toBe(6);
  });

  it('never looks up a clean song', async () => {
    let searches = 0;
    resetCleanVersions(async () => (searches++, []));
    expect(await findClean(song('clean'))).toBeNull();
    expect(searches).toBe(0);
  });
});

it('shows an album listed in both editions only as the clean one', () => {
  const card = (id: string, title: string, isExplicit: boolean): MusicItem => ({
    kind: 'album', id, title, subtitle: 'Album • Eminem • 2013', artists: [], album: null,
    thumbnail: null, wide: false, durationSec: null, explicit: isExplicit,
  });
  const kept = cleanEditions([card('e', 'The Marshall Mathers LP2', true), card('r', 'Recovery', true), card('c', 'The Marshall Mathers LP2', false)]);
  // Recovery has no clean card here, so it stays.
  expect(kept.map((c) => c.id)).toEqual(['r', 'c']);
  // Songs are left alone.
  const track: MusicItem = { ...card('v', 'Rap God', true), kind: 'song' };
  expect(cleanEditions([track, card('c2', 'Rap God', false)])).toContain(track);
});
