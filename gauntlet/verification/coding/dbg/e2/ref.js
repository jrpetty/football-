function smartTruncate(text, maxLen) {
  const cps = Array.from(text);
  if (cps.length <= maxLen) return text;
  if (maxLen < 1) return '';
  const head = cps.slice(0, maxLen - 1);
  while (head.length && /^\s$/.test(head[head.length - 1])) head.pop();
  return head.join('') + '…';
}
