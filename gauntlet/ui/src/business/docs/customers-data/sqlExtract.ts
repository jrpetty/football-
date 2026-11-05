/**
 * The model's SQL query from its reply, for the picture only (the scorer's own extraction lives in
 * src/scoring/sql.ts, which needs Node; the scorer records the query it ran as `detail.extracted`, used first).
 */
export function extractSqlCandidatesBrowser(reply: string): string[] {
  const blocks = [...reply.matchAll(/```([\w-]*)\s*\n([\s\S]*?)```/g)].map((m) => ({ lang: m[1]!.toLowerCase(), code: m[2]!.trim() }));
  const sql = blocks.filter((b) => b.lang === 'sql' || /^\s*\(?\s*(WITH|SELECT)\b/i.test(b.code));
  if (sql.length) return [sql[sql.length - 1]!.code];
  const m = reply.match(/(?:^|\n)\s*((?:WITH|SELECT)\b[\s\S]*?)(?:;|\n\s*\n|$)/i);
  return m ? [m[1]!.trim()] : [];
}

const KW = /^(?:WITH|SELECT|FROM|WHERE|GROUP|BY|ORDER|HAVING|LIMIT|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|ON|AND|OR|NOT|AS|IN|IS|NULL|CASE|WHEN|THEN|ELSE|END|DISTINCT|UNION|ALL|OVER|PARTITION|ROWS|BETWEEN|DESC|ASC|EXISTS|COALESCE|SUM|COUNT|AVG|MIN|MAX|ROUND|CAST|DATE|DATETIME|STRFTIME|JULIANDAY|IFNULL|NULLIF|RANK|DENSE_RANK|ROW_NUMBER|LAG|LEAD|RECURSIVE|LIKE|USING|FILTER|PRECEDING|FOLLOWING|CURRENT|ROW|UNBOUNDED|INTEGER|REAL|TEXT|ABS|SUBSTR|LENGTH|UPPER|LOWER|TRIM|REPLACE|PRINTF|MAX)$/i;

/** A one-line query set out over several lines (each clause on its own line); multi-line queries unchanged. */
export function layoutSql(sql: string): string {
  if (sql.includes('\n') || sql.length < 70) return sql;
  return sql.replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|UNION ALL|UNION|(?:LEFT |INNER |CROSS )?JOIN)\b/gi, '\n$1');
}

export interface SqlToken {
  k: 'kw' | 'str' | 'num' | 'com' | 'id' | 'pun' | 'ws';
  v: string;
}

/** A tiny SQL tokenizer for colouring. */
export function tokenizeSql(sql: string): SqlToken[] {
  const out: SqlToken[] = [];
  const re = /(--[^\n]*)|('(?:[^']|'')*'?)|("(?:[^"]|"")*"?)|(\s+)|(\d+(?:\.\d+)?)|([A-Za-z_][\w]*)|([^\s\w])/g;
  for (const m of sql.matchAll(re)) {
    if (m[1]) out.push({ k: 'com', v: m[1] });
    else if (m[2]) out.push({ k: 'str', v: m[2] });
    else if (m[3]) out.push({ k: 'id', v: m[3] });
    else if (m[4]) out.push({ k: 'ws', v: m[4] });
    else if (m[5]) out.push({ k: 'num', v: m[5] });
    else if (m[6]) out.push({ k: KW.test(m[6]) ? 'kw' : 'id', v: m[6] });
    else out.push({ k: 'pun', v: m[7]! });
  }
  return out;
}
