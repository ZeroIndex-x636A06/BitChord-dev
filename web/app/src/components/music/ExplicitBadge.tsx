import { usePlaysClean } from '@/state/explicit';
import styles from './ExplicitBadge.module.css';

/**
 * The boxed "E" (report §2: 10×16, 4px after the title). Drawn for BitChord.
 * While explicit songs play their clean versions it is a C: the song will play clean.
 */
export function ExplicitBadge() {
  const clean = usePlaysClean();
  return (
    <span className={styles.badge} role="img" aria-label={clean ? 'Plays clean' : 'Explicit'}>
      {clean ? 'C' : 'E'}
    </span>
  );
}
