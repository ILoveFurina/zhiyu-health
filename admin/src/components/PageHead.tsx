/**
 * 页面头：标题 / 简介 / 标签垂直堆叠，对齐 option-a 静态页 .page-head。
 * 替代 PageContainer 的 title/subTitle/tags（那三者同行且会与路由名重复出现标题）。
 */
export interface PageHeadProps {
  title: string;
  description: string;
  tags?: string[];
}

export default function PageHead({ title, description, tags }: PageHeadProps) {
  return (
    <div className="zy-page-head">
      <h1>{title}</h1>
      <p>{description}</p>
      {tags && tags.length > 0 && (
        <div className="zy-chips">
          {tags.map((t) => (
            <span key={t} className="zy-chip">{t}</span>
          ))}
        </div>
      )}
    </div>
  );
}
