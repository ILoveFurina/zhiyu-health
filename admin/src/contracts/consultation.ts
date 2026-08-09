import contract from '../../../contracts/online-consultation.json';

export const consultationStatuses = contract.statuses;
export const consultationStatusLabels = contract.status_labels;
export const draftStatuses = contract.draft_statuses;
export const draftStatusLabels = contract.draft_status_labels;
export const progressSteps = contract.progress_steps;
export const consultMethods = contract.consult_methods;
export const consultMethodLabels = contract.consult_method_labels;
export const senderTypes = contract.sender_types;
// 票 58：医患消息类型（text=文字含语音输入转出文字；image=患者图片消息，content 为 {"object_key","media_type"} JSON）
export const messageKinds = contract.message_kinds;
export const acceptTimeoutSeconds = contract.accept_timeout_seconds;
// 票 86：固定时长窗（自医生接受起计时），B 端问诊抽屉倒计时用
export const consultationDurationSeconds = contract.consultation_duration_seconds;
export const summaryFieldLabels = contract.summary_field_labels;
export const consultationTexts = contract.texts;
