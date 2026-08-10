import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  App, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, type TableColumnsType,
} from 'antd';
import { listCampuses, listHospitals, type Campus, type Hospital } from '@/services/organization';
import {
  addPharmacyMedication,
  deletePharmacyMedication,
  getCampusPharmacy,
  listPharmacyMedications,
  updateCampusPharmacy,
  updatePharmacyMedication,
  type CampusPharmacy,
  type PharmacyMedication,
} from '@/services/pharmacy';
import { listMedications, updateMedication, type Medication } from '@/services/medication';
import PageHead from '@/components/PageHead';

interface MedModalState {
  mode: 'add' | 'edit';
  record?: PharmacyMedication;
}

export default function PharmacyInventoryPage() {
  const { message, modal } = App.useApp();
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [hospitalId, setHospitalId] = useState<number>();
  const [campusId, setCampusId] = useState<number>();
  const [pharmacy, setPharmacy] = useState<CampusPharmacy>();
  const [meds, setMeds] = useState<PharmacyMedication[]>([]);
  const [keyword, setKeyword] = useState('');
  const [loadingMeds, setLoadingMeds] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [medModal, setMedModal] = useState<MedModalState>();
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [catalog, setCatalog] = useState<Medication[]>([]);
  const [configForm] = Form.useForm();
  const [medForm] = Form.useForm();

  const campusOptions = useMemo(
    () => campuses.filter((c) => c.hospital_id === hospitalId).map((c) => ({ value: c.id, label: c.name })),
    [campuses, hospitalId],
  );

  const loadMeds = useCallback(async (pharmacyId: number, kw?: string) => {
    setLoadingMeds(true);
    try {
      setMeds(await listPharmacyMedications(pharmacyId, kw));
    } finally {
      setLoadingMeds(false);
    }
  }, []);

  // 选定院区后定位该院区药房（每院区恰好一个，后端事务保证）
  useEffect(() => {
    if (!campusId) {
      setPharmacy(undefined);
      setMeds([]);
      return;
    }
    getCampusPharmacy(campusId).then(async (p) => {
      setPharmacy(p);
      await loadMeds(p.id);
    }).catch(() => {});
  }, [campusId, loadMeds]);

  useEffect(() => {
    Promise.all([listHospitals(), listCampuses()]).then(([hs, cs]) => {
      setHospitals(hs);
      setCampuses(cs);
    }).catch(() => {});
  }, []);

  const refreshMeds = async () => {
    if (pharmacy) await loadMeds(pharmacy.id, keyword || undefined);
  };

  const openConfig = () => {
    if (!pharmacy) return;
    configForm.setFieldsValue(pharmacy);
    setConfigOpen(true);
  };

  const submitConfig = async () => {
    if (!pharmacy) return;
    const values = await configForm.validateFields();
    const updated = await updateCampusPharmacy(pharmacy.id, values);
    setPharmacy(updated);
    setConfigOpen(false);
    message.success('药房配置已保存');
  };

  const openAdd = async () => {
    if (catalog.length === 0) {
      try {
        setCatalog(await listMedications());
      } catch {
        return;
      }
    }
    medForm.resetFields();
    medForm.setFieldsValue({ is_on_sale: true });
    setMedModal({ mode: 'add' });
  };

  const openEdit = (record: PharmacyMedication) => {
    medForm.resetFields();
    medForm.setFieldsValue({ price: record.price, stock: record.stock, is_on_sale: record.is_on_sale });
    setMedModal({ mode: 'edit', record });
  };

  const submitMed = async () => {
    if (!pharmacy || !medModal) return;
    const values = await medForm.validateFields();
    if (medModal.mode === 'add') {
      await addPharmacyMedication(pharmacy.id, values);
      message.success('已加入药房，刷新后即可供医生开方与患者查询');
    } else if (medModal.record) {
      await updatePharmacyMedication(medModal.record.id, values);
      message.success('药房药品已更新');
    }
    setMedModal(undefined);
    await refreshMeds();
  };

  const removeMed = (record: PharmacyMedication) => {
    modal.confirm({
      title: `删除药房药品「${record.name}」`,
      content: '仅未被历史处方或订单引用的药品可物理删除；已引用的请下架。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deletePharmacyMedication(record.id);
          message.success('已删除');
          await refreshMeds();
        } catch (error: any) {
          // 409：已有历史引用，只能下架（skipErrorHandler 后在此兜底提示）
          const detail = error?.response?.data?.detail;
          if (error?.response?.status === 409) {
            message.error(typeof detail === 'string' ? detail : '已有历史引用，请改为下架');
          } else {
            message.error('删除失败，请稍后重试');
          }
        }
      },
    });
  };

  // 新增药品弹窗的目录选项：排除已在该药房上架的药品，避免重复加入（后端唯一约束兜底）
  const catalogOptions = useMemo(() => {
    const existing = new Set(meds.map((m) => m.medication_id));
    return catalog
      .filter((m) => !existing.has(m.id))
      .map((m) => ({ value: m.id, label: `${m.name}（${m.specification}）${m.is_prescription ? '·处方药' : ''}` }));
  }, [catalog, meds]);

  const columns: TableColumnsType<PharmacyMedication> = [
    { title: '药品名称', dataIndex: 'name' },
    { title: '规格', dataIndex: 'specification', width: 140 },
    {
      title: '处方属性', dataIndex: 'is_prescription', width: 100,
      render: (value) => (value ? <Tag color="orange">处方药</Tag> : <Tag color="blue">OTC</Tag>),
    },
    { title: '价格(元)', dataIndex: 'price', width: 100, render: (value) => Number(value).toFixed(2) },
    { title: '库存', dataIndex: 'stock', width: 90 },
    {
      title: '在售状态', dataIndex: 'is_on_sale', width: 100,
      render: (value) => (value ? <Tag color="green">在售</Tag> : <Tag color="default">已下架</Tag>),
    },
    {
      title: '操作', width: 130,
      render: (_, row) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(row)}>编辑</Button>
          <Button type="link" size="small" danger onClick={() => removeMed(row)}>删除</Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="院区药房库存"
        description="按医院/院区定位院区药房，维护药房配置与药品价格、库存、在售状态"
        tags={['一院区一药房', '价格库存', '在售管理']}
      />
      <Card title="选择药房" style={{ marginBottom: 16 }}>
        <Space size={12} wrap>
          <Select
            placeholder="选择医院"
            style={{ width: 240 }}
            value={hospitalId}
            onChange={(value) => { setHospitalId(value); setCampusId(undefined); }}
            options={hospitals.map((h) => ({ value: h.id, label: h.name }))}
          />
          <Select
            placeholder="选择院区"
            style={{ width: 240 }}
            value={campusId}
            disabled={!hospitalId}
            onChange={setCampusId}
            options={campusOptions}
          />
        </Space>
      </Card>
      {pharmacy && (
        <>
          <Card
            title="药房配置"
            style={{ marginBottom: 16 }}
            extra={<Button onClick={openConfig}>编辑配置</Button>}
          >
            <Descriptions column={3} size="small" items={[
              { key: 'name', label: '药房展示名', children: pharmacy.display_name },
              { key: 'fee', label: '配送费(元)', children: Number(pharmacy.delivery_fee).toFixed(2) },
              { key: 'eta', label: '预计配送', children: `${pharmacy.estimated_delivery_minutes} 分钟` },
            ]} />
          </Card>
          <Card
            title="药房药品"
            extra={
              <Space>
                <Input.Search
                  placeholder="按药名搜索"
                  allowClear
                  style={{ width: 200 }}
                  onSearch={(value) => { setKeyword(value); void loadMeds(pharmacy.id, value || undefined); }}
                />
                <Button onClick={() => setCatalogOpen(true)}>标准药品目录</Button>
                <Button type="primary" onClick={openAdd}>新增药品</Button>
              </Space>
            }
          >
            <Table
              rowKey="id"
              loading={loadingMeds}
              columns={columns}
              dataSource={meds}
              pagination={{ pageSize: 10 }}
              locale={{ emptyText: '该药房尚未配置药品' }}
            />
          </Card>
        </>
      )}

      <Modal
        title="编辑药房配置"
        open={configOpen}
        onOk={submitConfig}
        onCancel={() => setConfigOpen(false)}
        destroyOnClose
      >
        <Form form={configForm} layout="vertical">
          <Form.Item name="display_name" label="药房展示名" rules={[{ required: true, message: '请输入药房展示名' }]}>
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="delivery_fee" label="配送费(元)" rules={[{ required: true, message: '请输入配送费' }]}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="estimated_delivery_minutes" label="预计配送分钟数" rules={[{ required: true, message: '请输入预计配送分钟数' }]}>
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={medModal?.mode === 'add' ? '新增药房药品' : `编辑「${medModal?.record?.name ?? ''}」`}
        open={!!medModal}
        onOk={submitMed}
        onCancel={() => setMedModal(undefined)}
        destroyOnClose
      >
        <Form form={medForm} layout="vertical">
          {medModal?.mode === 'add' && (
            <Form.Item name="medication_id" label="标准药品" rules={[{ required: true, message: '请选择药品' }]}>
              <Select
                showSearch
                placeholder="从标准药品目录搜索选择"
                optionFilterProp="label"
                options={catalogOptions}
              />
            </Form.Item>
          )}
          <Form.Item name="price" label="价格(元)" rules={[{ required: true, message: '请输入价格' }]}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="stock" label="库存" rules={[{ required: true, message: '请输入库存' }]}>
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="is_on_sale" label="在售" valuePropName="checked">
            <Switch checkedChildren="在售" unCheckedChildren="下架" />
          </Form.Item>
        </Form>
      </Modal>

      <CatalogDrawer
        open={catalogOpen}
        catalog={catalog}
        onClose={() => setCatalogOpen(false)}
        onLoaded={setCatalog}
      />
    </PageContainer>
  );
}

