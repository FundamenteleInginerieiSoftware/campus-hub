export const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

// Formats "2026-11-03T14:00:00" (or "2026-11-03") from its calendar fields so browser timezone never shifts the day.
export function formatSlotDate(isoString: string): string {
  if (!isoString) return ''
  const [datePart] = isoString.split('T')
  const [yearStr, monthStr, dayStr] = datePart.split('-')
  const year = Number(yearStr)
  const month = Number(monthStr) - 1
  const day = Number(dayStr)
  const dateObj = new Date(year, month, day)
  const dayName = DAY_NAMES[dateObj.getDay()]
  const monthName = MONTH_NAMES[month]
  return `${dayName}, ${day} ${monthName} ${year}`
}

export function formatSlotTime(startTime: string, endTime: string): string {
  const startPart = startTime.split('T')[1]?.slice(0, 5) ?? ''
  const endPart = endTime.split('T')[1]?.slice(0, 5) ?? ''
  return `${startPart} – ${endPart}`
}
