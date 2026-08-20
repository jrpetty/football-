import { useState } from 'react';
import { engineData } from '../store.ts';

/**
 * Provenance banner.
 *
 * Shown on every screen until measured data outweighs recalled data. The app
 * renders confident monospace figures with tidy uncertainty bands; without this
 * line, anyone opening it would reasonably assume those figures came from
 * measurements. They do not yet, and the interface should say so where it
 * cannot be missed rather than in a tab nobody opens.
 */
export function ProvenanceBanner() {
  const [dismissed, setDismissed] = useState(false);

  // Derived from the data itself, so it disappears on its own once the harness
  // has supplied enough measurements — no flag to remember to flip.
  const gpuProv = new Set<string>();
  for (const g of engineData.gpus.values()) for (const v of Object.values(g._prov ?? {})) for (const p of v) gpuProv.add(p);
  const seeded = gpuProv.has('model-knowledge') && gpuProv.size === 1;

  if (!seeded || dismissed) return null;

  return (
    <div className="provenance-banner">
      <span className="mark">seed data</span>
      <span className="body">
        <b>These figures come from recalled data, not measurements.</b> The specification sources this
        tool is built around were unreachable when the catalogue was seeded, so every hardware record and
        every validation fixture is tagged <span className="mono">model-knowledge</span>. Relative
        orderings are reliable; absolute frame rates are not. Import measurements via{' '}
        <span className="mono">data/manual/</span> to replace them — this notice clears itself when
        measured data outweighs recalled.
      </span>
      <button onClick={() => setDismissed(true)} title="Hide until reload">dismiss</button>
    </div>
  );
}
