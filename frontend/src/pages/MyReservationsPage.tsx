import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import {
  IconArrowsExchange,
  IconCalendar,
  IconCalendarCheck,
  IconCheck,
  IconPlus,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router'
import {
  cancelReservation,
  fetchAssetSummary,
  fetchMyReservations,
  fetchMyReservationSummary,
  type AssetSummary,
  type Reservation,
  type ReservationStatus,
  type ReservationSummary,
} from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

// Formats "2026-11-03T14:00:00" without timezone offset skew.
function formatSlotDate(isoString: string): string {
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

function formatSlotTime(startTime: string, endTime: string): string {
  const startPart = startTime.split('T')[1]?.slice(0, 5) ?? ''
  const endPart = endTime.split('T')[1]?.slice(0, 5) ?? ''
  return `${startPart} – ${endPart}`
}

function statusColor(status: ReservationStatus): string {
  switch (status) {
    case 'CONFIRMED':
      return 'blue'
    case 'CHECKED_OUT':
      return 'teal'
    case 'RETURNED':
      return 'green'
    case 'OVERDUE':
      return 'red'
    case 'NO_SHOW':
      return 'red'
    case 'FORCE_CANCELLED':
      return 'orange'
    case 'CANCELLED':
    default:
      return 'gray'
  }
}

function statusLabel(status: ReservationStatus): string {
  switch (status) {
    case 'CONFIRMED':
      return 'Confirmed'
    case 'CHECKED_OUT':
      return 'Checked out'
    case 'RETURNED':
      return 'Returned'
    case 'OVERDUE':
      return 'Overdue'
    case 'NO_SHOW':
      return 'No-show'
    case 'FORCE_CANCELLED':
      return 'Auto-cancelled'
    case 'CANCELLED':
      return 'Cancelled'
    default:
      return status
  }
}

export default function MyReservationsPage() {
  const [pageData, setPageData] = useState<PageResponse<Reservation>>({
    content: [],
    page: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
  })
  const [pageNumber, setPageNumber] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [filterTab, setFilterTab] = useState<'all' | 'active' | 'past'>('all')

  const location = useLocation()
  const [actionNotice, setActionNotice] = useState<string | null>(() => {
    const state = location.state as { bookedId?: number } | null
    if (state?.bookedId) {
      window.history.replaceState({}, document.title)
      return `Booking confirmed! Reservation #${state.bookedId} has been added to your upcoming bookings.`
    }
    return null
  })

  // Aggregate stats across all reservations, fetched from GET /api/v1/reservations/me/summary
  const [summary, setSummary] = useState<ReservationSummary>({
    activeCount: 0,
    completedCount: 0,
    reassignedCount: 0,
  })

  // Cache for resolved asset names to enrich the table rows.
  const [assetCache, setAssetCache] = useState<Record<number, AssetSummary>>({})

  // Increment this counter to re-fetch the current reservations page and summary.
  const [reloadToken, setReloadToken] = useState(0)

  // Modal state for cancelling a reservation.
  const [cancelModalOpened, { open: openCancelModal, close: closeCancelModal }] = useDisclosure(false)
  const [cancellingReservation, setCancellingReservation] = useState<Reservation | null>(null)
  const [cancelSubmitting, setCancelSubmitting] = useState(false)
  const [cancelError, setCancelError] = useState<unknown>(null)

  // Fetch summary counts for the user across the entire database.
  useEffect(() => {
    let ignore = false
    fetchMyReservationSummary()
      .then((data) => {
        if (!ignore) setSummary(data)
      })
      .catch(() => {
        // Non-blocking fallback if summary is unavailable
      })
    return () => {
      ignore = true
    }
  }, [reloadToken])

  // Fetch paginated reservations with server-side status filtering.
  useEffect(() => {
    let ignore = false
    const statusFilter: ReservationStatus[] | undefined =
      filterTab === 'active'
        ? ['CONFIRMED', 'CHECKED_OUT', 'OVERDUE']
        : filterTab === 'past'
          ? ['RETURNED', 'CANCELLED', 'FORCE_CANCELLED', 'NO_SHOW']
          : undefined

    fetchMyReservations(pageNumber, 10, statusFilter)
      .then(async (response) => {
        if (ignore) return
        setPageData(response)

        const neededAssetIds = [
          ...new Set(
            response.content
              .flatMap((r) => [r.assetId, r.originalAssetId])
              .filter((id): id is number => id !== null && id !== undefined),
          ),
        ]

        if (neededAssetIds.length > 0) {
          const fetched = await Promise.all(
            neededAssetIds.map((id) => fetchAssetSummary(id).catch(() => null)),
          )
          if (!ignore) {
            const newEntries: Record<number, AssetSummary> = {}
            for (const item of fetched) {
              if (item) newEntries[item.id] = item
            }
            if (Object.keys(newEntries).length > 0) {
              setAssetCache((prev) => ({ ...prev, ...newEntries }))
            }
          }
        }
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })

    return () => {
      ignore = true
    }
  }, [pageNumber, filterTab, reloadToken])

  function handleTabChange(val: string) {
    setFilterTab(val as 'all' | 'active' | 'past')
    setPageNumber(0)
    setLoading(true)
  }

  function handleStartCancel(reservation: Reservation) {
    setCancellingReservation(reservation)
    setCancelError(null)
    openCancelModal()
  }

  async function handleConfirmCancel() {
    if (!cancellingReservation) return
    const cancelledId = cancellingReservation.id
    setCancelSubmitting(true)
    setCancelError(null)
    try {
      await cancelReservation(cancelledId)
      closeCancelModal()
      setCancellingReservation(null)
      setActionNotice(`Reservation #${cancelledId} was successfully cancelled.`)
      // Refresh the current page and summary counters.
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setCancelError(err)
    } finally {
      setCancelSubmitting(false)
    }
  }

  const columns: Column<Reservation>[] = [
    {
      header: 'Date & Time',
      render: (r) => (
        <div>
          <Text fw={500}>{formatSlotDate(r.startTime)}</Text>
          <Text size="xs" c="dimmed">
            {formatSlotTime(r.startTime, r.endTime)}
          </Text>
        </div>
      ),
    },
    {
      header: 'Equipment',
      render: (r) => {
        const asset = assetCache[r.assetId]
        const origAsset = r.originalAssetId ? assetCache[r.originalAssetId] : null
        return (
          <div>
            <Text fw={600}>{asset?.name ?? `Asset #${r.assetId}`}</Text>
            {asset && (
              <Text size="xs" c="dimmed">
                {asset.model} · S/N {asset.serialNumber}
              </Text>
            )}
            {r.originalAssetId && (
              <Tooltip
                label={`Moved by reassignment engine because ${origAsset?.name ?? `Asset #${r.originalAssetId}`} was damaged.`}
              >
                <Badge
                  size="xs"
                  variant="outline"
                  color="blue"
                  leftSection={<IconArrowsExchange size={12} />}
                  mt={4}
                >
                  Moved from {origAsset?.name ?? `#${r.originalAssetId}`}
                </Badge>
              </Tooltip>
            )}
          </div>
        )
      },
    },
    {
      header: 'Status',
      render: (r) => {
        const isForceCancelled =
          r.status === 'FORCE_CANCELLED' ||
          (r.status === 'CANCELLED' && (r.cancelReason?.toLowerCase().includes('incident') || r.cancelReason?.toLowerCase().includes('equipment failure')))

        return (
          <div>
            <Badge color={statusColor(r.status)} variant="filled">
              {statusLabel(r.status)}
            </Badge>
            {isForceCancelled && (
              <Tooltip label={r.cancelReason ?? 'Cancelled by automatic reassignment engine'}>
                <Badge size="xs" variant="light" color="orange" mt={4} style={{ display: 'block', maxWidth: 'fit-content' }}>
                  Force-cancelled by engine
                </Badge>
              </Tooltip>
            )}
            {r.cancelReason && !isForceCancelled && (
              <Tooltip label={r.cancelReason}>
                <Text size="xs" c="dimmed" mt={2} maw={180} truncate>
                  {r.cancelReason}
                </Text>
              </Tooltip>
            )}
          </div>
        )
      },
    },
    {
      header: 'Actions',
      render: (r) => {
        const canCancel = r.status === 'CONFIRMED'
        if (!canCancel) return null
        return (
          <Button
            size="xs"
            variant="light"
            color="red"
            onClick={() => handleStartCancel(r)}
          >
            Cancel
          </Button>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        title="My reservations"
        description="View your lab bookings and manage upcoming slots. You can cancel any confirmed booking until its start time."
        actions={
          <Tooltip
            label="You already have 2 active bookings (the maximum allowed). Return or cancel a booking before making a new reservation."
            disabled={summary.activeCount < 2}
          >
            <Button
              component={Link}
              to="/catalog"
              leftSection={<IconPlus size={16} />}
              color={summary.activeCount >= 2 ? 'gray' : 'campus'}
            >
              Book equipment
            </Button>
          </Tooltip>
        }
      />

      {actionNotice && (
        <Alert
          color="green"
          icon={<IconCheck size={18} />}
          withCloseButton
          onClose={() => setActionNotice(null)}
          mb="md"
        >
          {actionNotice}
        </Alert>
      )}

      <ErrorAlert error={error} />

      {/* Overview Cards */}
      <SimpleGrid cols={{ base: 1, sm: 3 }} mb="lg">
        <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
          <Group justify="space-between">
            <div>
              <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                Active bookings
              </Text>
              <Group gap="xs" align="baseline" mt={2}>
                <Text fw={700} size="xl">
                  {summary.activeCount}
                </Text>
                <Text size="xs" c="dimmed">
                  of 2 max allowed
                </Text>
              </Group>
              {summary.activeCount >= 2 && (
                <Badge color="orange" size="xs" variant="light" mt={4}>
                  Limit reached (2/2)
                </Badge>
              )}
            </div>
            <ThemeIcon
              size={36}
              radius="md"
              variant="light"
              color={summary.activeCount >= 2 ? 'orange' : 'campus'}
            >
              <IconCalendar size={20} />
            </ThemeIcon>
          </Group>
        </Card>

        <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
          <Group justify="space-between">
            <div>
              <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                Completed
              </Text>
              <Text fw={700} size="xl" mt={2}>
                {summary.completedCount}
              </Text>
            </div>
            <ThemeIcon size={36} radius="md" variant="light" color="green">
              <IconCalendarCheck size={20} />
            </ThemeIcon>
          </Group>
        </Card>

        <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
          <Group justify="space-between">
            <div>
              <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                Reassigned
              </Text>
              <Group gap="xs" align="baseline" mt={2}>
                <Text fw={700} size="xl">
                  {summary.reassignedCount}
                </Text>
                <Text size="xs" c="dimmed">
                  moved by engine
                </Text>
              </Group>
            </div>
            <ThemeIcon size={36} radius="md" variant="light" color="blue">
              <IconArrowsExchange size={20} />
            </ThemeIcon>
          </Group>
        </Card>
      </SimpleGrid>

      {/* Filter Tabs & Table */}
      <Group justify="space-between" mb="sm">
        <SegmentedControl
          value={filterTab}
          onChange={handleTabChange}
          data={[
            { label: 'All bookings', value: 'all' },
            { label: `Active (${summary.activeCount})`, value: 'active' },
            { label: 'Past', value: 'past' },
          ]}
        />
      </Group>

      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(r) => r.id}
        onPageChange={(newPage) => {
          setLoading(true)
          setPageNumber(newPage)
        }}
        loading={loading}
        emptyText={
          filterTab === 'active'
            ? 'No active bookings. Browse the catalog to book equipment.'
            : filterTab === 'past'
              ? 'No past bookings found.'
              : 'No reservations found.'
        }
      />

      {/* Cancel Confirmation Modal */}
      <Modal
        opened={cancelModalOpened}
        onClose={closeCancelModal}
        title={<Text fw={600}>Cancel Reservation #{cancellingReservation?.id}</Text>}
        centered
      >
        <Stack>
          <ErrorAlert error={cancelError} />
          {cancellingReservation && (
            <div>
              <Text size="sm">
                Are you sure you want to cancel your booking for{' '}
                <b>{assetCache[cancellingReservation.assetId]?.name ?? `Asset #${cancellingReservation.assetId}`}</b>?
              </Text>
              <Text size="xs" c="dimmed" mt={4}>
                Time slot: {formatSlotDate(cancellingReservation.startTime)} ({formatSlotTime(cancellingReservation.startTime, cancellingReservation.endTime)})
              </Text>
            </div>
          )}
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeCancelModal} disabled={cancelSubmitting}>
              Keep booking
            </Button>
            <Button
              color="red"
              onClick={handleConfirmCancel}
              loading={cancelSubmitting}
            >
              Confirm cancel
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  )
}
