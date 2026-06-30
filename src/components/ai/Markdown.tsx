import type { ReactNode } from 'react'

// Minimal markdown renderer for AI output: ## / ### headings, - / * bullets,
// 1. ordered lists, **bold**, *italic*, `code`, and paragraphs. Intentionally
// small — no external dependency.
function inline(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  const re = /(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)/g
  let last = 0
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(text))) {
    if (m.index > last) nodes.push(text.slice(last, m.index))
    const tok = m[0]
    if (tok.startsWith('**')) nodes.push(<strong key={i++}>{tok.slice(2, -2)}</strong>)
    else if (tok.startsWith('`')) nodes.push(<code key={i++} className="mono" style={{ background: 'var(--bg-2)', padding: '1px 5px', borderRadius: 5 }}>{tok.slice(1, -1)}</code>)
    else nodes.push(<em key={i++}>{tok.slice(1, -1)}</em>)
    last = m.index + tok.length
  }
  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}

export function Markdown({ text }: { text: string }) {
  const lines = text.replace(/\r/g, '').split('\n')
  const blocks: ReactNode[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  let key = 0

  const flush = () => {
    if (!list) return
    const items = list.items.map((it, i) => <li key={i}>{inline(it)}</li>)
    blocks.push(
      list.ordered
        ? <ol key={key++} style={{ margin: '4px 0 10px', paddingLeft: 20, lineHeight: 1.55 }}>{items}</ol>
        : <ul key={key++} style={{ margin: '4px 0 10px', paddingLeft: 20, lineHeight: 1.55 }}>{items}</ul>,
    )
    list = null
  }

  for (const raw of lines) {
    const line = raw.trimEnd()
    if (!line.trim()) { flush(); continue }
    const h = line.match(/^(#{1,4})\s+(.*)$/)
    const bullet = line.match(/^[-*]\s+(.*)$/)
    const ordered = line.match(/^\d+\.\s+(.*)$/)
    if (h) {
      flush()
      const lvl = h[1].length
      blocks.push(
        <div key={key++} style={{ fontWeight: 800, fontSize: lvl <= 2 ? 14.5 : 13.5, marginTop: blocks.length ? 12 : 0, marginBottom: 4, color: lvl <= 2 ? 'var(--text)' : 'var(--text-dim)' }}>
          {inline(h[2])}
        </div>,
      )
    } else if (bullet) {
      if (!list || list.ordered) { flush(); list = { ordered: false, items: [] } }
      list.items.push(bullet[1])
    } else if (ordered) {
      if (!list || !list.ordered) { flush(); list = { ordered: true, items: [] } }
      list.items.push(ordered[1])
    } else {
      flush()
      blocks.push(<p key={key++} style={{ margin: '0 0 9px', lineHeight: 1.55 }}>{inline(line)}</p>)
    }
  }
  flush()
  return <div style={{ fontSize: 13.5 }}>{blocks}</div>
}
