/**
 * "Read what this browser already knows."
 *
 * The specification form asks for a graphics card out of 279, a resolution and
 * a refresh rate. The browser knows all three within a few hundred
 * milliseconds, and until now nothing asked it — the benchmark printed the
 * renderer string as a curiosity while the form next to it still made somebody
 * find the same card in a list.
 *
 * Two rules govern what this is allowed to do:
 *
 *  - **Every field says where it came from.** Reported, derived, measured or a
 *    bound: four words, one per field, in front of the user rather than in a
 *    comment. A form that fills itself silently is a form that cannot be
 *    checked.
 *  - **Ambiguity is never resolved by guessing.** "GTX 1060" is two different
 *    cards and the browser does not say which. That case shows both and fills
 *    neither, because a silently wrong part is worse than an empty field.
 */

import { useState } from 'react';
import type { BrowserInspection, SpecConfidence } from '../../core/browserspec.ts';
import { inspectionSummary, resolutionFor } from '../../core/browserspec.ts';
import { identifyGpu, inspectBrowser, prefillFrom, type GpuIdentification } from '../bench/inspect.ts';
import { useApp } from '../store.ts';
import type { Resolution } from '../../core/types.ts';

const CONFIDENCE_LABEL: Record<SpecConfidence, string> = {
  reported: 'reported',
  derived: 'derived',
  measured: 'measured',
  bound: 'at least',
};

export interface SpecApply {
  gpuId?: string;
  resolution?: Resolution;
  refreshHz?: number;
}