/** 标准药品目录维护抽屉：目录只承载名称/规格/处方属性，价格库存由各院区药房维护。 */
function CatalogDrawer({ open, catalog, onClose, onLoaded }: {
  open: boolean;
  catalog: Medication[];
  onClose: () => void;
  onLoaded: (list: Medication[]) => void;
}) {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    listMedications()
      .then(onLoaded)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [open, onLoaded]);

  const togglePrescription = async (record: Medication, checked: boolean) => {
    await updateMedication(record.id, { is_prescription: checked });
    onLoaded(catalog.map((m) => (m.id === record.id ? { ...m, is_prescription: checked } : m)));
    message.success('处方属性已更新');
  };

  return (
    <Drawer title="标准药品目录" width={720} open={open} onClose={onClose} destroyOnClose>
      <Typography.Paragraph type="secondary" style={{ fontSize: 13 }}>
        标准目录为全平台共享药品（名称/规格/处方属性）；价格、库存与在售状态在各院区药房维护。
      </Typography.Paragraph>
      <Table<Medication>
        rowKey="id"
        loading={loading}
        dataSource={catalog}
        pagination={{ pageSize: 10 }}
        size="small"
        columns={[
          { title: '药品名称', dataIndex: 'name' },
          { title: '规格', dataIndex: 'specification', width: 140 },
          {
            title: '处方药', dataIndex: 'is_prescription', width: 110,
            render: (value, record) => (
              <Switch
                checked={value}
                checkedChildren="Rx"
                unCheckedChildren="OTC"
                onChange={(checked) => togglePrescription(record, checked)}
              />
            ),
          },
        ]}
      />
    </Drawer>
  );
}
