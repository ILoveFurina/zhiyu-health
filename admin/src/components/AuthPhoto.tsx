import { useEffect, useState } from 'react';
import { Image, Spin, Typography } from 'antd';
import { getToken } from '@/utils/session';

interface Props {
  // MinIO object key（形如 photos/2026-08-09/abc.jpg）
  objectKey: string;
  width?: number;
  // 加载失败/无图时的占位文案
  fallback?: string;
}

/**
 * B 端鉴权图片（问诊图片回看）。
 *
 * 图片走鉴权代理 GET /api/b/reception/photos，<img>/Image 无法带 Bearer header，
 * 故先 fetch blob 再用 createObjectURL 渲染；object_key 不存在或 404 时回落占位。
 */
export default function AuthPhoto({ objectKey, width = 220, fallback = '图片暂不可查看' }: Props) {
  const [src, setSrc] = useState<string>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let url: string | undefined;
    let cancelled = false;
    setLoading(true);
    fetch(`/api/b/reception/photos?key=${encodeURIComponent(objectKey)}`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    })
      .then((resp) => (resp.ok ? resp.blob() : undefined))
      .then((blob) => {
        if (cancelled) return;
        if (blob) {
          url = URL.createObjectURL(blob);
          setSrc(url);
        } else {
          setSrc(undefined);
        }
      })
      .catch(() => {
        if (!cancelled) setSrc(undefined);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      // 释放 blob URL，避免内存泄漏
      if (url) URL.revokeObjectURL(url);
    };
  }, [objectKey]);

  if (loading) {
    return <Spin size="small" style={{ width, height: 120 }} />;
  }
  if (!src) {
    return <Typography.Text type="secondary">{fallback}</Typography.Text>;
  }
  return <Image src={src} width={width} style={{ borderRadius: 8 }} />;
}
