import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  SegmentedControl,
  SimpleGrid,
  Text,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconArrowsExchange,
  IconCalendar,
  IconCalendarCheck,
  IconCalendarShare,
  IconCheck,
  IconClockPlus,
  IconPackage,
  IconPlus,
  IconQrcode,
  IconUser,
  IconUserShare,
} from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import {
  acceptTransfer,
  cancelWaitlistEntry,
  fetchAssetSummary,
  fetchMyReservations,
  fetchMyReservationSummary,
  fetchMyWaitlist,
  fetchPendingTransfers,
  nowInBucharest,
  parseCampusLocalDateTime,
  rejectTransfer,
  type AssetSummary,
  type Reservation,
  type ReservationStatus,
  type ReservationSummary,
  type ReservationTransferResponse,
  type WaitlistEntry,
  type WaitlistStatus,
} from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import { useAuth } from '../auth/useAuth.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'
import {
  CLOSE_MINUTES,
  MAX_ACTIVE_RESERVATIONS,
  MAX_DURATION_MINUTES,
  SLOT_STEP_MINUTES,
} from '../components/reservation/bookingRules.ts'
import CancelBundleModal from '../components/reservation/CancelBundleModal.tsx'
import CancelReservationModal from '../components/reservation/CancelReservationModal.tsx'
import ExtendReservationModal from '../components/reservation/ExtendReservationModal.tsx'
import { formatSlotDate, formatSlotTime } from '../components/reservation/reservationFormatters.ts'
import TransferReservationModal from '../components/reservation/TransferReservationModal.tsx'

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
  // Keep null while loading or switching tabs so PagedTable shows the spinner instead of an empty state.
  const [pageData, setPageData] = useState<PageResponse<Reservation> | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [filterTab, setFilterTab] = useState<'all' | 'active' | 'past'>('all')

  const location = useLocation()
  const navigate = useNavigate()
  const [actionNotice, setActionNoticeState] = useState<string | null>(() => {
    const state = location.state as { bookedId?: number; bookedBundleId?: string; bundleItemCount?: number } | null
    if (state?.bookedId) {
      return `Booking confirmed! Reservation #${state.bookedId} has been added to your upcoming bookings.`
    }
    if (state?.bookedBundleId) {
      return state.bundleItemCount
        ? `Bundle booking confirmed! ${state.bundleItemCount} items have been added to your upcoming bookings.`
        : 'Bundle booking confirmed! Your kit items have been added to your upcoming bookings.'
    }
    return null
  })

  // Clear navigation state through React Router (not history.replaceState) so router state stays in sync.
  useEffect(() => {
    if (location.state) {
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null })
    }
  }, [location.pathname, location.search, location.state, navigate])

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

  // Modal targets (each modal owns its own form/submission state while mounted).
  const [cancellingReservation, setCancellingReservation] = useState<Reservation | null>(null)
  const [cancellingBundle, setCancellingBundle] = useState<{ bundleId: string; reservation: Reservation } | null>(null)
  const [extendingReservation, setExtendingReservation] = useState<Reservation | null>(null)
  const [transferringReservation, setTransferringReservation] = useState<Reservation | null>(null)

  // Incoming pending transfers
  const [pendingTransfers, setPendingTransfers] = useState<ReservationTransferResponse[]>([])
  const [transferRespondingId, setTransferRespondingId] = useState<number | null>(null)
  const [transferActionError, setTransferActionError] = useState<unknown>(null)

  // Waitlist entries (#59)
  const [waitlistEntries, setWaitlistEntries] = useState<WaitlistEntry[]>([])
  const [waitlistCancellingId, setWaitlistCancellingId] = useState<number | null>(null)
  const [waitlistActionError, setWaitlistActionError] = useState<unknown>(null)
  // A later success elsewhere on the page hides an old transfer error.
  function setActionNotice(notice: string | null) {
    setActionNoticeState(notice)
    if (notice) {
      setTransferActionError(null)
    }
  }

  function reloadReservations() {
    setError(null)
    setLoading(true)
    setReloadToken((t) => t + 1)
  }

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

    fetchMyWaitlist()
      .then(async (entries) => {
        if (ignore) return
        setWaitlistEntries(entries)
        const missingIds = [...new Set(entries.map((e) => e.assetId).filter((id) => !assetCacheRef.current[id]))]
        if (missingIds.length > 0) {
          const fetched = await Promise.all(missingIds.map((id) => fetchAssetSummary(id).catch(() => null)))
          if (!ignore) {
            const added: Record<number, AssetSummary> = {}
            for (const item of fetched) {
              if (item) added[item.id] = item
            }
            if (Object.keys(added).length > 0) {
              assetCacheRef.current = { ...assetCacheRef.current, ...added }
              setAssetCache((prev) => ({ ...prev, ...added }))
            }
          }
        }
      })
      .catch(() => {
        // Non-blocking fallback if waitlist is unavailable
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
        // Step back to the last non-empty page if an action removes the only row on this page.
        if (response.content.length === 0 && response.totalPages > 0 && pageNumber >= response.totalPages) {
          steppingBack = true
          setPageNumber(response.totalPages - 1)
          return
        }
        setError(null)
        setPageData(response)
        // Render reservation rows immediately; asset names fill in once fetched.
        setLoading(false)

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

  async function handleAcceptTransfer(transferId: number) {
    setTransferRespondingId(transferId)
    setTransferActionError(null)
    try {
      await acceptTransfer(transferId)
      setActionNotice('Transfer accepted! The reservation has been transferred to your upcoming bookings.')
      reloadReservations()
    } catch (err) {
      // Keep transfer errors separate from the reservation list load error.
      setTransferActionError(err)
    } finally {
      setTransferRespondingId(null)
    }
  }

  async function handleRejectTransfer(transferId: number) {
    setTransferRespondingId(transferId)
    setTransferActionError(null)
    try {
      await rejectTransfer(transferId)
      setActionNotice('Transfer offer declined.')
      reloadReservations()
    } catch (err) {
      setTransferActionError(err)
    } finally {
      setTransferRespondingId(null)
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
        const now = nowInBucharest()
        const start = parseCampusLocalDateTime(r.startTime)
        const end = parseCampusLocalDateTime(r.endTime)
        const isBundleItem = Boolean(r.bundleId)
        const hasStartedInBucharest = now >= start
        const hasEndedInBucharest = now >= end
        // Match backend ReservationService.extendReservation: CONFIRMED cannot extend past start+30m,
        // bundle items cannot be extended or transferred individually, and max duration/closing hour caps apply (#362).
        const checkOutWindowClosed =
          r.status === 'CONFIRMED' && now.getTime() > start.getTime() + 30 * 60 * 1000
        const startMins = start.getHours() * 60 + start.getMinutes()
        const endMins = end.getHours() * 60 + end.getMinutes()
        const maxExtendableEndMins = Math.min(startMins + MAX_DURATION_MINUTES, CLOSE_MINUTES)
        const hasExtensionRoom = endMins + SLOT_STEP_MINUTES <= maxExtendableEndMins

        // Desk check-out only opens near the slot (start - 15 min .. start + 30 min in
        // ReservationService.checkOutReservation), so only show the pickup pass on today's CONFIRMED rows.
        const startsTodayInBucharest =
          start.getFullYear() === now.getFullYear() &&
          start.getMonth() === now.getMonth() &&
          start.getDate() === now.getDate()
        const canShowPickupPass = r.status === 'CONFIRMED' && startsTodayInBucharest
        const canCancel = r.status === 'CONFIRMED' && !hasStartedInBucharest
        const canCancelBundle = isBundleItem && canCancel
        const canExtend =
          (r.status === 'CONFIRMED' || r.status === 'CHECKED_OUT') &&
          !isBundleItem &&
          !hasEndedInBucharest &&
          !checkOutWindowClosed &&
          hasExtensionRoom
        const canTransfer = r.status === 'CONFIRMED' && !hasStartedInBucharest && !isBundleItem

        return (
          <Group gap="xs" wrap="wrap">
            {canShowPickupPass && (
              <Tooltip label="Open your rotating 6-digit code and QR pass for the lab desk">
                <Button
                  component={Link}
                  to="/pickup-pass"
                  size="xs"
                  variant="light"
                  color="teal"
                  leftSection={<IconQrcode size={14} />}
                >
                  Show pickup pass
                </Button>
              </Tooltip>
            )}

            {canExtend && (
              <Tooltip label="Extend duration of this active reservation">
                <Button
                  size="xs"
                  variant="light"
                  color="blue"
                  leftSection={<IconClockPlus size={14} />}
                  onClick={() => setExtendingReservation(r)}
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
                  onClick={() => setTransferringReservation(r)}
                >
                  Transfer
                </Button>
              </Tooltip>
            )}

            {canCancel && (
              <Button
                size="xs"
                variant="light"
                color="red"
                onClick={() => setCancellingReservation(r)}
              >
                Cancel
              </Button>
            )}

            {canCancelBundle && r.bundleId && (
              <Tooltip label="Cancel all remaining confirmed items in this bundle">
                <Button
                  size="xs"
                  variant="outline"
                  color="red"
                  leftSection={<IconPackage size={14} />}
                  onClick={() => setCancellingBundle({ bundleId: r.bundleId!, reservation: r })}
                >
                  Cancel bundle
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
          <Group gap="xs" wrap="wrap">
            <Button
              component={Link}
              to="/calendar-sync"
              variant="default"
              leftSection={<IconCalendarShare size={16} />}
            >
              Sync to my calendar
            </Button>
            <Tooltip
              label={`You already have ${MAX_ACTIVE_RESERVATIONS} active bookings (the maximum allowed). Return or cancel a booking before making a new reservation.`}
              disabled={summary.activeCount < MAX_ACTIVE_RESERVATIONS}
            >
              <Button
                component={Link}
                to="/catalog"
                leftSection={<IconPlus size={16} />}
                color={summary.activeCount >= MAX_ACTIVE_RESERVATIONS ? 'gray' : 'campus'}
              >
                Book equipment
              </Button>
            </Tooltip>
          </Group>
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
      <ErrorAlert error={transferActionError} />
      {pendingTransfers.map((t) => (
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

      {/* FIFO Waitlist Entries (#59) */}
      <ErrorAlert error={waitlistActionError} />
      {waitlistEntries.length > 0 && (
        <Card withBorder padding="sm" radius="md" bg="var(--app-surface)" mb="md">
          <Group justify="space-between" mb="xs">
            <Text size="sm" fw={700}>
              My Waitlist ({waitlistEntries.filter((w) => w.status === 'WAITING').length} waiting)
            </Text>
          </Group>
          {waitlistEntries.map((w) => {
            const badgeColor: Record<WaitlistStatus, string> = {
              WAITING: 'blue',
              PROMOTED: 'green',
              CANCELLED: 'gray',
              EXPIRED: 'gray',
              SKIPPED_QUOTA_FULL: 'orange',
            }
            const assetName = assetCache[w.assetId]?.name ?? `Asset #${w.assetId}`
            return (
              <Group key={w.id} justify="space-between" wrap="wrap" py={4}>
                <Group gap="xs" wrap="wrap">
                  <Badge size="xs" color={badgeColor[w.status]}>
                    {w.status}
                  </Badge>
                  <Text size="xs" fw={600}>
                    {assetName}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {formatSlotDate(w.startTime)} • {formatSlotTime(w.startTime, w.endTime)}
                  </Text>
                  {w.promotedReservationId && (
                    <Text size="xs" c="teal">
                      → Reservation #{w.promotedReservationId}
                    </Text>
                  )}
                </Group>
                {w.status === 'WAITING' && (
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    loading={waitlistCancellingId === w.id}
                    onClick={async () => {
                      setWaitlistCancellingId(w.id)
                      setWaitlistActionError(null)
                      try {
                        await cancelWaitlistEntry(w.id)
                        setActionNotice(`Waitlist entry #${w.id} cancelled.`)
                        reloadReservations()
                      } catch (err) {
                        setWaitlistActionError(err)
                      } finally {
                        setWaitlistCancellingId(null)
                      }
                    }}
                  >
                    Leave waitlist
                  </Button>
                )}
              </Group>
            )
          })}
        </Card>
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
                  of {MAX_ACTIVE_RESERVATIONS} max allowed
                </Text>
              </Group>
              {summary.activeCount >= MAX_ACTIVE_RESERVATIONS && (
                <Badge color="orange" size="xs" variant="light" mt={4}>
                  Limit reached ({MAX_ACTIVE_RESERVATIONS}/{MAX_ACTIVE_RESERVATIONS})
                </Badge>
              )}
            </div>
            <ThemeIcon
              size={36}
              radius="md"
              variant="light"
              color={summary.activeCount >= MAX_ACTIVE_RESERVATIONS ? 'orange' : 'campus'}
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

      {cancellingReservation && (
        <CancelReservationModal
          reservation={cancellingReservation}
          assetName={assetCache[cancellingReservation.assetId]?.name}
          onClose={() => setCancellingReservation(null)}
          onCancelled={(cancelledId) => {
            setCancellingReservation(null)
            setActionNotice(`Reservation #${cancelledId} was successfully cancelled.`)
            reloadReservations()
          }}
        />
      )}

      {cancellingBundle && (
        <CancelBundleModal
          bundleId={cancellingBundle.bundleId}
          reservation={cancellingBundle.reservation}
          onClose={() => setCancellingBundle(null)}
          onCancelled={() => {
            setCancellingBundle(null)
            setActionNotice('Bundle booking cancelled — remaining confirmed items released.')
            reloadReservations()
          }}
        />
      )}

      {extendingReservation && (
        <ExtendReservationModal
          reservation={extendingReservation}
          assetName={assetCache[extendingReservation.assetId]?.name}
          onClose={() => setExtendingReservation(null)}
          onExtended={(updated) => {
            setExtendingReservation(null)
            setActionNotice(
              `Reservation #${updated.id} extended successfully until ${formatSlotTime(updated.startTime, updated.endTime)}.`,
            )
            reloadReservations()
          }}
        />
      )}

      {transferringReservation && (
        <TransferReservationModal
          reservation={transferringReservation}
          assetName={assetCache[transferringReservation.assetId]?.name}
          onClose={() => setTransferringReservation(null)}
          onTransferred={(resId, toId) => {
            setTransferringReservation(null)
            setActionNotice(`Transfer offer for Reservation #${resId} sent to Student #${toId}.`)
            reloadReservations()
          }}
        />
      )}
    </>
  )
}
