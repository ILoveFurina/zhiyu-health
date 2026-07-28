import { request } from './client'

export interface ScheduleInput {
  doctor_id: number
  schedule_date: string
  time_slot: string
  total_slots: number
}

export interface Schedule extends ScheduleInput {
  id: number
  remaining_slots: number
  is_active: boolean
}

export const scheduleApi = {
  list: () => request<Schedule[]>('/b/schedules'),
  create: (payload: ScheduleInput) => request<Schedule>('/b/schedules', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  disable: (id: number) => request<Schedule>(`/b/schedules/${id}/disable`, {
    method: 'PATCH',
  }),
}
