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

/**
 * 人性化相对时间：1 分钟内「刚刚」，1 小时内「N 分钟前」，今天内「N 小时前」，
 * 昨天「昨天 HH:mm」，前天「前天 HH:mm」，更早「MM-DD HH:mm」，跨年「YYYY-MM-DD」。
 * 用于列表场景让创建时间一眼可读；hover 可看完整时间（由调用方补 title）。
 */
export function formatRelativeTime(iso?: string | null): string {
  const d = toLocal(iso);
  if (!d) return iso || '-';
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return '刚刚';
  if (diffMin < 60) return `${diffMin} 分钟前`;
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 1) return `${diffMin} 分钟前`;
  // 同一天：N 小时前
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return `${diffHour} 小时前`;
  // 昨天 / 前天
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const dayBefore = new Date(now);
  dayBefore.setDate(now.getDate() - 2);
  if (d.toDateString() === dayBefore.toDateString()) return `前天 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  // 同年：MM-DD HH:mm
  if (d.getFullYear() === now.getFullYear()) {
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  // 跨年：YYYY-MM-DD
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
