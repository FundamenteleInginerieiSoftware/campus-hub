import { apiFetch } from './client.ts'
import { nowInBucharest } from './reservations.ts'

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

export type TimeRangePreset = '7d' | '30d' | '90d'

export const DAYS_OF_WEEK = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
]
// Saturday and Sunday are left out: capacity counts only Mon-Fri open hours, so weekend cells would have no capacity to compare against.

export const HOURS_RANGE = Array.from({ length: 12 }, (_, i) => i + 8) // 8..19

/**
 * Analytics window on the campus clock (Europe/Bucharest, same helper as the booking pages),
 * so an admin in another time zone still gets the campus "today".
 * It ends at "now", so today's future confirmed bookings are not counted as used hours.
 */
export function getDateWindow(preset: TimeRangePreset): { from: string; to: string } {
  const toDate = nowInBucharest()
  const fromDate = new Date(toDate)
  const days = preset === '7d' ? 7 : preset === '90d' ? 90 : 30
  // today + the (days - 1) days before it = exactly N calendar days
  fromDate.setDate(toDate.getDate() - (days - 1))
  fromDate.setHours(8, 0, 0, 0)

  return {
    from: formatLocalIsoDateTime(fromDate),
    to: formatLocalIsoDateTime(toDate),
  }
}

/** Builds a CSV of the per-asset rows and makes the browser download it. */
export function downloadUtilizationCsv(assets: AssetUtilizationItem[], from: string, to: string) {
  // Quote every value so commas or quotes in asset names don't break the columns.
  const quote = (v: unknown) => `"${String(v).replace(/"/g, '""')}"`
  const header = ['assetId', 'name', 'model', 'status', 'bookedHours', 'utilizationPercent', 'bookingCount', 'idle']
  const rows = assets.map((a) =>
    [a.assetId, a.name, a.model, a.status, a.bookedHours, a.utilizationPercent, a.bookingCount, a.idle].map(quote).join(','),
  )
  const csv = [header.join(','), ...rows].join('\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `utilization_${from.slice(0, 10)}_${to.slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

export function getCellColor(intensity: number, count: number): string {
  if (count === 0) return 'transparent'
  if (intensity < 0.25) return 'rgba(59, 130, 246, 0.18)'
  if (intensity < 0.5) return 'rgba(59, 130, 246, 0.40)'
  if (intensity < 0.75) return 'rgba(249, 115, 22, 0.55)'
  return 'rgba(239, 68, 68, 0.85)'
}

export function getCellTextColor(intensity: number, count: number): string {
  if (count === 0) return 'var(--mantine-color-dimmed)'
  if (intensity >= 0.75) return '#ffffff'
  return 'var(--mantine-color-text)'
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
