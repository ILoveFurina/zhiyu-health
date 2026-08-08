import flow from '../../../contracts/appointment-flow.json';

// 票 71：管理端直接从共享契约推导状态码、标签与叫号消息类型。
export const appointmentStatuses = flow.statuses;
export const appointmentStatusLabels = flow.status_labels;
export type AppointmentStatusCode = keyof typeof appointmentStatusLabels;
export const appointmentCalledMessageType = flow.called_notice.message_type;
