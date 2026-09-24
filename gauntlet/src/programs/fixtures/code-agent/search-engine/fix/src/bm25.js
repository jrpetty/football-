'use strict';

const K1 = 1.2;
const B = 0.75;

/** Inverse document frequency (see README). */
function idf(docCount, docFreq) {
  return Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
}

/** BM25 contribution of one term to one document. */
function termScore({ tf, docLength, avgLength, idfValue }) {
  if (tf === 0) return 0;
  const norm = avgLength > 0 ? docLength / avgLength : 1;
  return (idfValue * tf * (K1 + 1)) / (tf + K1 * (1 - B + B * norm));
}

/** Score one document for a list of distinct query terms. */
function scoreDocument(index, docId, terms) {
  const avgLength = index.averageLength();
  const docLength = index.docLength(docId);
  let score = 0;
  for (const term of terms) {
    const tf = index.termFreq(term, docId);
    if (!tf) continue;
    score += termScore({ tf, docLength, avgLength, idfValue: idf(index.docCount, index.docFreq(term)) });
  }
  return score;
}

module.exports = { K1, B, idf, termScore, scoreDocument };
