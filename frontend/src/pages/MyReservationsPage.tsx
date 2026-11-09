import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import {
  IconAlertTriangle,
  IconArrowsExchange,
  IconCalendar,
  IconCalendarCheck,
  IconCheck,
  IconClockPlus,
  IconPackage,
  IconPlus,
  IconUser,
  IconUserShare,
} from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import {
  acceptTransfer,
  cancelReservation,
  extendReservation,
  fetchAssetSummary,
  fetchMyReservations,
  fetchMyReservationSummary,
  fetchPendingTransfers,
  initiateTransfer,
  nowInBucharest,
  parseCampusLocalDateTime,
  rejectTransfer,
  type AssetSummary,
  type Reservation,
  type ReservationStatus,
  type ReservationSummary,
  type ReservationTransferResponse,
} from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import { useAuth } from '../auth/useAuth.ts'
import { ApiError } from '../api/client.ts'
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

/**
 * Computes allowable new end times for an active reservation.
 * Enforces the 4-hour max total duration and the 20:00 laboratory closing time constraint.
 */
function computeExtensionOptions(r: Reservation): { value: string; label: string }[] {
  const [datePart, startPart] = r.startTime.split('T')
  const [, endPart] = r.endTime.split('T')
  if (!startPart || !endPart) return []

  const [startH, startM] = startPart.split(':').map(Number)
  const [endH, endM] = endPart.split(':').map(Number)
  const startMins = startH * 60 + startM
  const endMins = endH * 60 + endM

  // Max 4 hours (240 min) from start, and lab closes at 20:00 (1200 min)
  const maxMinsAllowed = Math.min(startMins + 240, 1200)
  const remainingMins = maxMinsAllowed - endMins

  const options: { value: string; label: string }[] = []
  for (const step of [30, 60, 90, 120, 150, 180]) {
    if (step <= remainingMins) {
      const targetMins = endMins + step
      const targetH = Math.floor(targetMins / 60)
      const targetM = targetMins % 60
      const timeStr = `${String(targetH).padStart(2, '0')}:${String(targetM).padStart(2, '0')}`
      options.push({
        value: `${datePart}T${timeStr}:00`,
        label: `${timeStr} (+${step} min)`,
      })
    }
  }
  return options
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
  const { user } = useAuth()
  // Bug fixed (#122): pageData initialized as null (not empty object) and reset to null
  // on tab/page transitions so PagedTable displays the loading spinner while fetching,
  // avoiding false "No reservations found" flashes and stale tab data.
  const [pageData, setPageData] = useState<PageResponse<Reservation> | null>(null)
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
  // assetCacheRef mirrors assetCache so the fetch effect can skip already-cached IDs
  // without adding assetCache to the effect dependency array.
  const [assetCache, setAssetCache] = useState<Record<number, AssetSummary>>({})
  const assetCacheRef = useRef<Record<number, AssetSummary>>({})

  // Increment this counter to re-fetch the current reservations page and summary.
  const [reloadToken, setReloadToken] = useState(0)

  // Modal state for cancelling a reservation.
  const [cancelModalOpened, { open: openCancelModal, close: closeCancelModal }] = useDisclosure(false)
  const [cancellingReservation, setCancellingReservation] = useState<Reservation | null>(null)
  const [cancelSubmitting, setCancelSubmitting] = useState(false)
  const [cancelError, setCancelError] = useState<unknown>(null)

  // Incoming pending transfers
  const [pendingTransfers, setPendingTransfers] = useState<ReservationTransferResponse[]>([])
  const [transferRespondingId, setTransferRespondingId] = useState<number | null>(null)

  // Extend reservation modal state
  const [extendingReservation, setExtendingReservation] = useState<Reservation | null>(null)
  const [extendEndTime, setExtendEndTime] = useState<string | null>(null)
  const [extendLoading, setExtendLoading] = useState(false)
  const [extendError, setExtendError] = useState<unknown>(null)

  // Transfer reservation modal state
  const [transferringReservation, setTransferringReservation] = useState<Reservation | null>(null)
  const [recipientUserId, setRecipientUserId] = useState<number | string>('')
  const [transferLoading, setTransferLoading] = useState(false)
  const [transferError, setTransferError] = useState<unknown>(null)

  // Fetch summary counts and pending transfers for the student.
  useEffect(() => {
    let ignore = false
    fetchMyReservationSummary()
      .then((data) => {
        if (!ignore) setSummary(data)
      })
      .catch(() => {
        // Non-blocking fallback if summary is unavailable
      })

    fetchPendingTransfers()
      .then((transfers) => {
        if (!ignore) setPendingTransfers(transfers)
      })
      .catch(() => {
        // Non-blocking fallback if transfer queue is temporarily unreachable
      })

    return () => {
      ignore = true
    }
  }, [reloadToken])

  // Fetch paginated reservations with server-side status filtering.
  useEffect(() => {
    let ignore = false
    let steppingBack = false
    const statusFilter: ReservationStatus[] | undefined =
      filterTab === 'active'
        ? ['CONFIRMED', 'CHECKED_OUT', 'OVERDUE']
        : filterTab === 'past'
          ? ['RETURNED', 'CANCELLED', 'FORCE_CANCELLED', 'NO_SHOW']
          : undefined

    fetchMyReservations(pageNumber, 10, statusFilter)
      .then(async (response) => {
        if (ignore) return
        // Bug fixed: cancelling the last item on page > 1 returned an empty page and hid the pagination controls.
        // Fix: step back to the last non-empty page automatically when totalPages shrinks below pageNumber + 1.
        if (response.content.length === 0 && response.totalPages > 0 && pageNumber >= response.totalPages) {
          steppingBack = true
          setPageNumber(response.totalPages - 1)
          return
        }
        setError(null)
        setPageData(response)

        const neededAssetIds = [
          ...new Set(
            response.content
              .flatMap((r) => [r.assetId, r.originalAssetId])
              .filter(
                (id): id is number =>
                  id !== null && id !== undefined && !assetCacheRef.current[id],
              ),
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
              assetCacheRef.current = { ...assetCacheRef.current, ...newEntries }
              setAssetCache((prev) => ({ ...prev, ...newEntries }))
            }
          }
        }
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore && !steppingBack) setLoading(false)
      })

    return () => {
      ignore = true
    }
  }, [pageNumber, filterTab, reloadToken])

  function handleTabChange(val: string) {
    setFilterTab(val as 'all' | 'active' | 'past')
    setPageNumber(0)
    setPageData(null)
    setError(null)
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
      setError(null)
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setCancelError(err)
    } finally {
      setCancelSubmitting(false)
    }
  }

  async function handleAcceptTransfer(transferId: number) {
    setTransferRespondingId(transferId)
    setError(null)
    try {
      await acceptTransfer(transferId)
      setActionNotice('Transfer accepted! The reservation has been transferred to your upcoming bookings.')
      setError(null)
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setError(err)
    } finally {
      setTransferRespondingId(null)
    }
  }

  async function handleRejectTransfer(transferId: number) {
    setTransferRespondingId(transferId)
    setError(null)
    try {
      await rejectTransfer(transferId)
      setActionNotice('Transfer offer declined.')
      setError(null)
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setError(err)
    } finally {
      setTransferRespondingId(null)
    }
  }

  function handleStartExtend(reservation: Reservation) {
    setExtendingReservation(reservation)
    setExtendError(null)
    const options = computeExtensionOptions(reservation)
    setExtendEndTime(options[0]?.value ?? null)
  }

  async function handleConfirmExtend() {
    if (!extendingReservation || !extendEndTime) return
    setExtendLoading(true)
    setExtendError(null)
    try {
      const updated = await extendReservation(extendingReservation.id, extendEndTime)
      setExtendingReservation(null)
      setActionNotice(
        `Reservation #${updated.id} extended successfully until ${formatSlotTime(updated.startTime, updated.endTime)}.`,
      )
      setError(null)
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setExtendError(err)
    } finally {
      setExtendLoading(false)
    }
  }

  function handleStartTransfer(reservation: Reservation) {
    setTransferringReservation(reservation)
    setRecipientUserId('')
    setTransferError(null)
  }

  async function handleConfirmTransfer() {
    const toId = Number(recipientUserId)
    if (!transferringReservation || !toId || isNaN(toId)) return
    setTransferLoading(true)
    setTransferError(null)
    try {
      await initiateTransfer(transferringReservation.id, toId)
      const resId = transferringReservation.id
      setTransferringReservation(null)
      setActionNotice(`Transfer offer for Reservation #${resId} sent to Student #${toId}.`)
      setError(null)
      setLoading(true)
      setReloadToken((t) => t + 1)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.code === 'RESERVATION_LIMIT_REACHED') {
        setTransferError(
          new ApiError(
            409,
            'RECIPIENT_QUOTA_FULL',
            "This student can't take another booking right now (they have reached the 2 active reservations limit).",
          ),
        )
      } else {
        setTransferError(err)
      }
    } finally {
      setTransferLoading(false)
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
            {r.bundleId && (
              <Badge
                size="xs"
                variant="light"
                color="violet"
                leftSection={<IconPackage size={12} />}
                mt={4}
                style={{ display: 'block', maxWidth: 'fit-content' }}
              >
                Bundle booking
              </Badge>
            )}
          </div>
        )
      },
    },
    {
      header: 'Status',
      render: (r) => {
        const isForceCancelled = r.status === 'FORCE_CANCELLED'

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
        const hasStartedInBucharest = nowInBucharest() >= parseCampusLocalDateTime(r.startTime)
        const canCancel = r.status === 'CONFIRMED' && !hasStartedInBucharest
        const canExtend = r.status === 'CONFIRMED' || r.status === 'CHECKED_OUT'
        const canTransfer = r.status === 'CONFIRMED' && !hasStartedInBucharest

        return (
          <Group gap="xs" wrap="nowrap">
            {canExtend && (
              <Tooltip label="Extend duration of this active reservation">
                <Button
                  size="xs"
                  variant="light"
                  color="blue"
                  leftSection={<IconClockPlus size={14} />}
                  onClick={() => handleStartExtend(r)}
                >
                  Extend
                </Button>
              </Tooltip>
            )}

            {canTransfer && (
              <Tooltip label="Transfer this upcoming reservation to another student">
                <Button
                  size="xs"
                  variant="light"
                  color="violet"
                  leftSection={<IconUserShare size={14} />}
                  onClick={() => handleStartTransfer(r)}
                >
                  Transfer
                </Button>
              </Tooltip>
            )}

            {canCancel && (
              <Tooltip
                label="Students can only cancel before the reservation start time (Europe/Bucharest)."
                disabled={!hasStartedInBucharest}
              >
                <Button
                  size="xs"
                  variant="light"
                  color="red"
                  data-disabled={hasStartedInBucharest || undefined}
                  onClick={() => {
                    if (!hasStartedInBucharest) handleStartCancel(r)
                  }}
                >
                  Cancel
                </Button>
              </Tooltip>
            )}

            <Tooltip label="Report an issue or damage on this equipment">
              <Button
                component={Link}
                to={`/incidents?assetId=${r.assetId}`}
                size="xs"
                variant="subtle"
                color="yellow"
                leftSection={<IconAlertTriangle size={14} />}
              >
                Report
              </Button>
            </Tooltip>
          </Group>
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

      {/* Student Identity Card for Transfers */}
      <Card withBorder radius="md" p="sm" mb="md" bg="var(--mantine-color-blue-light)">
        <Group justify="space-between" align="center" wrap="wrap">
          <Group gap="sm">
            <ThemeIcon color="blue" size="md" radius="xl" variant="filled">
              <IconUser size={16} />
            </ThemeIcon>
            <div>
              <Group gap="xs">
                <Text size="sm" fw={600}>
                  {user?.fullName ?? 'Student Account'}
                </Text>
                <Badge color="blue" size="sm" variant="filled">
                  Your Student ID: #{user?.id ?? '—'}
                </Badge>
              </Group>
              <Text size="xs" c="dimmed">
                {user?.email} • Classmates need your Student ID (<b>#{user?.id ?? '—'}</b>) to transfer an upcoming reservation to you.
              </Text>
            </div>
          </Group>
        </Group>
      </Card>

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

      {/* Incoming Reservation Transfer Offers */}
      {Array.isArray(pendingTransfers) &&
        pendingTransfers.map((t) => (
        <Alert
          key={t.id}
          color="blue"
          icon={<IconArrowsExchange size={18} />}
          withCloseButton={false}
          mb="md"
        >
          <Group justify="space-between" wrap="wrap">
            <div>
              <Text size="sm" fw={600}>
                Incoming Reservation Transfer Offer
              </Text>
              <Text size="xs">
                Student #{t.fromUserId} offered to transfer <b>Reservation #{t.reservationId}</b> to you. Accept to take ownership of this booking.
              </Text>
            </div>
            <Group gap="xs">
              <Button
                size="xs"
                color="teal"
                loading={transferRespondingId === t.id}
                onClick={() => handleAcceptTransfer(t.id)}
              >
                Accept
              </Button>
              <Button
                size="xs"
                variant="default"
                disabled={transferRespondingId === t.id}
                onClick={() => handleRejectTransfer(t.id)}
              >
                Decline
              </Button>
            </Group>
          </Group>
        </Alert>
      ))}

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
          // Mantine's Pagination calls onChange even for the current page; without this guard the spinner never clears.
          if (newPage === pageNumber) return
          setPageData(null)
          setError(null)
          setLoading(true)
          setPageNumber(newPage)
        }}
        loading={loading}
        emptyText={
          error
            ? 'Could not load reservations.'
            : filterTab === 'active'
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

      {/* Extend Duration Modal */}
      {extendingReservation && (
        <Modal
          opened
          onClose={() => !extendLoading && setExtendingReservation(null)}
          title={<Text fw={600}>Extend Reservation #{extendingReservation.id}</Text>}
          centered
        >
          <Stack>
            <ErrorAlert error={extendError} />
            <div>
              <Text size="sm">
                Equipment: <b>{assetCache[extendingReservation.assetId]?.name ?? `Asset #${extendingReservation.assetId}`}</b>
              </Text>
              <Text size="xs" c="dimmed" mt={2}>
                Current slot: {formatSlotDate(extendingReservation.startTime)} ({formatSlotTime(extendingReservation.startTime, extendingReservation.endTime)})
              </Text>
            </div>
            {computeExtensionOptions(extendingReservation).length > 0 ? (
              <Select
                label="New end time"
                description="Select new end time (maximum 4 hours total duration, closing at 20:00)."
                data={computeExtensionOptions(extendingReservation)}
                value={extendEndTime}
                onChange={setExtendEndTime}
                allowDeselect={false}
              />
            ) : (
              <Alert color="yellow">
                This reservation cannot be extended: it already reaches the 4-hour duration limit or the 20:00 lab closing time.
              </Alert>
            )}
            <Group justify="flex-end" mt="md">
              <Button variant="default" onClick={() => setExtendingReservation(null)} disabled={extendLoading}>
                Cancel
              </Button>
              <Button
                color="blue"
                onClick={handleConfirmExtend}
                loading={extendLoading}
                disabled={!extendEndTime || computeExtensionOptions(extendingReservation).length === 0}
              >
                Confirm extension
              </Button>
            </Group>
          </Stack>
        </Modal>
      )}

      {/* Transfer Reservation Modal */}
      {transferringReservation && (
        <Modal
          opened
          onClose={() => !transferLoading && setTransferringReservation(null)}
          title={<Text fw={600}>Transfer Reservation #{transferringReservation.id}</Text>}
          centered
        >
          <Stack>
            <ErrorAlert error={transferError} />
            <div>
              <Text size="sm">
                Offer booking for <b>{assetCache[transferringReservation.assetId]?.name ?? `Asset #${transferringReservation.assetId}`}</b> to another student.
              </Text>
              <Text size="xs" c="dimmed" mt={2}>
                Slot: {formatSlotDate(transferringReservation.startTime)} ({formatSlotTime(transferringReservation.startTime, transferringReservation.endTime)})
              </Text>
            </div>

            <NumberInput
              label="Recipient Student User ID"
              placeholder="e.g. 2"
              description="Classmates can find their Student ID in the banner at the top of their My Reservations page."
              min={1}
              allowDecimal={false}
              value={recipientUserId}
              onChange={setRecipientUserId}
              required
            />
            <Group justify="flex-end" mt="md">
              <Button variant="default" onClick={() => setTransferringReservation(null)} disabled={transferLoading}>
                Cancel
              </Button>
              <Button
                color="violet"
                onClick={handleConfirmTransfer}
                loading={transferLoading}
                disabled={!recipientUserId}
              >
                Send transfer offer
              </Button>
            </Group>
          </Stack>
        </Modal>
      )}
    </>
  )
}
