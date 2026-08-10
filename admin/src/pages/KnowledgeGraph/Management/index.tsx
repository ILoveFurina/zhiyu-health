import { PageContainer } from '@ant-design/pro-components';
import { Tabs } from 'antd';
import { useSearchParams } from '@umijs/max';
import PageHead from '@/components/PageHead';
import NodesPanel from './components/NodesPanel';
import EdgesPanel from './components/EdgesPanel';

/**
 * 图谱管理页（票 91）：在线维护 Neo4j 医学图谱的节点与关系。
 * 编辑范围限 contracts/graph-management.json 白名单（Symptom/Disease/Department
 * 节点 + INDICATES/TREATED_BY/SUGGESTS_DEPARTMENT 关系）；Medication/Contraindication
 * 及药品相关关系不在线编辑，继续走 PG + seed 离线链路。
 * G6 可视化页保持只读，仅经 ?keyword=<name> 跳转本页定位节点。
 */
export default function GraphManagementPage() {
  const [searchParams] = useSearchParams();
  // G6 可视化页「编辑」跳转携带的定位参数：节点页签按名称预过滤
  const initialKeyword = searchParams.get('keyword') ?? '';

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="图谱管理"
        description="在线维护医学知识图谱的症状、疾病、科室节点及其关系；药品与禁忌节点仍走离线 seed 链路"
        tags={['白名单编辑', '节点管理', '关系管理']}
      />
      <div style={{ background: '#fff', padding: '0 16px 16px', borderRadius: 8 }}>
        <Tabs
          items={[
            {
              key: 'nodes',
              label: '节点',
              children: <NodesPanel initialKeyword={initialKeyword} />,
            },
            { key: 'edges', label: '关系', children: <EdgesPanel /> },
          ]}
        />
      </div>
    </PageContainer>
  );
}
