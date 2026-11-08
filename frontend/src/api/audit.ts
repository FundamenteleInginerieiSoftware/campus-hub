import { apiFetch } from './client.ts'
import type { PageResponse } from './types.ts'

export type AuditLogEntry = {
  id: number
  actorId: number | null
  action: string
  entityType: string
  entityId: number | null
  details: string | null
  createdAt: string
}

export type AuditLogSearchParams = {
  actorId?: number
  action?: string
  entityType?: string
  entityId?: number
  from?: string
  to?: string
  page?: number
  size?: number
  sort?: string
}

// Queries GET /api/v1/admin/audit-logs with optional filter criteria and pagination.
export function searchAuditLogs(params: AuditLogSearchParams = {}): Promise<PageResponse<AuditLogEntry>> {
  const query = new URLSearchParams()

  if (params.actorId !== undefined) {
    query.set('actorId', String(params.actorId))
  }
  if (params.action && params.action.trim() !== '') {
    query.set('action', params.action.trim())
  }
  if (params.entityType && params.entityType.trim() !== '') {
    query.set('entityType', params.entityType.trim())
  }
  if (params.entityId !== undefined) {
    query.set('entityId', String(params.entityId))
  }
  if (params.from && params.from.trim() !== '') {
    query.set('from', params.from.trim())
  }
  if (params.to && params.to.trim() !== '') {
    query.set('to', params.to.trim())
  }

  query.set('page', String(params.page ?? 0))
  query.set('size', String(params.size ?? 20))
  query.set('sort', params.sort ?? 'createdAt,desc')

  return apiFetch<PageResponse<AuditLogEntry>>(`/api/v1/admin/audit-logs?${query.toString()}`)
}
