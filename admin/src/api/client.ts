const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8000/api'

export function getToken() {
  return localStorage.getItem('staff_token') ?? ''
}

export function setToken(token: string) {
  localStorage.setItem('staff_token', token)
}

export function clearToken() {
  localStorage.removeItem('staff_token')
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken()
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.detail ?? '请求失败')
  }
  if (response.status === 204) return undefined as T
  return response.json()
}
