import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isBlocked, playUnlessBlocked, useExplicit } from './explicit';

const song = { videoId: 'v1', title: 'HUMBLE.', explicit: true };

describe('Block explicit songs', () => {
  beforeEach(() => useExplicit.setState({ block: false, allowed: [], prompt: null }));

  it('blocks only explicit songs, only while it is on, and not ones let through', () => {
    expect(isBlocked(song)).toBe(false);
    useExplicit.getState().setBlock(true);
    expect(isBlocked(song)).toBe(true);
    expect(isBlocked({ ...song, explicit: false })).toBe(false);
    useExplicit.getState().setAllowed('v1', true);
    expect(isBlocked(song)).toBe(false);
    useExplicit.getState().setAllowed('v1', false);
    expect(isBlocked(song)).toBe(true);
  });

  it('asks before playing a blocked song, and plays it once allowed', () => {
    useExplicit.getState().setBlock(true);
    const play = vi.fn();
    playUnlessBlocked(song, play);
    expect(play).not.toHaveBeenCalled();
    expect(useExplicit.getState().prompt?.videoId).toBe('v1');
    useExplicit.getState().answer('allow');
    expect(play).toHaveBeenCalledOnce();
    expect(useExplicit.getState().allowed).toContain('v1');
  });

  it('turning blocking off from the prompt plays the song too; cancel plays nothing', () => {
    useExplicit.getState().setBlock(true);
    const play = vi.fn();
    playUnlessBlocked(song, play);
    useExplicit.getState().answer(null);
    expect(play).not.toHaveBeenCalled();
    playUnlessBlocked(song, play);
    useExplicit.getState().answer('turn-off');
    expect(play).toHaveBeenCalledOnce();
    expect(useExplicit.getState().block).toBe(false);
  });
});
