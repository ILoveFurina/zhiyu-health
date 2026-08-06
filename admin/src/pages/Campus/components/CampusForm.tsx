import { ModalForm, ProFormDigit, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { createCampus, listHospitals, updateCampus, type Campus } from '@/services/organization';

interface Props {
  open: boolean;
  record?: Campus;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function CampusForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<Omit<Campus, 'id'>>
      title={record ? '编辑院区' : '新建院区'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnClose: true, forceRender: true }}
      onFinish={async (values) => {
        if (record) {
          await updateCampus(record.id, values);
        } else {
          await createCampus(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormSelect
        name="hospital_id"
        label="所属医院"
        rules={[{ required: true, message: '请选择所属医院' }]}
        request={async () => (await listHospitals()).map((h) => ({ label: h.name, value: h.id }))}
      />
      <ProFormText name="name" label="院区名称" rules={[{ required: true, message: '请输入院区名称' }]} />
      <ProFormText name="city_code" label="城市代码" rules={[{ required: true, message: '请输入城市代码' }]} />
      <ProFormText name="city_name" label="城市名称" rules={[{ required: true, message: '请输入城市名称' }]} />
      <ProFormText name="address" label="地址" rules={[{ required: true, message: '请输入地址' }]} />
      <ProFormDigit name="longitude" label="经度" />
      <ProFormDigit name="latitude" label="纬度" />
      <ProFormText name="floor" label="楼层" />
      <ProFormTextArea name="materials" label="就诊材料" />
      <ProFormTextArea name="precautions" label="就诊注意事项" />
    </ModalForm>
  );
}
