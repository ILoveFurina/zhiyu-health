import { useEffect, useState } from 'react';
import { Image, Spin } from 'antd';
import { getToken } from '@/utils/session';

interface Props {
  // MinIO object key（形如 photos/2026-08-07/abc.jpg）；为空表示无照片
  objectKey?: string;
  size?: number;
}

/**
 * 医生照片缩略图（票 54）。
 *
 * B 端图片走鉴权代理 GET /api/b/photos，<img>/Image 无法带 Bearer header，
 * 故先 fetch blob 再用 createObjectURL 渲染。object_key 不存在或 404 时回落占位。
 */
export default function DoctorPhoto({ objectKey, size = 40 }: Props) {
  const [src, setSrc] = useState<string>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!objectKey) {
      setSrc(undefined);
      return;
    }
    let url: string | undefined;
    let cancelled = false;
    setLoading(true);
    fetch(`/api/b/photos?key=${encodeURIComponent(objectKey)}`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    })
      .then((resp) => {
        if (!resp.ok) return undefined;
        return resp.blob();
      })
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
    return <Spin size="small" style={{ width: size, height: size, lineHeight: `${size}px` }} />;
  }
  if (!src) {
    return <span style={{ color: 'var(--zy-muted)' }}>-</span>;
  }
  return <Image src={src} width={size} height={size} style={{ borderRadius: 6, objectFit: 'cover' }} />;
}
