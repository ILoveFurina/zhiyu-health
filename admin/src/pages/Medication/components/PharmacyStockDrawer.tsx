import { useEffect, useState } from 'react';
import { Alert, App, Drawer, Spin, Table, Typography } from 'antd';
import { fetchPharmacyStock, type PharmacyStockItem, type PharmacyStockView } from '@/services/demo';

interface Props {
  open: boolean;
  /** 每次成功同步 +1：抽屉已打开时重复同步也能刷新快照。 */
  refreshKey: number;
  onClose: () => void;
}

const itemColumns = [
  { title: '药品名称', dataIndex: 'medication_name' },
  { title: '规格', dataIndex: 'specification' },
  { title: '药店库存', dataIndex: 'stock', width: 96 },
];

/** Mock 药店库存快照抽屉：各虚构药店库存明细 + 上次同步时间（票 48 演示展示层）。 */
export default function PharmacyStockDrawer({ open, refreshKey, onClose }: Props) {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [view, setView] = useState<PharmacyStockView>();

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    fetchPharmacyStock()
      .then(setView)
      .catch(() => message.error('药店库存快照加载失败'))
      .finally(() => setLoading(false));
    // refreshKey 仅作刷新信号，不参与取值
  }, [open, refreshKey]);

  return (
    <Drawer title="药店库存快照" width={560} open={open} onClose={onClose} destroyOnClose>
      <Spin spinning={loading}>
        <Alert
          type="info"
          showIcon
          message="演示数据：以下为虚构合作药店库存快照，仅作展示，未与平台自营药房库存打通。"
          style={{ marginBottom: 16 }}
        />
        <Typography.Paragraph>
          上次同步时间：
          {view?.last_synced_at ? new Date(view.last_synced_at).toLocaleString() : '未同步'}
        </Typography.Paragraph>
        {view?.pharmacies.map((pharmacy) => (
          <div key={pharmacy.name} style={{ marginBottom: 24 }}>
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {pharmacy.name}
              <Typography.Text type="secondary" style={{ marginLeft: 8, fontWeight: 'normal' }}>
                {pharmacy.region}
              </Typography.Text>
            </Typography.Title>
            <Table<PharmacyStockItem>
              rowKey={(item) => `${pharmacy.name}-${item.medication_name}`}
              columns={itemColumns}
              dataSource={pharmacy.items}
              pagination={false}
              size="small"
            />
          </div>
        ))}
      </Spin>
    </Drawer>
  );
}
