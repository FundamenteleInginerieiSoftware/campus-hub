import {
  Alert,
  Badge,
  Button,
  Center,
  Group,
  Loader,
  Pagination,
  Paper,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core'
import { useMediaQuery } from '@mantine/hooks'
import { IconAlertTriangle, IconCheck, IconCircleCheckFilled, IconQrcode, IconRefresh } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { fetchAdminUser, type AdminUser } from '../api/adminUsers.ts'
import { errorMessage } from '../api/errorMessages.ts'
import { checkOutReservation, returnReservation, searchReservations } from '../api/reservationDesk.ts'
import type { VerifiedStudent } from '../api/pickupPass.ts'
import { fetchAssetSummary, type AssetSummary, type Reservation, type ReservationStatus } from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'
import { VerifiedMark, VerifyStudentDialog } from './ReservationsDeskParts.tsx'

type Tab = 'pickup' | 'out' | 'overdue'
type VerifyDialogTarget = { kind: 'any' } | { kind: 'student'; userId: number }

// Each tab is one status. Only "To pick up" uses the chosen day: equipment that is out or overdue
// has to come back whatever day it was booked for, so those tabs show all of it.
const TAB_STATUS: Record<Tab, ReservationStatus> = {
  pickup: 'CONFIRMED',
  out: 'CHECKED_OUT',
  overdue: 'OVERDUE',
}

const PAGE_SIZE = 20

// Today's date as "YYYY-MM-DD" in the lab's time zone, not the browser's ("en-CA" formats as YYYY-MM-DD).
// The backend's Clock uses Europe/Bucharest too, so "today" means the same day on both sides.
function todayInBucharest(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Bucharest' }).format(new Date())
}

// "2026-11-04T14:00:00" → "14:00". The backend sends local times without an offset,
// so we cut the string instead of using Date, which would shift it to the browser's zone.
function timeOf(iso: string): string {
  return iso.split('T')[1]?.slice(0, 5) ?? ''
}

function dayOf(iso: string): string {
  const [year, month, day] = iso.split('T')[0].split('-')
  return `${day}.${month}.${year}`
}

function searchFor(tab: Tab, date: string, page: number, userId?: number) {
  return searchReservations({
    status: TAB_STATUS[tab],
    userId,
    date: tab === 'pickup' ? date : undefined,
    page,
    size: PAGE_SIZE,
  })
}

export default function ReservationsDeskPage() {
  const [tab, setTab] = useState<Tab>('pickup')
  const [date, setDate] = useState(todayInBucharest)
  const [pageNumber, setPageNumber] = useState(0)
  const [pageData, setPageData] = useState<PageResponse<Reservation> | null>(null)
  const [counts, setCounts] = useState<Record<Tab, number | null>>({ pickup: null, out: null, overdue: null })
  const [assets, setAssets] = useState<Record<number, AssetSummary>>({})
  const [users, setUsers] = useState<Record<number, AdminUser>>({})
  // Ids of assets and users already loaded or being loaded. Refs, not state: the list effect reads
  // them after an await, and a ref always gives the current set there (a state value would be the
  // one from the render that started the request).
  const requestedAssetIds = useRef(new Set<number>())
  const requestedUserIds = useRef(new Set<number>())
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<unknown>(null)

  // Check-out / return: which row is busy, and what happened last.
  const [busyId, setBusyId] = useState<number | null>(null)
  const [actionError, setActionError] = useState<{ title: string; error: unknown } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  // Students whose pickup code was checked at the desk, by user id (kept until the page is left).
  const [verified, setVerified] = useState<Record<number, VerifiedStudent>>({})
  const [verifyTarget, setVerifyTarget] = useState<VerifyDialogTarget | null>(null)
  // Set after a top-bar "Scan student pass" succeeds: narrows both the table and the three tab counts.
  const [filteredStudent, setFilteredStudent] = useState<VerifiedStudent | null>(null)
  const filteredUserId = filteredStudent?.userId

  // Adding 1 re-runs both effects below (after an action or a click on "Refresh").
  const [reloadToken, setReloadToken] = useState(0)

  // Below Mantine's "sm" breakpoint (48em = 768px) the 5 columns don't fit: show one card per booking.
  const isPhone = useMediaQuery('(max-width: 48em)')

  // The rows of the open tab.
  useEffect(() => {
    // "ignore" drops the answer of a request the user has already moved away from
    // (other tab, day, or scanned student), so a slow old answer can't overwrite the new list.
    let ignore = false
    let steppingBack = false
    searchFor(tab, date, pageNumber, filteredUserId)
      .then(async (response) => {
        if (ignore) return

        // Bug fixed (#148): after a check-out or return of the only row on the last page, the reload asked
        // for a page that no longer exists, got no rows and showed "No equipment is out right now." with no
        // page buttons, while the tab still counted 20. Fix: step back to the last page that still exists
        // (page 0 if nothing is left). Changing pageNumber runs this effect again; loading stays on until then.
        if (response.content.length === 0 && pageNumber > 0 && pageNumber >= response.totalPages) {
          steppingBack = true
          setPageData(null) // the old rows hold the booking that was just handled; don't leave its button
          setPageNumber(Math.max(response.totalPages - 1, 0))
          return
        }

        // Bug fixed (#166): the backend sorted newest first across pages and we re-sorted only the 20 rows on
        // screen, so early bookings landed on the last page. Fix: ask the backend for ascending order
        // (sortDir=asc, Calin's #172, sent by searchReservations) and show the rows as they come.
        setPageData(response)
        setLoadError(null)

        // Asset names come from Liviu's public GET /api/v1/assets/{id} and student names from Stefania's
        // GET /api/v1/admin/users/{id}, one call per uncached id on the page. If one fails, that row
        // falls back to "Asset #id" / "Student #id" and the id is removed from the requested set so the
        // next load tries it again.
        // Bug fixed (#166): every tab switch, day change or refresh fetched every asset on the page again,
        // even the ones we already had. Fix: only fetch ids not asked for yet, and keep completed names
        // even if the user has moved on meanwhile (those ids are already marked as asked for, so no later
        // load would fetch them).
        const assetIds = [...new Set(response.content.map((r) => r.assetId))].filter(
          (id) => !requestedAssetIds.current.has(id),
        )
        const userIds = [...new Set(response.content.map((r) => r.userId))].filter(
          (id) => !requestedUserIds.current.has(id),
        )
        assetIds.forEach((id) => requestedAssetIds.current.add(id))
        userIds.forEach((id) => requestedUserIds.current.add(id))

        const [foundAssets, foundUsers] = await Promise.all([
          Promise.all(
            assetIds.map((id) =>
              fetchAssetSummary(id).catch(() => {
                requestedAssetIds.current.delete(id)
                return null
              }),
            ),
          ),
          Promise.all(
            userIds.map((id) =>
              fetchAdminUser(id).catch(() => {
                requestedUserIds.current.delete(id)
                return null
              }),
            ),
          ),
        ])

        const validAssets = foundAssets.filter((a): a is AssetSummary => a !== null)
        if (validAssets.length > 0) {
          setAssets((prev) => {
            const next = { ...prev }
            for (const asset of validAssets) {
              next[asset.id] = asset
            }
            return next
          })
        }

        const validUsers = foundUsers.filter((u): u is AdminUser => u !== null)
        if (validUsers.length > 0) {
          setUsers((prev) => {
            const next = { ...prev }
            for (const user of validUsers) {
              next[user.id] = user
            }
            return next
          })
        }
      })
      .catch((err) => {
        if (!ignore) setLoadError(err)
      })
      .finally(() => {
        // Only the latest request may end the loading state; an ignored one would end it too early.
        // When stepping back a page, the next request ends it.
        if (!ignore && !steppingBack) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [tab, date, filteredUserId, pageNumber, reloadToken])

  // The numbers on the three tabs: size=1 is enough, we only read totalElements.
  useEffect(() => {
    let ignore = false
    const tabs: Tab[] = ['pickup', 'out', 'overdue']
    Promise.all(
      tabs.map((t) =>
        searchReservations({
          status: TAB_STATUS[t],
          userId: filteredUserId,
          date: t === 'pickup' ? date : undefined,
          size: 1,
        }),
      ),
    )
      .then(([pickup, out, overdue]) => {
        if (!ignore) setCounts({ pickup: pickup.totalElements, out: out.totalElements, overdue: overdue.totalElements })
      })
      .catch(() => {
        // The counts are extra; the list above already shows the error if the backend is down.
      })
    return () => {
      ignore = true
    }
  }, [date, filteredUserId, reloadToken])

  // Each change below that re-runs the list effect also sets loading = true. Each one first checks
  // that something really changed: if the effect didn't run, nothing would set loading back to false.
  function changeTab(value: string) {
    if (value === tab) return
    setLoading(true)
    setTab(value as Tab)
    setPageNumber(0)
    setPageData(null) // don't show the old tab's rows with the new tab's buttons
  }

  function changeDate(value: string) {
    // The browser gives "" while the field is being cleared; keep the last real day.
    if (!value || value === date) return
    setLoading(true)
    setDate(value)
    setPageNumber(0)
    // Bug fixed (#166): the old day's rows and their Check out buttons stayed clickable while the new day
    // loaded (the table only shows its loader when there is no page). Fix: clear them, like changeTab does.
    setPageData(null)
  }

  function changePage(newPage: number) {
    // Mantine's Pagination also fires when the current page is clicked again; only a new page loads.
    if (newPage === pageNumber) return
    setLoading(true)
    setPageNumber(newPage)
    setPageData(null) // same bug as in changeDate: no old page's buttons while the new page loads
  }

  function reload() {
    setLoading(true)
    setReloadToken((t) => t + 1)
  }

  function handleVerified(student: VerifiedStudent, filterToStudent: boolean) {
    setVerified((prev) => ({ ...prev, [student.userId]: student }))
    if (!filterToStudent) return

    const sameStudent = filteredStudent?.userId === student.userId
    setFilteredStudent(student)
    setNotice(null)
    setActionError(null)
    setPageNumber(0)
    setPageData(null)
    setLoading(true)
    if (sameStudent) {
      // Re-scanning the already filtered student leaves filteredUserId unchanged; bump reloadToken
      // so both effects still run and clear loading.
      setReloadToken((t) => t + 1)
    }
  }

  function clearStudentFilter() {
    if (!filteredStudent) return
    setFilteredStudent(null)
    setPageNumber(0)
    setPageData(null)
    setLoading(true)
  }

  async function runAction(reservation: Reservation, action: 'checkout' | 'return') {
    setBusyId(reservation.id)
    setActionError(null)
    setNotice(null)
    try {
      if (action === 'checkout') {
        await checkOutReservation(reservation.id)
        setNotice(`Reservation #${reservation.id} checked out. Hand the equipment to the student.`)
      } else {
        await returnReservation(reservation.id)
        setNotice(`Reservation #${reservation.id} returned. The equipment is back at the desk.`)
      }
    } catch (err) {
      // 409s (pick-up window closed, already returned, ...) get their friendly text from errorMessages.ts.
      // The title says which booking failed, since the admin may have clicked several rows.
      const verb = action === 'checkout' ? 'check out' : 'return'
      setActionError({ title: `Could not ${verb} reservation #${reservation.id}`, error: err })
    } finally {
      setBusyId(null)
      // Reload either way: after a 409 the row is often out of date (someone else already acted on it).
      reload()
    }
  }

  const columns: Column<Reservation>[] = [
    {
      header: 'Time',
      render: (r) => (
        <div>
          <Text fw={600}>
            {timeOf(r.startTime)} – {timeOf(r.endTime)}
          </Text>
          <Text size="xs" c="dimmed">
            {dayOf(r.startTime)} · #{r.id}
          </Text>
        </div>
      ),
    },
    {
      header: 'Equipment',
      render: (r) => {
        const asset = assets[r.assetId]
        return (
          <div>
            <Text fw={500}>{asset?.name ?? `Asset #${r.assetId}`}</Text>
            {asset && (
              <Text size="xs" c="dimmed">
                {asset.model} · S/N {asset.serialNumber}
              </Text>
            )}
          </div>
        )
      },
    },
    {
      // Student names come from Stefania's GET /api/v1/admin/users/{id} (cached per id); once the desk
      // verifies the student's pickup pass, VerifiedMark adds the green check on top.
      header: 'Student',
      render: (r) => {
        const student = verified[r.userId]
        if (student) return <VerifiedMark student={student} />
        const user = users[r.userId]
        if (user) {
          return (
            <div>
              <Text fw={500} title={user.email}>
                {user.fullName}
              </Text>
              <Text size="xs" c="dimmed">
                #{r.userId}
              </Text>
            </div>
          )
        }
        return <Text>Student #{r.userId}</Text>
      },
    },
    {
      header: 'Status',
      render: (r) => <StatusBadge status={r.status} />,
    },
    {
      header: 'Action',
      render: (r) => {
        if (r.status === 'CONFIRMED') {
          return (
            <Group gap="xs" justify="flex-end" wrap="nowrap">
              {!verified[r.userId] && (
                <Button size="xs" variant="default" onClick={() => setVerifyTarget({ kind: 'student', userId: r.userId })}>
                  Verify
                </Button>
              )}
              <Button
                size="xs"
                loading={busyId === r.id}
                disabled={busyId !== null && busyId !== r.id}
                onClick={() => runAction(r, 'checkout')}
              >
                Check out
              </Button>
            </Group>
          )
        }
        if (r.status === 'CHECKED_OUT' || r.status === 'OVERDUE') {
          return (
            <Button
              size="xs"
              color="teal"
              loading={busyId === r.id}
              disabled={busyId !== null && busyId !== r.id}
              onClick={() => runAction(r, 'return')}
            >
              Return
            </Button>
          )
        }
        return null
      },
    },
  ]

  const emptyText = filteredStudent
    ? tab === 'pickup'
      ? `No pick-ups for ${filteredStudent.fullName} on this day.`
      : tab === 'out'
        ? `${filteredStudent.fullName} has no equipment out right now.`
        : `${filteredStudent.fullName} has nothing overdue.`
    : tab === 'pickup'
      ? 'No pick-ups left for this day.'
      : tab === 'out'
        ? 'No equipment is out right now.'
        : 'Nothing is overdue.'

  const tabLabel = (label: string, t: Tab) => (counts[t] === null ? label : `${label} (${counts[t]})`)

  return (
    <>
      <PageHeader
        title="Reservations desk"
        description="Hand out equipment when a student comes to pick it up, and take it back when they return it."
        actions={
          <Group gap="xs">
            <Button leftSection={<IconQrcode size={16} />} onClick={() => setVerifyTarget({ kind: 'any' })}>
              Scan student pass
            </Button>
            <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={reload} loading={loading}>
              Refresh
            </Button>
          </Group>
        }
      />

      {notice && (
        <Alert color="green" icon={<IconCheck size={18} />} withCloseButton onClose={() => setNotice(null)} mb="md">
          {notice}
        </Alert>
      )}
      {actionError && (
        <Alert
          color="red"
          icon={<IconAlertTriangle size={18} />}
          title={actionError.title}
          withCloseButton
          onClose={() => setActionError(null)}
          mb="md"
        >
          {errorMessage(actionError.error)}
        </Alert>
      )}
      <ErrorAlert error={loadError} />

      {filteredStudent && (
        <Alert color="teal" icon={<IconCircleCheckFilled size={18} />} mb="md">
          <Group justify="space-between" align="center" gap="sm" wrap="wrap">
            <Text size="sm">
              Filtered to{' '}
              <Text span fw={600}>
                {filteredStudent.fullName}
              </Text>{' '}
              ({filteredStudent.email}) · #{filteredStudent.userId}
            </Text>
            <Button size="xs" variant="light" color="teal" onClick={clearStudentFilter}>
              Show all students
            </Button>
          </Group>
        </Alert>
      )}

      <Group justify="space-between" align="flex-end" mb="sm" wrap="wrap">
        <SegmentedControl
          value={tab}
          onChange={changeTab}
          data={[
            { label: tabLabel('To pick up', 'pickup'), value: 'pickup' },
            { label: tabLabel('Out now', 'out'), value: 'out' },
            { label: tabLabel('Overdue', 'overdue'), value: 'overdue' },
          ]}
        />
        {tab === 'pickup' && (
          <Group gap="xs" align="flex-end">
            <TextInput type="date" label="Day" value={date} onChange={(e) => changeDate(e.currentTarget.value)} />
            <Button variant="subtle" onClick={() => changeDate(todayInBucharest())}>
              Today
            </Button>
          </Group>
        )}
      </Group>

      {isPhone ? (
        <PhoneList page={pageData} columns={columns} onPageChange={changePage} loading={loading} emptyText={emptyText} />
      ) : (
        <PagedTable
          page={pageData}
          columns={columns}
          rowKey={(r) => r.id}
          onPageChange={changePage}
          loading={loading}
          emptyText={emptyText}
        />
      )}

      {verifyTarget !== null && (
        <VerifyStudentDialog
          userId={verifyTarget.kind === 'student' ? verifyTarget.userId : undefined}
          studentName={verifyTarget.kind === 'student' ? users[verifyTarget.userId]?.fullName : undefined}
          onClose={() => setVerifyTarget(null)}
          onVerified={(student) => handleVerified(student, verifyTarget.kind === 'any')}
        />
      )}
    </>
  )
}

function StatusBadge({ status }: { status: ReservationStatus }) {
  if (status === 'CONFIRMED') return <Badge color="blue">To pick up</Badge>
  if (status === 'CHECKED_OUT') return <Badge color="teal">Out</Badge>
  if (status === 'OVERDUE') return <Badge color="red">Overdue</Badge>
  return <Badge color="gray">{status}</Badge>
}

type PhoneListProps = {
  page: PageResponse<Reservation> | null
  columns: Column<Reservation>[]
  onPageChange: (page: number) => void
  loading: boolean
  emptyText: string
}

// The phone version of <PagedTable>: the same columns, stacked in one card per booking,
// so the Check out / Return button is visible without scrolling sideways.
function PhoneList({ page, columns, onPageChange, loading, emptyText }: PhoneListProps) {
  if (loading && !page) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }
  if (!page || page.content.length === 0) {
    return (
      <Text c="dimmed" ta="center" py="xl">
        {emptyText}
      </Text>
    )
  }
  return (
    <Stack gap="sm">
      {page.content.map((r) => (
        <Paper key={r.id} withBorder p="sm" bg="var(--app-surface)">
          <Stack gap="xs">
            {columns.map((column) => (
              <Group key={column.header} justify="space-between" align="flex-start" wrap="nowrap">
                <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                  {column.header}
                </Text>
                <div style={{ textAlign: 'right' }}>{column.render(r)}</div>
              </Group>
            ))}
          </Stack>
        </Paper>
      ))}
      {page.totalPages > 1 && (
        <Pagination total={page.totalPages} value={page.page + 1} onChange={(value) => onPageChange(value - 1)} />
      )}
    </Stack>
  )
}
