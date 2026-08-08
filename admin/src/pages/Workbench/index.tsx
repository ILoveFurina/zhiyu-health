import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Tabs } from 'antd';
import {
  completeAppointment,
  callAppointment,
  fetchAppointmentDetail,
  fetchReceptionDashboard,
  type AppointmentDetail,
  type ReceptionDashboard,
} from '@/services/reception';
import ConsultationDrawer from './components/ConsultationDrawer';
import OnlineConsultationPanel from './components/OnlineConsultationPanel';
import { createPrescription, fetchMedications, type Medication, type PrescriptionInput } from '@/services/prescription';
import ReceptionQueue from './components/ReceptionQueue';
import ScheduleOverview from './components/ScheduleOverview';
import PageHead from '@/components/PageHead';
import TemplateManageDrawer from './components/TemplateManageDrawer';

export default function WorkbenchPage() {
  const { message } = App.useApp();
  const [dashboard, setDashboard] = useState<ReceptionDashboard>();
  const [detail, setDetail] = useState<AppointmentDetail>();
  const [open, setOpen] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [medications, setMedications] = useState<Medication[]>([]);
  const [prescriptionSubmitting, setPrescriptionSubmitting] = useState(false);
  const [prescriptionCreated, setPrescriptionCreated] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);

  const loadDashboard = useCallback(async () => {
    setDashboard(await fetchReceptionDashboard());
  }, []);

  useEffect(() => { loadDashboard().catch(() => {}); }, [loadDashboard]);

  const openAppointment = async (id: number) => {
    setOpen(true);
    setDetail(undefined);
    setLoadingDetail(true);
    setPrescriptionCreated(false);
    try {
      const [appointment, medicationOptions] = await Promise.all([
        fetchAppointmentDetail(id), fetchMedications(),
      ]);
      setDetail(appointment);
      setMedications(medicationOptions);
    } finally {
      setLoadingDetail(false);
    }
  };

  const prescribe = async (values: PrescriptionInput) => {
    if (!detail) return;
    setPrescriptionSubmitting(true);
    try {
      await createPrescription(detail.appointment.id, values);
      setPrescriptionCreated(true);
      message.success('电子处方已提交审核');
    } finally {
      setPrescriptionSubmitting(false);
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

  const call = async (id: number) => {
    await callAppointment(id);
    await loadDashboard();
    message.success('叫号通知已发送');
  };

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="医生接诊台"
        description={`${dashboard?.date ?? '今日'} · 仅展示当前医生的排班与挂号患者`}
        tags={['排班概览', '挂号队列']}
      />
      <Tabs
        destroyOnHidden
        items={[
          {
            key: 'offline',
            label: '线下接诊',
            children: (
              <>
                <div style={{ marginBottom: 16, textAlign: 'right' }}>
                  <Button onClick={() => setTemplateOpen(true)}>处方模板</Button>
                </div>
                <ScheduleOverview schedules={dashboard?.schedules ?? []} />
                <div style={{ height: 16 }} />
                <ReceptionQueue appointments={dashboard?.appointments ?? []} onOpen={openAppointment} onCall={call} />
              </>
            ),
          },
          { key: 'online', label: '在线问诊', children: <OnlineConsultationPanel /> },
        ]}
      />
      <ConsultationDrawer open={open} loading={loadingDetail} submitting={submitting}
        detail={detail} medications={medications} prescriptionSubmitting={prescriptionSubmitting}
        prescriptionCreated={prescriptionCreated} onPrescribe={prescribe}
        onClose={() => setOpen(false)} onSubmit={complete} />
      <TemplateManageDrawer open={templateOpen} onClose={() => setTemplateOpen(false)} />
    </PageContainer>
  );
}
