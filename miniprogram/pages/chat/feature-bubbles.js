/**
 * 功能入口气泡配置（票 19 决策 D5）。
 *
 * 气泡栏是 C 端聊天的场景功能入口；输入栏 `+` 另作统一图片入口。每项含 enabled 开关，
 * 后续拍照票（14/15/16/17）落地时只改对应项 enabled:true 并接上 action 即可，
 * 不动气泡栏本体与渲染逻辑。action 为字符串标识，由 chat 页 dispatch 分发。
 *
 * 不进后端、不进全局 config--纯 UI 可见性，非业务能力开关。
 */
const FEATURE_BUBBLES = [
  {
    key: 'triage',
    // 票 62：与首页主卡/宫格入口统一命名「智能导诊」
    label: '智能导诊',
    icon: 'plus',
    enabled: true,
    action: 'triage',
  },
  {
    key: 'drugGuide',
    // 票 94：与首页宫格入口统一命名「便捷购药」，进对话购药引导卡
    label: '便捷购药',
    icon: 'capsule',
    enabled: true,
    action: 'drugGuide',
  },
  {
    key: 'consult',
    label: '在线问诊',
    icon: 'consult',
    enabled: true,
    action: 'consult',
  },
  // 「找医院」气泡已于票 49 移除：自助挂号收敛到 AI挂号助手主卡与首页宫格，
  // 不再作为对话内自助入口；Agent 侧医院推荐卡（hospital-card）由票 50 接管
  {
    key: 'report',
    label: '看报告',
    icon: 'report',
    enabled: true,
    action: 'report',
  },
  // 拍照类入口随各自票单落地再点亮；未落地前 enabled:false 不渲染（D8 可插拔纪律）
  { key: 'pillbox', label: '拍药盒', icon: 'capsule', enabled: true, action: 'pillbox' },
  { key: 'skin', label: '拍皮肤', icon: 'scan', enabled: true, action: 'skin' },
  { key: 'diet', label: '拍饮食', icon: 'diet', enabled: true, action: 'diet' },
  { key: 'tongue', label: '拍舌苔', icon: 'tongue', enabled: true, action: 'tongue' },
  // 票 51：「查药品」入口已隐藏（与拍药盒能力重复且挤占气泡栏）；
  // 文字版能力经 chat 信封 medication_name 保留（拍药盒识别后自动携带）
]

/** 仅暴露已点亮气泡，供 axml a:for 渲染。 */
function visibleBubbles() {
  return FEATURE_BUBBLES.filter((b) => b.enabled)
}

module.exports = { FEATURE_BUBBLES, visibleBubbles }
