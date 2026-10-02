export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const API_TOKEN = import.meta.env.VITE_API_TOKEN ?? ''
const localUserId = (() => {
  if (typeof localStorage === 'undefined') return ''
  const existing = localStorage.getItem('dreamloop-user-id')
  if (existing) return existing
  const created = crypto.randomUUID?.() ?? `user-${Date.now().toString(36)}`
  localStorage.setItem('dreamloop-user-id', created)
  return created
})()

export const identityHeaders: Record<string, string> = API_TOKEN
  ? { Authorization: `Bearer ${API_TOKEN}` }
  : {
      'X-User-Id': import.meta.env.VITE_USER_ID ?? (localUserId || 'default'),
      'X-Workspace-Id': import.meta.env.VITE_WORKSPACE_ID ?? 'default',
    }

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly payload?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...identityHeaders,
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init?.headers,
    },
  })

  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    const message =
      typeof payload === 'object' && payload && 'error' in payload
        ? String(payload.error)
        : `请求失败（${response.status}）`
    throw new ApiError(message, response.status, payload)
  }

  return payload as T
}
