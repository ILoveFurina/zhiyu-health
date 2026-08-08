// 票 56：处方来源类型常量。与 contracts/prescription-flow.json 的
// source_types/source_type_labels 对齐。
// 端侧无法读契约 JSON，此文件是 miniprogram 侧的本地镜像；契约变更须同步更新。

// 处方来源：经 appointment_id 或 online_consultation_id 两个真实外键二选一关联，
// 该枚举仅是服务端派生的展示值（数据库不落 source_type 列）
const SOURCE_TYPES = {
  appointment: 'APPOINTMENT',
  online_consultation: 'ONLINE_CONSULTATION',
}

const SOURCE_TYPE_LABELS = {
  APPOINTMENT: '线下接诊',
  ONLINE_CONSULTATION: '在线问诊',
}

// 处方审核状态（票 60）：与 contracts/prescription-flow.json 的 statuses 对齐。
// 展示文案直接使用 API 下发的 status_label，端侧不镜像 status_labels
const STATUSES = {
  pending: 'PENDING',
  approved: 'APPROVED',
  rejected: 'REJECTED',
}

module.exports = { SOURCE_TYPES, SOURCE_TYPE_LABELS, STATUSES }
