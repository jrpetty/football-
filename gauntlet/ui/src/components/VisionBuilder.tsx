/** Test Builder: attach PNG/JPEG images to a case (vision tests). */
import { useRef, useState } from 'react';
import type { PromptTestCase } from '../types.ts';
import { imagePathFor, testImageUrl, uploadTestImage } from '../vision.ts';
import { useToast } from '../context.tsx';
import { Icon } from './icons.tsx';
import { cx } from './ui.tsx';
import { VisionImage } from './VisionImage.tsx';

type CaseImage = NonNullable<PromptTestCase['images']>[number];
const CUSTOM_FILE = 'tests/custom/_.json';

const refFile = (x: CaseImage) => (typeof x === 'string' ? x : x.file);
const refTurn = (x: CaseImage) => (typeof x === 'string' ? 0 : x.turn ?? 0);

/**
 * Image references copied from another test are relative to that test's folder; rewrite them so they resolve from
 * tests/custom/ (where the Builder saves), e.g. "images/chart.png" in tests/vision/ → "../vision/images/chart.png".
 */
export function rebaseImages(sourceFile: string, images: PromptTestCase['images']): PromptTestCase['images'] {
  if (!images?.length) return images;
  return images.map((x) => {
    const path = imagePathFor(sourceFile, refFile(x));
    const file = path.startsWith('custom/') ? path.slice('custom/'.length) : `../${path}`;
    return typeof x === 'string' ? file : { ...x, file };
  });
}

export function CaseImageEditor({ testId, images, turns, onChange }: { testId: string; images: PromptTestCase['images']; turns: number; onChange: (images: PromptTestCase['images']) => void }) {
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [over, setOver] = useState(false);
  const list = images ?? [];
  const idOk = /^[a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$/.test(testId);

  const upload = async (files: FileList | null) => {
    if (!files?.length) return;
    if (!idOk) {
      toast.error('Give the test a valid id (section 1) before adding images: they are stored under that id.', 'Set the test id first');
      return;
    }
    setBusy(true);
    try {
      const added: CaseImage[] = [];
      for (const f of Array.from(files)) {
        const up = await uploadTestImage(testId, f);
        added.push(turns > 1 ? { file: up.file, turn: 0 } : up.file);
      }
      onChange([...list, ...added]);
      toast.success(`${added.length} image${added.length === 1 ? '' : 's'} added. The image bytes become part of the test hash.`, 'Image added');
    } catch (e) {
      toast.error(e, 'Upload failed');
    } finally {
      setBusy(false);
      if (input.current) input.current.value = '';
    }
  };

  const setTurn = (i: number, turn: number) => onChange(list.map((x, j) => (j === i ? { file: refFile(x), turn } : x)));

  return (
    <div className="vi-builder">
      {list.length > 0 && (
        <div className="vi-builder-list">
          {list.map((x, i) => {
            const file = refFile(x);
            return (
              <div key={`${file}-${i}`} className="vi-builder-item">
                <VisionImage src={testImageUrl(imagePathFor(CUSTOM_FILE, file))} name={file.split('/').pop() ?? file} size="sm" caption={file} />
                <div className="row" style={{ gap: 6, marginTop: 6 }}>
                  {turns > 1 && (
                    <select className="select sm" aria-label="Send with turn" value={refTurn(x)} onChange={(e) => setTurn(i, Number(e.target.value))}>
                      {Array.from({ length: turns }, (_, k) => (
                        <option key={k} value={k}>
                          with turn {k + 1}
                        </option>
                      ))}
                    </select>
                  )}
                  <button type="button" className="btn xs ghost" onClick={() => onChange(list.filter((_, j) => j !== i).length ? list.filter((_, j) => j !== i) : undefined)}>
                    <Icon.Trash /> Remove
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
      <label
        className={cx('vi-drop', over && 'over')}
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setOver(false);
          void upload(e.dataTransfer.files);
        }}
      >
        <Icon.Upload style={{ width: 18, height: 18 }} />
        <span>
          {busy ? 'Uploading…' : list.length ? 'Add another image' : 'Show the model an image (optional)'} — drop a PNG or JPEG here or click to choose. Models without image input are skipped on this case.
        </span>
        <input ref={input} type="file" accept="image/png,image/jpeg" multiple onChange={(e) => void upload(e.target.files)} />
      </label>
    </div>
  );
}
