import {
  IconAlertTriangle,
  IconArrowsExchange,
  IconBox,
  IconCalendarEvent,
  IconClipboardList,
  IconLayoutGrid,
  type Icon,
} from '@tabler/icons-react'
import type { Role } from '../api/auth.ts'

export type NavItem = { label: string; path: string; icon: Icon }
export type NavGroup = { title: string; items: NavItem[] }

// The side menu per role, in the same groups and order as the prototype. When you add a page,
// add its route in App.tsx and, if it belongs in the menu, a line here.
const STUDENT_MENU: NavGroup[] = [
  { title: 'Equipment', items: [{ label: 'Catalog', path: '/catalog', icon: IconLayoutGrid }] },
  {
    title: 'Mine',
    items: [
      { label: 'My reservations', path: '/reservations', icon: IconCalendarEvent },
      { label: 'My incidents', path: '/incidents', icon: IconAlertTriangle },
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
    ],
  },
  {
    title: 'Equipment',
    items: [
      { label: 'Catalog', path: '/catalog', icon: IconLayoutGrid },
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
