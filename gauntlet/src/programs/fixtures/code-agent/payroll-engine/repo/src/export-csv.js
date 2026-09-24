'use strict';

const { formatCents } = require('./money');

const COLUMNS = ['employee', 'name', 'period_start', 'period_end', 'regular_hours', 'overtime_hours', 'gross', 'pension', 'tax', 'union_dues', 'net'];

/** RFC 4180 quoting: wrap in quotes when needed and double any embedded quotes. */
function csvField(value) {
  const s = String(value);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function toCsv(run) {
  const lines = [COLUMNS.join(',')];
  for (const p of run.payslips) {
    const row = [
      p.employeeId,
      p.name,
      p.period.start,
      p.period.end,
      p.regularHours,
      p.overtimeHours,
      formatCents(p.gross).replace(/,/g, ''),
      formatCents(p.pension).replace(/,/g, ''),
      formatCents(p.tax).replace(/,/g, ''),
      formatCents(p.unionDues).replace(/,/g, ''),
      formatCents(p.net).replace(/,/g, ''),
    ];
    lines.push(row.map(csvField).join(','));
  }
  return lines.join('\n') + '\n';
}

/** Split one CSV line into fields (inverse of csvField). Used by the bank-file importer. */
function parseCsvLine(line) {
  const fields = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      fields.push(field);
      field = '';
    } else {
      field += ch;
    }
  }
  fields.push(field);
  return fields;
}

/** Parse an exported payroll CSV back into plain objects keyed by column name. */
function fromCsv(text) {
  const [header, ...rows] = text.trim().split('\n');
  const columns = parseCsvLine(header);
  return rows.map((row) => {
    const fields = parseCsvLine(row);
    return Object.fromEntries(columns.map((c, i) => [c, fields[i] ?? '']));
  });
}

module.exports = { toCsv, csvField, parseCsvLine, fromCsv, COLUMNS };
