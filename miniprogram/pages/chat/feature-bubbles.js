/**
 * 功能入口气泡配置（票 19 决策 D5）。
 *
 * 气泡栏是 C 端聊天的唯一功能入口（输入栏 `+` 已拆除）。每项含 enabled 开关，
 * 后续拍照票（14/15/16/17）落地时只改对应项 enabled:true 并接上 action 即可，
 * 不动气泡栏本体与渲染逻辑。action 为字符串标识，由 chat 页 dispatch 分发。
 *
 * 不进后端、不进全局 config--纯 UI 可见性，非业务能力开关。
 */
const FEATURE_BUBBLES = [
  {
    key: 'triage',
    label: 'AI 诊室',
    icon: '✚',
    enabled: true,
    action: 'triage',
  },
  {
    key: 'hospital',
    label: '找医院',
    icon: '⚑',
    enabled: true,
    action: 'hospital',
  },
  {
    key: 'report',
    label: '看报告',
    icon: '▤',
    enabled: true,
    action: 'report',
  },
  // 拍照类入口随各自票单落地再点亮；未落地前 enabled:false 不渲染（D8 可插拔纪律）
  { key: 'pillbox', label: '拍药盒', icon: '⊗', enabled: false, action: 'pillbox' },
  { key: 'skin', label: '拍皮肤', icon: '⊙', enabled: true, action: 'skin' },
  { key: 'diet', label: '拍饮食', icon: '✦', enabled: false, action: 'diet' },
  { key: 'tongue', label: '拍舌苔', icon: '◐', enabled: false, action: 'tongue' },
]

/** 仅暴露已点亮气泡，供 axml a:for 渲染。 */
function visibleBubbles() {
  return FEATURE_BUBBLES.filter((b) => b.enabled)
}

module.exports = { FEATURE_BUBBLES, visibleBubbles }
