import {
  IconAlertTriangle,
  IconArrowsExchange,
  IconBox,
  IconBulb,
  IconCalendarEvent,
  IconClipboardList,
  IconHistory,
  IconLayoutGrid,
  IconQrcode,
  type Icon,
} from '@tabler/icons-react'
import type { Role } from '../api/auth.ts'

export type NavItem = { label: string; path: string; icon: Icon }
export type NavGroup = { title: string; items: NavItem[] }

// The side menu per role, in the same groups and order as the prototype. When you add a page,
// add its route in App.tsx and, if it belongs in the menu, a line here.
const STUDENT_MENU: NavGroup[] = [
  {
    title: 'Equipment',
    items: [
      { label: 'Catalog', path: '/catalog', icon: IconLayoutGrid },
      { label: 'Projects', path: '/projects', icon: IconBulb },
    ],
  },
  {
    title: 'Mine',
    items: [
      { label: 'My reservations', path: '/reservations', icon: IconCalendarEvent },
      { label: 'My incidents', path: '/incidents', icon: IconAlertTriangle },
      { label: 'Pickup pass', path: '/pickup-pass', icon: IconQrcode },
    ],
  },
]

const ADMIN_MENU: NavGroup[] = [
  {
    title: 'Operations',
    items: [
      { label: 'Reservations desk', path: '/admin/reservations', icon: IconClipboardList },
      { label: 'Incident queue', path: '/admin/incidents', icon: IconAlertTriangle },
      { label: 'Reassignment log', path: '/admin/reassignments', icon: IconArrowsExchange },
      { label: 'Audit log', path: '/admin/audit-logs', icon: IconHistory },
    ],
  },
  {
    title: 'Equipment',
    items: [
      { label: 'Catalog', path: '/catalog', icon: IconLayoutGrid },
      { label: 'Projects', path: '/projects', icon: IconBulb },
      { label: 'Inventory', path: '/admin/inventory', icon: IconBox },
    ],
  },
]

export function menuFor(role: Role): NavGroup[] {
  return role === 'ADMIN' ? ADMIN_MENU : STUDENT_MENU
}

// Title shown in the top bar for the current address.
export function titleFor(pathname: string): string {
  for (const group of [...ADMIN_MENU, ...STUDENT_MENU]) {
    const item = group.items.find((entry) => pathname.startsWith(entry.path))
    if (item) {
      return item.label
    }
  }
  return 'Campus Hub'
}
