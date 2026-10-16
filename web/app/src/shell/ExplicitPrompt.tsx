import { useEffect } from 'react';
import { useExplicit } from '@/state/explicit';
import styles from './ExplicitPrompt.module.css';

/**
 * What a tapped blocked explicit song asks (state/explicit.ts): let this one
 * song through, turn blocking off, or cancel. Both of the first two then play
 * the tap. Also shows the short notice when the queue skips a blocked song.
 */
export function ExplicitPrompt() {
  const prompt = useExplicit((s) => s.prompt);
  const notice = useExplicit((s) => s.notice);
  const { answer } = useExplicit.getState();

  useEffect(() => {
    if (!prompt) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && answer(null);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [prompt, answer]);

  return (
    <>
      {prompt && (
        <div className={styles.scrim} onClick={() => answer(null)}>
          <div
            className={styles.dialog}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="explicit-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="explicit-title">Explicit Song</h2>
            <p>“{prompt.title}” is marked explicit and your filter is blocking it.</p>
            <div className={styles.actions}>
              <button onClick={() => answer(null)}>Cancel</button>
              <button onClick={() => answer('turn-off')}>Turn Off Blocking</button>
              <button className={styles.primary} onClick={() => answer('allow')} autoFocus>
                Allow This Song
              </button>
            </div>
          </div>
        </div>
      )}
      {notice && (
        <div className={styles.notice} role="status">
          {notice}
        </div>
      )}
    </>
  );
}
