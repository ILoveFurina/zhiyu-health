// 票 55：在线问诊主闭环常量。与 contracts/online-consultation.json 的
// statuses/draft_statuses/progress_steps/consult_methods/sender_types/texts 等对齐。
// 端侧无法读契约 JSON，此文件是 miniprogram 侧的本地镜像；契约变更须同步更新。

// 预问诊专用对话场景值：server-java 校验草稿归属与状态后强制使用，客户端不得自由指定
const SCENARIO = 'preconsultation'

const DRAFT_STATUSES = {
  collecting: 'COLLECTING',
  pending_confirm: 'PENDING_CONFIRM',
  submitted: 'SUBMITTED',
}

const DRAFT_STATUS_LABELS = {
  COLLECTING: '病情收集',
  PENDING_CONFIRM: '待确认',
  SUBMITTED: '已提交',
}

const STATUSES = {
  waiting_doctor: 'WAITING_DOCTOR',
  in_progress: 'IN_PROGRESS',
  completed: 'COMPLETED',
  cancelled: 'CANCELLED',
  expired: 'EXPIRED',
}

const STATUS_LABELS = {
  WAITING_DOCTOR: '等待医生接诊',
  IN_PROGRESS: '医生问诊中',
  COMPLETED: '问诊已完成',
  CANCELLED: '已取消',
  EXPIRED: '已失效',
}

// 每份健康档案同时最多一条处于这些状态的在线问诊；CANCELLED/EXPIRED 可复用原摘要重新提交
const ACTIVE_STATUSES = ['WAITING_DOCTOR', 'IN_PROGRESS']

// C 端固定展示的跨端五步进度：前两步来自预问诊草稿，后三步来自在线问诊单；
// CANCELLED/EXPIRED 为终态分支，不占进度步
const PROGRESS_STEPS = [
  { key: 'PRECONSULTATION', label: 'AI 预问诊' },
  { key: 'SUMMARY_CONFIRMED', label: '患者确认摘要' },
  { key: 'WAITING_DOCTOR', label: '等待医生接诊' },
  { key: 'IN_PROGRESS', label: '医生问诊中' },
  { key: 'COMPLETED', label: '问诊已完成' },
]

const CONSULT_METHODS = {
  text: 'TEXT',
  video: 'VIDEO',
}

const CONSULT_METHOD_LABELS = {
  TEXT: '图文问诊',
  VIDEO: '视频问诊',
}

// 医患消息发送者类型；SYSTEM 用于医生接受、视频发起、问诊完成等系统通知消息
const SENDER_TYPES = {
  patient: 'PATIENT',
  doctor: 'DOCTOR',
  system: 'SYSTEM',
}

// 医患消息类型（票 58）：text=文字（含语音输入 ASR 转出的文字）；image=患者图片消息，
// content 存 {"object_key","media_type"} JSON。语音不构成消息类型，问诊记录不留语音。
const MESSAGE_KINDS = {
  text: 'text',
  image: 'image',
}

// 在线问诊单创建后默认接诊截止时间；超时惰性收敛为 EXPIRED，端内不得散落硬编码
const ACCEPT_TIMEOUT_SECONDS = 600

const SUMMARY_FIELDS = ['chief_complaint', 'present_illness', 'allergy_history']

const SUMMARY_FIELD_LABELS = {
  chief_complaint: '主诉',
  present_illness: '现病史',
  allergy_history: '过敏史',
}

const TEXTS = {
  waiting_matching: '正在为您匹配{department}的医生，请稍候…',
  expired_hint: '等待超时，暂无医生接诊，问诊单已失效。',
  cancelled_hint: '问诊已取消。',
  resubmit_hint: '可复用原病情摘要重新提交，无需重复预问诊。',
  doctor_accepted: '医生已接受问诊',
  video_started: '医生发起视频问诊（模拟）',
  consult_completed: '问诊已完成',
  profile_required: '请先创建健康档案并选择当前服务对象',
  department_unresolved: '请继续完善预问诊信息，暂未确定建议科室',
  summary_required: '请先与 AI 完成预问诊病情摘要',
  scenario_requires_draft: '预问诊场景需要有效的预问诊草稿',
  accept_conflict: '该问诊单已被其他医生接受',
  not_waiting: '问诊单不在等待接诊状态',
  not_in_progress: '问诊不在进行中',
  text_started: '医生发起图文问诊',
  method_already_set: '接诊方式已发起，不可更换',
  method_required: '医生尚未发起接诊方式',
}

/** waiting_matching 文案的 {department} 插值；科室名缺失时退化为"合适"。 */
function waitingMatchingText(departmentName) {
  return TEXTS.waiting_matching.replace('{department}', departmentName || '合适')
}

/** 终态提示：优先后端 terminal_hint，缺失时按状态回退契约文案。 */
function terminalHintFor(consultation) {
  if (!consultation) return ''
  if (consultation.terminal_hint) return consultation.terminal_hint
  if (consultation.status === STATUSES.expired) return TEXTS.expired_hint
  if (consultation.status === STATUSES.cancelled) return TEXTS.cancelled_hint
  return ''
}

module.exports = {
  SCENARIO,
  DRAFT_STATUSES,
  DRAFT_STATUS_LABELS,
  STATUSES,
  STATUS_LABELS,
  ACTIVE_STATUSES,
  PROGRESS_STEPS,
  CONSULT_METHODS,
  CONSULT_METHOD_LABELS,
  SENDER_TYPES,
  MESSAGE_KINDS,
  ACCEPT_TIMEOUT_SECONDS,
  SUMMARY_FIELDS,
  SUMMARY_FIELD_LABELS,
  TEXTS,
  waitingMatchingText,
  terminalHintFor,
}
