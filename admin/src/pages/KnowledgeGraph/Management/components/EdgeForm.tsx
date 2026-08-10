import { ModalForm, ProFormSelect } from '@ant-design/pro-components';
import { Form } from 'antd';
import { useEffect } from 'react';
import { createGraphEdge, type GraphEdgeInput } from '@/services/graphManagement';
import {
  graphEdgeEndpoints,
  graphEdgeTypeNames,
  graphEdgeTypes,
  graphNodeLabelNames,
  type GraphEdgeType,
} from '@/contracts/graphManagement';
import NodeSelect from './NodeSelect';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

/**
 * 新建关系表单（票 91）：
 * - 关系类型下拉限白名单三类；
 * - 选定类型后，两端节点按 contracts 白名单约束 label（如 INDICATES 只能
 *   症状 -> 疾病），选择器远程搜索时自动带上 label 过滤；
 * - 关系不可编辑，修改 = 删除后重建。
 */
export default function EdgeForm({ open, onOpenChange, onSuccess }: Props) {
  const [form] = Form.useForm<GraphEdgeInput>();
  const type = Form.useWatch('type', form) as GraphEdgeType | undefined;
  const endpoints = type ? graphEdgeEndpoints[type] : undefined;

  // 切换关系类型后两端 label 约束变化，清空已选节点避免越白名单提交
  useEffect(() => {
    form.setFieldsValue({ from_node_id: undefined, to_node_id: undefined });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type]);

  return (
    <ModalForm<GraphEdgeInput>
      form={form}
      title="新建关系"
      open={open}
      onOpenChange={(o) => {
        if (!o) form.resetFields();
        onOpenChange(o);
      }}
      modalProps={{ destroyOnClose: false }}
      onFinish={async (values) => {
        await createGraphEdge(values);
        onSuccess();
        return true;
      }}
    >
      <ProFormSelect
        name="type"
        label="关系类型"
        options={graphEdgeTypes.map((t) => ({ value: t, label: graphEdgeTypeNames[t] }))}
        rules={[{ required: true, message: '请选择关系类型' }]}
      />
      <Form.Item
        name="from_node_id"
        label={`起点节点${endpoints ? `（${graphNodeLabelNames[endpoints.from_label]}）` : ''}`}
        rules={[{ required: true, message: '请选择起点节点' }]}
      >
        <NodeSelect
          nodeLabel={endpoints?.from_label}
          placeholder={endpoints ? `搜索${graphNodeLabelNames[endpoints.from_label]}名称` : '请先选择关系类型'}
          disabled={!endpoints}
        />
      </Form.Item>
      <Form.Item
        name="to_node_id"
        label={`终点节点${endpoints ? `（${graphNodeLabelNames[endpoints.to_label]}）` : ''}`}
        rules={[{ required: true, message: '请选择终点节点' }]}
      >
        <NodeSelect
          nodeLabel={endpoints?.to_label}
          placeholder={endpoints ? `搜索${graphNodeLabelNames[endpoints.to_label]}名称` : '请先选择关系类型'}
          disabled={!endpoints}
        />
      </Form.Item>
    </ModalForm>
  );
}