export function BrowserSpecPanel({
  onApply,
  onIdentified,
  currentGpuId,
}: {
  onApply: (s: SpecApply) => void;
  /** The catalogue id the renderer resolved to, so the report can cross-check it. */
  onIdentified?: (id: GpuIdentification | null) => void;
  currentGpuId?: string;
}) {
  const { data } = useApp();
  const [state, setState] = useState<{ i: BrowserInspection; gpu: GpuIdentification | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [applied, setApplied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const read = async () => {
    setBusy(true);
    setError(null);
    setApplied(false);
    try {
      const i = await inspectBrowser();
      const gpu = i.renderer ? identifyGpu(i.renderer, data) : null;
      setState({ i, gpu });
      onIdentified?.(gpu);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const prefill = state ? prefillFrom(state.i, state.gpu) : null;
  const hasSomething = !!(prefill && (prefill.gpuId || prefill.resolution || prefill.refreshHz));

  return (
    <div className="panel sub-panel" style={{ marginTop: 14 }}>
      <div className="panel-head">
        <h3>Read what this browser already knows</h3>
        <span className="spacer" />
        <span className="tag">instant</span>
      </div>
      <div className="panel-body">
        <p className="mini" style={{ marginTop: 0 }}>
          No load, no fans, nothing installed — this asks the page what it can already see and fills in
          what it is entitled to fill in. It cannot read the processor model: no browser is allowed to.
        </p>

        {!state && (
          <div className="wizard-nav" style={{ marginTop: 0 }}>
            <button className="btn primary" onClick={read} disabled={busy}>
              {busy ? 'reading…' : 'read this browser'}
            </button>
            <span className="mini">Takes under a second. Nothing leaves the page.</span>
          </div>
        )}

        {error && <div className="note bad">{error}</div>}

        {state && (
          <>
            <div className="verdict-hero" style={{ marginBottom: 14 }}>{inspectionSummary(state.i)}</div>

            <div className="probe-grid">
              <Field
                label="graphics card"
                confidence={state.gpu?.gpuId ? 'reported' : state.i.gpuName ? 'reported' : undefined}
                value={
                  state.gpu?.identity.software
                    ? 'no card is involved — this is software rendering'
                    : state.gpu?.ambiguous.length
                      ? `one of ${state.gpu.ambiguous.length} parts with this name`
                      : (state.gpu?.gpuName ??
                        (state.i.renderer
                          ? `${state.i.renderer} — not a name this catalogue recognises`
                          : 'the browser would not say'))
                }
                note={
                  state.gpu?.identity.software
                    ? 'This is a software rasteriser, not a card. The machine has no working graphics acceleration in this browser, so there is nothing here to fill the field with.'
                    : (state.i.gpuName?.note ?? '')
                }
                extra={
                  state.gpu?.ambiguous.length ? (
                    <>
                      <div className="mini" style={{ marginTop: 5 }}>
                        The driver's name fits {state.gpu.ambiguous.length} parts equally well and does not
                        separate them. Pick the one you have:
                      </div>
                      <div className="toggle-row" style={{ marginTop: 6 }}>
                        {state.gpu.ambiguous.map((a) => (
                          <button
                            key={a.id}
                            className={`toggle${currentGpuId === a.id ? ' on' : ''}`}
                            onClick={() => {
                              onApply({ gpuId: a.id });
                              setApplied(true);
                            }}
                          >
                            {a.name}
                          </button>
                        ))}
                      </div>
                    </>
                  ) : null
                }
              />
              <Field
                label="display"
                confidence={state.i.display?.confidence}
                value={
                  state.i.display
                    ? `${state.i.display.value.width}x${state.i.display.value.height}` +
                      (state.i.display.value.refreshHz ? ` at ${state.i.display.value.refreshHz}Hz` : '')
                    : 'not readable'
                }
                note={state.i.display?.note ?? ''}
                extra={
                  state.i.display && !resolutionFor(state.i.display.value.width) ? (
                    <div className="mini" style={{ marginTop: 5 }}>
                      Below the smallest resolution this model has references for, so the screen field is left
                      alone.
                    </div>
                  ) : null
                }
              />
              <Field
                label="processor threads"
                confidence={state.i.threads?.confidence}
                value={state.i.threads ? `${state.i.threads.value} logical` : 'not readable'}
                note={state.i.threads?.note ?? ''}
                extra={
                  <div className="mini" style={{ marginTop: 5 }}>
                    Not enough to name the part — hundreds of processors have this many threads. The benchmark
                    below narrows it further by measuring the cores and the cache.
                  </div>
                }
              />
              <Field
                label="memory"
                confidence={state.i.memoryFloorGB?.confidence}
                value={state.i.memoryFloorGB ? `${state.i.memoryFloorGB.value}GB or more` : 'not readable'}
                note={state.i.memoryFloorGB?.note ?? ''}
                extra={
                  <div className="mini" style={{ marginTop: 5 }}>
                    A floor, so it is never written into the memory field. Read what is on the sticker, or open
                    Task Manager's Performance tab.
                  </div>
                }
              />
              <Field
                label="platform"
                confidence={state.i.platform?.confidence}
                value={state.i.platform?.value || 'unknown'}
                note={state.i.platform?.note ?? ''}
              />
              <Field
                label="modern graphics API"
                confidence="reported"
                value={state.i.webgpu ? 'WebGPU available' : 'WebGL only'}
                note={
                  state.i.webgpu
                    ? 'WebGPU reports the vendor, architecture and device as separate fields, which is a cleaner identity than the single string WebGL hands over.'
                    : 'This browser has no WebGPU, so the card has to be identified from the WebGL renderer string.'
                }
              />
            </div>

            {state.i.notes.map((n) => (
              <div key={n} className="note warn" style={{ marginTop: 10, fontSize: 12 }}>{n}</div>
            ))}

            <div className="wizard-nav">
              <button
                className="btn primary"
                disabled={!hasSomething || applied}
                onClick={() => {
                  if (!prefill) return;
                  onApply(prefill);
                  setApplied(true);
                }}
              >
                {applied ? 'filled in below' : hasSomething ? 'use these' : 'nothing to fill in'}
              </button>
              <button className="btn" onClick={read} disabled={busy}>read again</button>
              {prefill?.gpuId && currentGpuId && prefill.gpuId !== currentGpuId && !applied && (
                <span className="mini" style={{ color: 'var(--spec)' }}>
                  This disagrees with the card currently selected below.
                </span>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  note,
  confidence,
  extra,
}: {
  label: string;
  value: string;
  note: string;
  confidence?: SpecConfidence;
  extra?: React.ReactNode;
}) {
  return (
    <div className="probe">
      <div className="probe-head">
        <span className="k">{label}</span>
        {confidence && <span className={`tag prov-${confidence}`}>{CONFIDENCE_LABEL[confidence]}</span>}
      </div>
      <div className="v">{value}</div>
      {note && <div className="mini">{note}</div>}
      {extra}
    </div>
  );
}
