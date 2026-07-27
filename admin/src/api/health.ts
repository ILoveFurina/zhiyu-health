export type ServiceName = 'postgres' | 'redis' | 'neo4j'
export type ServiceStatus = 'ok' | 'error'

export interface HealthResponse {
  status: 'ok' | 'degraded'
  services: Record<ServiceName, { status: ServiceStatus }>
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) {
    throw new Error(`Health API returned ${response.status}`)
  }
  return response.json() as Promise<HealthResponse>
}
