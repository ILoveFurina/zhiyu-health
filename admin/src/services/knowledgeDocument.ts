import { request } from '@umijs/max';
import { getToken } from '@/utils/session';

export interface KnowledgeDocument {
  id: number;
  file_name: string;
  content_type: string;
  byte_size: number;
  source: 'SEED' | 'UPLOAD';
  status: 'PROCESSING' | 'READY' | 'FAILED' | 'ARCHIVED';
  department: string | null;
  chunk_count: number;
  error_code: string | null;
  error_message: string | null;
  created_at: string;
  updated_at: string;
}

export interface UploadResponse {
  id: number;
  status: string;
}

export function listKnowledgeDocuments() {
  return request<KnowledgeDocument[]>('/api/b/knowledge-documents');
}

export function retryKnowledgeDocument(id: number) {
  return request<UploadResponse>(`/api/b/knowledge-documents/${id}/retry`, { method: 'POST' });
}

export function archiveKnowledgeDocument(id: number) {
  return request<UploadResponse>(`/api/b/knowledge-documents/${id}/archive`, { method: 'POST' });
}

// antd Upload 的 customRequest 不走 Umi request 拦截器，须手动带 Bearer
export function uploadKnowledgeDocument(file: File, department: string): Promise<UploadResponse> {
  const form = new FormData();
  form.append('file', file);
  form.append('department', department);
  return fetch('/api/b/knowledge-documents', {
    method: 'POST',
    headers: { Authorization: `Bearer ${getToken()}` },
    body: form,
  }).then(async (resp) => {
    if (!resp.ok) {
      const detail = await resp.json().catch(() => ({}));
      throw new Error(typeof detail.detail === 'string' ? detail.detail : '文档上传失败');
    }
    return resp.json() as Promise<UploadResponse>;
  });
}
