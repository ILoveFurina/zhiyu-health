/**
 * 票 52：轻量 Markdown 解析器，供 AI 对话气泡按块渲染。
 * 只覆盖健康对话高频语法：行首 `#`~`######` 标题（级别压到 1-3）、
 * `**加粗**` 行内分段、`- `/`* ` 无序列表、`1. `/`1、 ` 有序列表。
 * 表格、代码围栏、未闭合 `**` 等一律按纯文本兜底，不报错。
 * parseMarkdown(text) -> [{ type: 'heading'|'paragraph'|'list_item', level?, ordered?, index?, segments: [{text, bold}] }]
 */

/** 行内解析：按 `**bold**` 切分段落，未闭合的 `**` 保留为字面文本。 */
function parseInline(text) {
  const segments = []
  const re = /\*\*(.+?)\*\*/g
  let last = 0
  let match
  while ((match = re.exec(text)) !== null) {
    if (match.index > last) segments.push({ text: text.slice(last, match.index), bold: false })
    segments.push({ text: match[1], bold: true })
    last = match.index + match[0].length
  }
  if (last < text.length) segments.push({ text: text.slice(last), bold: false })
  if (segments.length === 0) segments.push({ text: '', bold: false })
  return segments
}

function parseMarkdown(text) {
  if (!text) return []
  const blocks = []
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    // 空行仅作分段间隔，由 acss 块间距体现，不产出空块
    if (!trimmed) continue
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (heading) {
      blocks.push({
        type: 'heading',
        level: Math.min(heading[1].length, 3),
        segments: parseInline(heading[2]),
      })
      continue
    }
    const unordered = /^[-*]\s+(.*)$/.exec(trimmed)
    if (unordered) {
      blocks.push({ type: 'list_item', ordered: false, segments: parseInline(unordered[1]) })
      continue
    }
    const ordered = /^(\d+)[.、]\s+(.*)$/.exec(trimmed)
    if (ordered) {
      blocks.push({
        type: 'list_item',
        ordered: true,
        index: ordered[1],
        segments: parseInline(ordered[2]),
      })
      continue
    }
    blocks.push({ type: 'paragraph', segments: parseInline(trimmed) })
  }
  return blocks
}

module.exports = { parseMarkdown }
