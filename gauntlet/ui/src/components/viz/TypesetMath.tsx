/** Plain maths text typeset with real superscripts, stacked fractions, √ and money highlights. */
import { mathSegments } from '../../../../src/presenter/visuals/maths.ts';

export function TypesetMath({ text, money = false }: { text: string; money?: boolean }) {
  return (
    <>
      {mathSegments(text).map((s, i) => {
        switch (s.t) {
          case 'text':
            return <span key={i}>{s.v}</span>;
          case 'sup':
            return <sup key={i}>{s.v}</sup>;
          case 'sub':
            return <sub key={i}>{s.v}</sub>;
          case 'sqrt':
            return (
              <span key={i} className="vz-sqrt">
                √<span>{s.v}</span>
              </span>
            );
          case 'frac':
            return (
              <span key={i} className="vz-frac" aria-label={`${s.num} over ${s.den}`}>
                <span>{s.num}</span>
                <span>{s.den}</span>
              </span>
            );
          case 'money':
            return money ? (
              <mark key={i} className="vz-money">
                {s.v}
              </mark>
            ) : (
              <span key={i}>{s.v}</span>
            );
        }
      })}
    </>
  );
}
