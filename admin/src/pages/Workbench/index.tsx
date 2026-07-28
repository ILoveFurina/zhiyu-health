import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Space, Typography } from 'antd';
import {
  completeAppointment,
  fetchAppointmentDetail,
  fetchReceptionDashboard,
  type AppointmentDetail,
  type ReceptionDashboard,
} from '@/services/reception';
import ConsultationDrawer from './components/ConsultationDrawer';
import ReceptionQueue from './components/ReceptionQueue';
import ScheduleOverview from './components/ScheduleOverview';

export default function WorkbenchPage() {
  const { message } = App.useApp();
  const [dashboard, setDashboard] = useState<ReceptionDashboard>();
  const [detail, setDetail] = useState<AppointmentDetail>();
  const [open, setOpen] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const loadDashboard = useCallback(async () => {
    setDashboard(await fetchReceptionDashboard());
  }, []);

  useEffect(() => { loadDashboard().catch(() => {}); }, [loadDashboard]);

  const openAppointment = async (id: number) => {
    setOpen(true);
    setDetail(undefined);
    setLoadingDetail(true);
    try {
      setDetail(await fetchAppointmentDetail(id));
    } finally {
      setLoadingDetail(false);
    }
  };

  const complete = async (values: { diagnosis: string; advice: string }) => {
    if (!detail) return;
    setSubmitting(true);
    try {
      setDetail(await completeAppointment(detail.appointment.id, values));
      await loadDashboard();
      message.success('已完成接诊');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageContainer title="医生接诊台">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Typography.Text type="secondary">{dashboard?.date ?? '今日'} · 仅展示当前医生的排班与挂号患者</Typography.Text>
        <ScheduleOverview schedules={dashboard?.schedules ?? []} />
        <ReceptionQueue appointments={dashboard?.appointments ?? []} onOpen={openAppointment} />
      </Space>
      <ConsultationDrawer open={open} loading={loadingDetail} submitting={submitting}
        detail={detail} onClose={() => setOpen(false)} onSubmit={complete} />
    </PageContainer>
  );
}
