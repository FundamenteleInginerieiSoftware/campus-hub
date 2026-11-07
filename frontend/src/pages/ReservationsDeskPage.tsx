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
import { IconAlertTriangle, IconCheck, IconRefresh } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { errorMessage } from '../api/errorMessages.ts'
import { checkOutReservation, returnReservation, searchReservations } from '../api/reservationDesk.ts'
import { fetchAssetSummary, type AssetSummary, type Reservation, type ReservationStatus } from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

type Tab = 'pickup' | 'out' | 'overdue'

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

function searchFor(tab: Tab, date: string, page: number) {
  return searchReservations({
    status: TAB_STATUS[tab],
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
  // Ids of assets already loaded or being loaded. A ref, not state: the list effect reads it after an
  // await, and a ref always gives the current set there (a state value would be the one from the render
  // that started the request).
  const requestedAssetIds = useRef(new Set<number>())
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<unknown>(null)

  // Check-out / return: which row is busy, and what happened last.
  const [busyId, setBusyId] = useState<number | null>(null)
  const [actionError, setActionError] = useState<{ title: string; error: unknown } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  // Adding 1 re-runs both effects below (after an action or a click on "Refresh").
  const [reloadToken, setReloadToken] = useState(0)

  // Below Mantine's "sm" breakpoint (48em = 768px) the 5 columns don't fit: show one card per booking.
  const isPhone = useMediaQuery('(max-width: 48em)')

  // The rows of the open tab.
  useEffect(() => {
    // "ignore" drops the answer of a request the user has already moved away from
    // (other tab or day), so a slow old answer can't overwrite the new list.
    let ignore = false
    let steppingBack = false
    searchFor(tab, date, pageNumber)
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

        // Asset names come from Liviu's public GET /api/v1/assets/{id}, one call per asset on the page.
        // If one fails, that row just shows "Asset #id".
        // Bug fixed (#166): every tab switch, day change or refresh fetched every asset on the page again,
        // even the ones we already had. Fix: only fetch ids not asked for yet. A failed one is removed from
        // the set, so the next load tries it again. The names are kept even if the user has moved on
        // meanwhile: they are already marked as asked for, so no later load would fetch them.
        const ids = [...new Set(response.content.map((r) => r.assetId))].filter((id) => !requestedAssetIds.current.has(id))
        ids.forEach((id) => requestedAssetIds.current.add(id))
        const found = await Promise.all(
          ids.map((id) =>
            fetchAssetSummary(id).catch(() => {
              requestedAssetIds.current.delete(id)
              return null
            }),
          ),
        )
        if (found.length === 0) return
        setAssets((prev) => {
          const next = { ...prev }
          for (const asset of found) {
            if (asset) next[asset.id] = asset
          }
          return next
        })
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
  }, [tab, date, pageNumber, reloadToken])

  // The numbers on the three tabs: size=1 is enough, we only read totalElements.
  useEffect(() => {
    let ignore = false
    const tabs: Tab[] = ['pickup', 'out', 'overdue']
    Promise.all(
      tabs.map((t) => searchReservations({ status: TAB_STATUS[t], date: t === 'pickup' ? date : undefined, size: 1 })),
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
  }, [date, reloadToken])

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
      // Only the id: there is no admin endpoint to look up another user's name yet.
      header: 'Student',
      render: (r) => <Text>Student #{r.userId}</Text>,
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
            <Button
              size="xs"
              loading={busyId === r.id}
              disabled={busyId !== null && busyId !== r.id}
              onClick={() => runAction(r, 'checkout')}
            >
              Check out
            </Button>
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

  const emptyText =
    tab === 'pickup' ? 'No pick-ups left for this day.' : tab === 'out' ? 'No equipment is out right now.' : 'Nothing is overdue.'

  const tabLabel = (label: string, t: Tab) => (counts[t] === null ? label : `${label} (${counts[t]})`)

  return (
    <>
      <PageHeader
        title="Reservations desk"
        description="Hand out equipment when a student comes to pick it up, and take it back when they return it."
        actions={
          <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={reload} loading={loading}>
            Refresh
          </Button>
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
