import { apiFetch } from './client.ts'

export type AssetUtilizationItem = {
  assetId: number
  name: string
  model: string
  categoryId: number
  bookedHours: number
  utilizationPercent: number
  bookingCount: number
  status: string
  idle: boolean
}

export type CapacityBottleneckItem = {
  model: string
  totalUnits: number
  peakConcurrentDemand: number
  saturatedEventsCount: number
  peakUtilizationRate: number
  recommendation: string
}

export type UtilizationAnalyticsResponse = {
  from: string
  to: string
  reportingDays: number
  totalBookedHours: number
  totalCapacityHours: number
  averageUtilizationPercent: number
  totalBookingsCount: number
  cancellationRate: number
  noShowRate: number
  totalAssetsTracked: number
  idleAssetsCount: number
  assets: AssetUtilizationItem[]
  bottlenecks: CapacityBottleneckItem[]
}

export type HeatmapCell = {
  dayOfWeek: number // 1 = Mon ... 7 = Sun
  hour: number // 8 .. 19
  bookingCount: number
  intensityScore: number // 0.0 to 1.0
}

export type HeatmapResponse = {
  from: string
  to: string
  assetId: number | null
  totalBookingsSampled: number
  peakHourlyDemand: number
  cells: HeatmapCell[]
}

/**
 * Formats a JavaScript Date to an ISO local string without timezone offset: "YYYY-MM-DDTHH:mm:ss".
 * Avoids passing Z or timezone offsets which are strictly rejected by the backend.
 */
export function formatLocalIsoDateTime(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  const seconds = pad(date.getSeconds())
  return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`
}

/**
 * Retrieves aggregate lab utilization metrics, idle asset lists, and capacity bottlenecks (GET /api/v1/admin/analytics/utilization).
 */
export function getUtilizationAnalytics(from?: string, to?: string): Promise<UtilizationAnalyticsResponse> {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return apiFetch<UtilizationAnalyticsResponse>(`/api/v1/admin/analytics/utilization${qs ? `?${qs}` : ''}`)
}

/**
 * Retrieves a 7-day x 12-hour hourly booking demand heatmap matrix (GET /api/v1/admin/analytics/heatmap).
 */
export function getDemandHeatmap(from?: string, to?: string, assetId?: number): Promise<HeatmapResponse> {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  if (assetId !== undefined && assetId !== null) params.set('assetId', String(assetId))
  const qs = params.toString()
  return apiFetch<HeatmapResponse>(`/api/v1/admin/analytics/heatmap${qs ? `?${qs}` : ''}`)
}
