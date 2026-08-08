import { request } from '@umijs/max';
import {
  scheduleRequestDecisions,
  scheduleRequestStatuses,
  scheduleRequestStatusLabels,
} from '@/contracts/scheduleRequest';

type ScheduleRequestStatus = (typeof scheduleRequestStatusLabels)[keyof typeof scheduleRequestStatusLabels];

export interface ScheduleRequest {
  id: number;
  doctor_id: number;
  schedule_date: string;
  time_slot: string;
  total_slots: number;
  action: string;
  target_schedule_id: number | null;
  status: ScheduleRequestStatus;
  submitted_by: number;
  reviewed_by: number | null;
  review_reason: string | null;
  schedule_id: number | null;
  created_at: string;
  reviewed_at: string | null;
  // 联查投影（审核列表/医生列表）
  doctor_name?: string;
  department_name?: string;
  title?: string;
}

export interface ScheduleRequestItemInput {
  schedule_date: string;
  time_slot: string;
  total_slots: number;
}

export interface SubmitScheduleRequestInput {
  doctor_id: number;
  items: ScheduleRequestItemInput[];
}

// 医生排班（排班表页面用）
export interface Schedule {
  id: number;
  doctor_id: number;
  schedule_date: string;
  time_slot: string;
  total_slots: number;
  remaining_slots: number;
  is_active: boolean;
  // 联查投影：该排班是否存在待审核的 DISABLE/ENABLE 申请（null 表示无待审核申请）
  pending_action?: string | null;
}

// 医生提交排班申请（reception 命名空间，AdminInterceptor 豁免，医生可达）
export const submitScheduleRequests = (data: SubmitScheduleRequestInput) =>
  request<ScheduleRequest[]>('/api/b/reception/schedule-requests', { method: 'POST', data });

// 医生查看自己的排班申请
export const fetchMyScheduleRequests = () =>
  request<ScheduleRequest[]>('/api/b/reception/schedule-requests/mine');

// 医生查看自己未来排班（排班表页面）
export const fetchMyScheduleTable = () =>
  request<Schedule[]>('/api/b/reception/schedule-table');

// 医生对已有排班发起调整号源/停诊申请
export const submitScheduleChange = (targetScheduleId: number, action: string, newTotalSlots?: number) =>
  request<ScheduleRequest>(`/api/b/reception/schedules/${targetScheduleId}/change-request`, {
    method: 'POST',
    data: { action, new_total_slots: newTotalSlots },
  });

// 管理员查看待审核列表（status 默认 PENDING）
export const fetchScheduleRequestsForReview = (status?: string) =>
  request<ScheduleRequest[]>('/api/b/schedule-requests', {
    params: status ? { status } : undefined,
  });

export type ScheduleReviewDecision = typeof scheduleRequestDecisions.approve | typeof scheduleRequestDecisions.reject;

// 管理员审核排班申请
export const reviewScheduleRequest = (id: number, decision: ScheduleReviewDecision, reason?: string) =>
  request<ScheduleRequest>(`/api/b/schedule-requests/${id}/review`, {
    method: 'POST',
    data: { decision, reason },
  });

export { scheduleRequestStatuses };
