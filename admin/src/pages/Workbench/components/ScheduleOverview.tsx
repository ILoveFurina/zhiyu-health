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
            <Card
              size="small"
              // 非当前时段整卡置灰（票 87）：时段是否当前由后端 in_window 判定，
              // 与挂号队列 callable 同源，前端不复制时段表。
              style={!schedule.in_window ? { opacity: 0.5, background: '#fafafa', borderColor: '#d9d9d9' } : undefined}
            >
              <Statistic
                title={<>{schedule.time_slot} <Tag color={schedule.status === 'FULL' ? 'orange' : schedule.active ? 'green' : 'default'}>{schedule.status === 'FULL' ? '已约满' : schedule.active ? '出诊' : '停诊'}</Tag></>}
                value={schedule.total_slots - schedule.remaining_slots}
                suffix={`/ ${schedule.total_slots} 人`}
              />
              <div style={{
                marginTop: 8, paddingTop: 6, paddingBottom: 6, borderTop: '1px dashed #f0f0f0',
                textAlign: 'center', fontSize: 12, lineHeight: '20px', borderRadius: 4,
                ...(schedule.in_window
                  ? { color: '#52c41a', background: '#f6ffed' }
                  : { color: 'rgba(0,0,0,0.45)', background: '#f0f0f0' }),
              }}>
                {schedule.in_window ? '当前可叫号' : '非当前时段，不可叫号'}
              </div>
            </Card>
          </Col>
        ))}
      </Row>
    </Card>
  );
}
