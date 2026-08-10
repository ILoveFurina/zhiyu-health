import { useEffect, useState } from 'react';
import { Select, Spin } from 'antd';
import { listGraphNodes } from '@/services/graphManagement';
import { graphNodeLabelNames, type GraphNodeLabel } from '@/contracts/graphManagement';

interface NodeOption {
  value: string;
  label: string;
}

interface Props {
  /** 限定节点 label（关系表单按契约约束两端 label）；不限时搜全部三类 */
  nodeLabel?: GraphNodeLabel;
  placeholder?: string;
  disabled?: boolean;
  value?: string;
  onChange?: (value?: string) => void;
}

/**
 * 可搜索的图谱节点选择器（票 91）：远程调 GET /nodes 按名称模糊搜索。
 * 已选中的选项会与历次搜索结果合并保留，避免搜索刷新后已选项丢失回显。
 */
export default function NodeSelect({ nodeLabel, placeholder, disabled, value, onChange }: Props) {
  const [options, setOptions] = useState<NodeOption[]>([]);
  const [fetching, setFetching] = useState(false);

  const load = async (keyword?: string) => {
    setFetching(true);
    try {
      const res = await listGraphNodes({
        label: nodeLabel,
        keyword: keyword || undefined,
        page: 1,
        size: 20,
      });
      const next = res.items.map((n) => ({
        value: n.node_id,
        label: `${n.name}（${graphNodeLabelNames[n.label]}）`,
      }));
      setOptions((prev) => {
        const merged = new Map(prev.map((o) => [o.value, o]));
        next.forEach((o) => merged.set(o.value, o));
        return [...merged.values()];
      });
    } finally {
      setFetching(false);
    }
  };

  // label 限制变化（如切换关系类型后两端 label 随之变化）时清空旧选项并重新拉取
  useEffect(() => {
    setOptions([]);
    load().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeLabel]);

  return (
    <Select
      showSearch
      allowClear
      style={{ width: '100%' }}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      disabled={disabled}
      filterOption={false}
      onSearch={(kw) => load(kw).catch(() => {})}
      onDropdownVisibleChange={(open) => {
        if (open && options.length === 0) load().catch(() => {});
      }}
      notFoundContent={fetching ? <Spin size="small" /> : undefined}
      options={options}
    />
  );
}
