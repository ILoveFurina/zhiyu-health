import { Tag } from 'antd';

/**
 * 医院等级彩色标签，对齐 option-a 静态页 .tag-blue / .tag-gold 配色：
 * 以「三」开头（三甲/三级甲等等）-> 蓝，以「二」开头 -> 金，其余默认灰。
 * 兼容演示数据「三级甲等 / 三级乙等」与静态页「三甲 / 三乙」两种写法。可复用组件。
 */
function levelColor(level: string): string {
  if (!level) return 'default';
  if (level.startsWith('三')) return 'blue';
  if (level.startsWith('二')) return 'gold';
  return 'default';
}

export default function LevelTag({ level }: { level: string }) {
  return <Tag color={levelColor(level)}>{level}</Tag>;
}