import { Alert, Button, Descriptions, Divider, Drawer, Form, Input, Space, Spin, Tag, Typography } from 'antd';
import type { AppointmentDetail } from '@/services/reception';
import type { Medication, PrescriptionInput } from '@/services/prescription';
import PrescriptionForm from './PrescriptionForm';

interface Props {
  open: boolean;
  loading: boolean;
  submitting: boolean;
  detail?: AppointmentDetail;
  onClose: () => void;
  onSubmit: (values: { diagnosis: string; advice: string }) => Promise<void>;
  medications: Medication[];
  prescriptionSubmitting: boolean;
  prescriptionCreated: boolean;
  onPrescribe: (values: PrescriptionInput) => Promise<void>;
}

export default function ConsultationDrawer(props: Props) {
  const { open, loading, submitting, detail, onClose, onSubmit, medications,
    prescriptionSubmitting, prescriptionCreated, onPrescribe } = props;
  const appointment = detail?.appointment;
  const completed = appointment?.status === '已接诊';

  return (
    <Drawer title="接诊详情" width={560} open={open} onClose={onClose} destroyOnHidden>
      <Spin spinning={loading}>
        {appointment && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="患者">{appointment.patient_nickname}</Descriptions.Item>
              <Descriptions.Item label="序号">{appointment.sequence_number} 号</Descriptions.Item>
              <Descriptions.Item label="时段">{appointment.time_slot}</Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color={completed ? 'green' : 'blue'}>{appointment.status}</Tag></Descriptions.Item>
            </Descriptions>
            <Alert
              type="info"
              showIcon
              message="AI 病情摘要"
              description={
                <Space direction="vertical">
                  <Typography.Paragraph style={{ margin: 0 }}>
                    {appointment.condition_summary || '患者本次挂号暂无病情摘要。'}
                  </Typography.Paragraph>
                  <Typography.Text strong type="warning">{appointment.summary_disclaimer}</Typography.Text>
                </Space>
              }
            />
            <Divider orientation="left">开具电子处方</Divider>
            {prescriptionCreated ? (
              <Alert type="success" showIcon message="电子处方已提交，等待管理员审核" />
            ) : (
              <PrescriptionForm medications={medications} submitting={prescriptionSubmitting} onSubmit={onPrescribe} />
            )}
            {completed ? (
              <Descriptions title="接诊记录" column={1} bordered>
                <Descriptions.Item label="诊断结论">{detail?.diagnosis}</Descriptions.Item>
                <Descriptions.Item label="医嘱">{detail?.advice}</Descriptions.Item>
              </Descriptions>
            ) : (
              <Form layout="vertical" onFinish={onSubmit}>
                <Form.Item name="diagnosis" label="诊断结论"
                  rules={[{ required: true, whitespace: true, message: '请填写诊断结论' }, { max: 2000 }]}>
                  <Input.TextArea rows={4} placeholder="填写医生诊断结论" />
                </Form.Item>
                <Form.Item name="advice" label="医嘱"
                  rules={[{ required: true, whitespace: true, message: '请填写医嘱' }, { max: 2000 }]}>
                  <Input.TextArea rows={4} placeholder="填写后续治疗、复诊或生活建议" />
                </Form.Item>
                <Button type="primary" htmlType="submit" loading={submitting}>完成接诊</Button>
              </Form>
            )}
          </Space>
        )}
      </Spin>
    </Drawer>
  );
}
