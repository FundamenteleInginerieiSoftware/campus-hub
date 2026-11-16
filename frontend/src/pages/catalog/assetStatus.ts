import type { AssetStatus } from '../../api/catalog.ts'

export function statusBadgeColor(status: AssetStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'teal'
    case 'UNDER_MAINTENANCE':
      return 'yellow'
    case 'RETIRED':
      return 'gray'
    default:
      return 'blue'
  }
}
