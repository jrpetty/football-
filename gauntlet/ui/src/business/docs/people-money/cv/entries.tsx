import type { DocEntry } from '../types.ts';
import { readCvQuestion, shortlistQuestion } from '../model/cv.ts';
import { CvReadBeats, CvReadInline } from './CvRead.tsx';
import { ShortlistBeats, ShortlistInline } from './Shortlist.tsx';

export const CV_READ: DocEntry = {
  family: 'extraction',
  caption: 'The screening form as the model filled it in, beside the CV. Numbered highlighter marks show where each answer comes from: green right, red wrong with the correct value beside it.',
  build: (input) => (readCvQuestion(input.turns[0] ?? '', input.notes) ? input : null),
  render: (_m, mode, input) => <CvReadInline input={input} mode={mode} />,
  Beats: CvReadBeats,
};

export const CV_SHORTLIST: DocEntry = {
  family: 'extraction',
  caption: 'Every applicant as a card stamped with the model’s decision, each essential requirement ticked or crossed. Red outlines mark wrong decisions: a good candidate lost, an unqualified one shortlisted, a hidden instruction obeyed.',
  build: (input) => (shortlistQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <ShortlistInline input={input} mode={mode} />,
  Beats: ShortlistBeats,
};
