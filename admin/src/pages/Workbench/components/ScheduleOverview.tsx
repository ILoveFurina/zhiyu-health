import { Card, Col, Empty, Row, Statistic, Tag } from 'antd';
import type { ReceptionSchedule } from '@/services/reception';

export default function ScheduleOverview({ schedules }: { schedules: ReceptionSchedule[] }) {
  if (!schedules.length) {
    return <Card title="今日排班"><Empty description="今日暂无排班" image={Empty.PRESENTED_IMAGE_SIMPLE} /></Card>;
  }

  return (
    <Card title="今日排班">
      <Row gutter={[16, 16]}>
        {schedules.map((schedule) => (
          <Col xs={24} md={12} xl={8} key={schedule.id}>
            <Card size="small">
              <Statistic
                title={<>{schedule.time_slot} <Tag color={schedule.status === 'FULL' ? 'orange' : schedule.active ? 'green' : 'default'}>{schedule.status === 'FULL' ? '已约满' : schedule.active ? '出诊' : '停诊'}</Tag></>}
                value={schedule.total_slots - schedule.remaining_slots}
                suffix={`/ ${schedule.total_slots} 人`}
              />
            </Card>
          </Col>
        ))}
      </Row>
    </Card>
  );
}
