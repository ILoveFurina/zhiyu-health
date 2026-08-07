// 时间展示工具：后端一律返回 ISO 8601 UTC（如 2026-08-07T18:07:55.472938Z），
// 端上转本地时区再展示；解析失败兜底回显原文，避免空值/脏值渲染成 "-" 造成误导。

const pad = (n: number) => String(n).padStart(2, '0');

const toLocal = (iso?: string | null): Date | null => {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
};

/** 完整时间：YYYY-MM-DD HH:mm，用于表格与详情描述。 */
export function formatDateTime(iso?: string | null): string {
  const d = toLocal(iso);
  if (!d) return iso || '-';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 紧凑时间：MM-DD HH:mm，用于对话气泡/消息流，避免重复年份噪音。 */
export function formatChatTime(iso?: string | null): string {
  const d = toLocal(iso);
  if (!d) return iso || '';
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
