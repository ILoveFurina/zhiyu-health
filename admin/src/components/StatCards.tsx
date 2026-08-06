import { Col, Row } from 'antd';

export interface StatItem {
  label: string;
  value: number | string;
  suffix?: string;
}

/**
 * 统计卡行：4 列响应式网格，对齐 option-a 静态页 .stats 样式。
 * 各页从已加载的表格数据实时计算，无新增后端接口。
 */
export default function StatCards({ items }: { items: StatItem[] }) {
  return (
    <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
      {items.map((item) => (
        <Col xs={24} sm={12} xl={6} key={item.label}>
          <div className="zy-stat-card">
            <div className="zy-stat-label">{item.label}</div>
            <div className="zy-stat-value">
              {item.value}
              {item.suffix && <small>{item.suffix}</small>}
            </div>
          </div>
        </Col>
      ))}
    </Row>
  );
}
