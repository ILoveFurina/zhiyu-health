import { useEffect } from 'react';
import { ModalForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { Form } from 'antd';
import {
  createGraphNode,
  updateGraphNode,
  type GraphNodeInput,
  type GraphNodeItem,
} from '@/services/graphManagement';
import {
  graphEditableProperties,
  graphNodeLabelNames,
  graphNodeLabels,
} from '@/contracts/graphManagement';

interface Props {
  open: boolean;
  record?: GraphNodeItem;
  onOpenChange: (open: boolean) => void;
  /** 提交成功后回调，携带响应中的 rag_chunk_count 与节点名（供父级弹 RAG 对齐提示） */
  onSuccess: (ragChunkCount: number | null, nodeName: string) => void;
}

/**
 * 节点新建/编辑表单（票 91）：
 * - label 下拉限白名单三类，创建后不可改（label 是 node_id 前缀的一部分）；
 * - aliases/description 按 contracts 白名单的 editable_properties 决定显隐
 *   （Symptom 无 description，Department 无 aliases）；
 * - 重名 409 由全局 errorHandler 弹 detail，ModalForm 保持打开。
 */
export default function NodeForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<GraphNodeInput>();
  const label = Form.useWatch('label', form);

  const editable = label ? (graphEditableProperties[label] ?? []) : [];
  const showAliases = editable.includes('aliases');
  const showDescription = editable.includes('description');

  // label 切换后清掉该 label 不可编辑的字段，避免残留值被误提交
  useEffect(() => {
    if (!label) return;
    const cleared: Partial<GraphNodeInput> = {};
    if (!showAliases) cleared.aliases = undefined;
    if (!showDescription) cleared.description = undefined;
    form.setFieldsValue(cleared);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [label]);

  return (
    <ModalForm<GraphNodeInput>
      form={form}
      title={record ? '编辑节点' : '新建节点'}
      open={open}
      onOpenChange={(o) => {
        if (o) {
          form.setFieldsValue(record ?? {});
        } else {
          form.resetFields();
        }
        onOpenChange(o);
      }}
      modalProps={{ destroyOnClose: false }}
      onFinish={async (values) => {
        if (record) {
          const res = await updateGraphNode(record.node_id, {
            name: values.name,
            aliases: values.aliases,
            description: values.description,
          });
          onSuccess(res.rag_chunk_count, values.name);
        } else {
          const res = await createGraphNode(values);
          onSuccess(res.rag_chunk_count, values.name);
        }
        return true;
      }}
    >
      <ProFormSelect
        name="label"
        label="节点类型"
        options={graphNodeLabels.map((l) => ({ value: l, label: graphNodeLabelNames[l] }))}
        disabled={!!record}
        rules={[{ required: true, message: '请选择节点类型' }]}
      />
      <ProFormText
        name="name"
        label="名称"
        rules={[{ required: true, message: '请输入名称' }]}
      />
      {showAliases && (
        <ProFormSelect
          name="aliases"
          label="别名"
          mode="tags"
          placeholder="输入后回车添加别名"
          fieldProps={{ open: false, suffixIcon: null }}
        />
      )}
      {showDescription && <ProFormTextArea name="description" label="描述" />}
    </ModalForm>
  );
}
