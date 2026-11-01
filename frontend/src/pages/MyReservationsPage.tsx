import {
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
  IconPlus,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import {
  cancelReservation,
  fetchAssetSummary,
  fetchMyReservations,
  type AssetSummary,
  type Reservation,
  type ReservationStatus,
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

  // Cache for resolved asset names to enrich the table rows.
  const [assetCache, setAssetCache] = useState<Record<number, AssetSummary>>({})

  // Increment this counter to re-fetch the current reservations page.
  const [reloadToken, setReloadToken] = useState(0)

  // Modal state for cancelling a reservation.
  const [cancelModalOpened, { open: openCancelModal, close: closeCancelModal }] = useDisclosure(false)
  const [cancellingReservation, setCancellingReservation] = useState<Reservation | null>(null)
  const [cancelSubmitting, setCancelSubmitting] = useState(false)
  const [cancelError, setCancelError] = useState<unknown>(null)

  useEffect(() => {
    let ignore = false
    fetchMyReservations(pageNumber, 10)
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
  }, [pageNumber, reloadToken])

  // Filter content client-side based on the selected tab.
  const filteredContent = useMemo(() => {
    if (filterTab === 'active') {
      return pageData.content.filter((r) => ['CONFIRMED', 'CHECKED_OUT', 'OVERDUE'].includes(r.status))
    }
    if (filterTab === 'past') {
      return pageData.content.filter((r) => !['CONFIRMED', 'CHECKED_OUT', 'OVERDUE'].includes(r.status))
    }
    return pageData.content
  }, [pageData.content, filterTab])

  // Summary statistics for the user.
  const activeCount = useMemo(
    () => pageData.content.filter((r) => ['CONFIRMED', 'CHECKED_OUT', 'OVERDUE'].includes(r.status)).length,
    [pageData.content],
  )
  const completedCount = useMemo(
    () => pageData.content.filter((r) => r.status === 'RETURNED').length,
    [pageData.content],
  )
  const movedCount = useMemo(
    () => pageData.content.filter((r) => r.originalAssetId !== null).length,
    [pageData.content],
  )

  function handleStartCancel(reservation: Reservation) {
    setCancellingReservation(reservation)
    setCancelError(null)
    openCancelModal()
  }

  async function handleConfirmCancel() {
    if (!cancellingReservation) return
    setCancelSubmitting(true)
    setCancelError(null)
    try {
      await cancelReservation(cancellingReservation.id)
      closeCancelModal()
      setCancellingReservation(null)
      // Refresh the current page.
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
      render: (r) => (
        <div>
          <Badge color={statusColor(r.status)} variant="filled">
            {statusLabel(r.status)}
          </Badge>
          {r.cancelReason && (
            <Text size="xs" c="dimmed" mt={2} maw={180} truncate>
              {r.cancelReason}
            </Text>
          )}
        </div>
      ),
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

  const displayedPageData: PageResponse<Reservation> = {
    ...pageData,
    content: filteredContent,
  }

  return (
    <>
      <PageHeader
        title="My reservations"
        description="View your lab bookings and manage upcoming slots. You can cancel any confirmed booking until its start time."
        actions={
          <Button component={Link} to="/catalog" leftSection={<IconPlus size={16} />}>
            Book equipment
          </Button>
        }
      />

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
                  {activeCount}
                </Text>
                <Text size="xs" c="dimmed">
                  of 2 max allowed
                </Text>
              </Group>
            </div>
            <ThemeIcon size={36} radius="md" variant="light" color={activeCount >= 2 ? 'orange' : 'campus'}>
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
                {completedCount}
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
                  {movedCount}
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
          onChange={(val) => setFilterTab(val as 'all' | 'active' | 'past')}
          data={[
            { label: 'All bookings', value: 'all' },
            { label: `Active (${activeCount})`, value: 'active' },
            { label: 'Past', value: 'past' },
          ]}
        />
      </Group>

      <PagedTable
        page={displayedPageData}
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
