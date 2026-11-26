import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Grid,
  Group,
  Paper,
  Progress,
  Stack,
  Text,
  Tooltip,
} from '@mantine/core'
import {
  IconArrowLeft,
  IconCalendar,
  IconCheck,
  IconChevronLeft,
  IconChevronRight,
  IconClock,
  IconLock,
  IconLockOpen,
  IconX,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client.ts'
import {
  checkAvailability,
  confirmHold,
  createReservation,
  fetchAssetSummary,
  fetchNextFreeSlots,
  joinWaitlist,
  nowInBucharest,
  parseCampusLocalDateTime,
  type AssetSummary,
  type AvailabilityResponse,
  type FreeSlotOption,
  type Reservation,
} from '../api/reservations.ts'
import ErrorAlert from './ErrorAlert.tsx'
import {
  BOOKING_DURATIONS,
  CLOSE_MINUTES,
  CLOSING_TIME,
  DEFAULT_DURATION_MINUTES,
  DEFAULT_START_TIME,
  HOLD_DURATION_SECONDS,
  LAST_START_MINUTES,
  MAX_ACTIVE_RESERVATIONS,
  MAX_ADVANCE_DAYS,
  OPEN_HOUR,
  OPENING_TIME,
  SLOT_STEP_MINUTES,
  TIME_STEPS,
} from './reservation/bookingRules.ts'
import { formatSlotDate, formatSlotTime, MONTH_NAMES } from './reservation/reservationFormatters.ts'
import { useSlotHold } from './reservation/useSlotHold.ts'

type Props = {
  asset: AssetSummary
  onBack?: () => void
  onSuccess?: (reservation: Reservation) => void
}

const WEEKDAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']

function getMonday(d: Date): Date {
  const date = new Date(d)
  const day = date.getDay()
  const diff = date.getDate() - day + (day === 0 ? -6 : 1) // adjust when day is sunday
  date.setDate(diff)
  date.setHours(0, 0, 0, 0)
  return date
}

function formatDateKey(d: Date): string {
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function toSlotDate(day: Date, timeStr: string): Date {
  const [h, m] = timeStr.split(':').map(Number)
  return new Date(day.getFullYear(), day.getMonth(), day.getDate(), h, m, 0)
}

function addMinutesToTimeString(timeStr: string, minutes: number): string {
  const [h, m] = timeStr.split(':').map(Number)
  const total = h * 60 + m + minutes
  const newH = Math.floor(total / 60)
  const newM = total % 60
  return `${String(newH).padStart(2, '0')}:${String(newM).padStart(2, '0')}`
}

function findFirstValidSlotForDay(targetDay: Date, today: Date): string | null {
  const isToday = formatDateKey(targetDay) === formatDateKey(today)
  if (!isToday) {
    return DEFAULT_START_TIME
  }
  const currentTotalMins = today.getHours() * 60 + today.getMinutes()
  for (const step of TIME_STEPS) {
    const [h, m] = step.split(':').map(Number)
    const slotMins = h * 60 + m
    if (slotMins > currentTotalMins && slotMins <= LAST_START_MINUTES) {
      return step
    }
  }
  // Bug fixed (#362 §3): after 19:30 Bucharest no future slot remains today; return null
  // instead of falling back to 09:00 in the past.
  return null
}

function getInitialSchedule(today: Date): { day: string; time: string; monday: Date } {
  const dayOfWeek = today.getDay()
  const isWeekend = dayOfWeek === 0 || dayOfWeek === 6
  const currentTotalMins = today.getHours() * 60 + today.getMinutes()
  const isTooLateToday = currentTotalMins >= LAST_START_MINUTES

  if (isWeekend) {
    const nextMon = getMonday(today)
    nextMon.setDate(nextMon.getDate() + 7)
    return {
      day: formatDateKey(nextMon),
      time: DEFAULT_START_TIME,
      monday: nextMon,
    }
  }

  if (isTooLateToday) {
    if (dayOfWeek === 5) {
      const nextMon = getMonday(today)
      nextMon.setDate(nextMon.getDate() + 7)
      return {
        day: formatDateKey(nextMon),
        time: DEFAULT_START_TIME,
        monday: nextMon,
      }
    }
    const tomorrow = new Date(today)
    tomorrow.setDate(tomorrow.getDate() + 1)
    return {
      day: formatDateKey(tomorrow),
      time: DEFAULT_START_TIME,
      monday: getMonday(today),
    }
  }

  return {
    day: formatDateKey(today),
    time: findFirstValidSlotForDay(today, today) ?? DEFAULT_START_TIME,
    monday: getMonday(today),
  }
}

/**
 * Google Calendar-style weekly equipment scheduling and booking component.
 * Calin's booking view ready to be embedded into Liviu's catalog equipment pages.
 */
export default function CalendarBookingView({ asset: initialAsset, onBack, onSuccess }: Props) {
  const navigate = useNavigate()
  // Allows in-place switching to a free same-model substitute unit while keeping the selected slot
  const [switchedAsset, setSwitchedAsset] = useState<{
    baseAssetId: number
    selected: AssetSummary
  } | null>(null)
  const asset =
    switchedAsset && switchedAsset.baseAssetId === initialAsset.id
      ? switchedAsset.selected
      : initialAsset
  const [substituteState, setSubstituteState] = useState<{
    key: string
    items: AssetSummary[]
  }>({ key: '', items: [] })
  const [nextSlotsState, setNextSlotsState] = useState<{
    key: string
    items: FreeSlotOption[]
  }>({ key: '', items: [] })
  const [today, setToday] = useState(() => nowInBucharest())
  const initialSchedule = useMemo(() => getInitialSchedule(today), [today])
  const maxBookingDate = useMemo(
    () =>
      new Date(
        today.getFullYear(),
        today.getMonth(),
        today.getDate() + MAX_ADVANCE_DAYS,
        today.getHours(),
        today.getMinutes(),
        today.getSeconds(),
      ),
    [today],
  )

  // Current view's Monday
  const [currentMonday, setCurrentMonday] = useState(() => initialSchedule.monday)

  // Selected slot state
  const [selectedDay, setSelectedDay] = useState<string>(() => initialSchedule.day)
  const [selectedStartTime, setSelectedStartTime] = useState<string>(() => initialSchedule.time)
  const [durationMinutes, setDurationMinutes] = useState<number>(DEFAULT_DURATION_MINUTES)

  const [availability, setAvailability] = useState<{
    checking: boolean
    data: AvailabilityResponse | null
    error: unknown
  }>({ checking: true, data: null, error: null })
  const [availabilityTick, setAvailabilityTick] = useState(0)

  const [bookingLoading, setBookingLoading] = useState(false)
  const [bookingError, setBookingError] = useState<unknown>(null)
  const [waitlistLoading, setWaitlistLoading] = useState(false)
  const [waitlistError, setWaitlistError] = useState<unknown>(null)
  const [waitlistJoined, setWaitlistJoined] = useState<{ key: string; entryId: number } | null>(null)

  // Compute the 5 weekdays for the active week view.
  const weekDays = useMemo(() => {
    return Array.from({ length: 5 }, (_, i) => {
      const d = new Date(currentMonday)
      d.setDate(d.getDate() + i)
      return d
    })
  }, [currentMonday])

  // Calculation for closing time (20:00) constraint.
  const maxAvailableMins = useMemo(() => {
    const [h, m] = selectedStartTime.split(':').map(Number)
    const startMins = h * 60 + m
    return Math.max(0, CLOSE_MINUTES - startMins)
  }, [selectedStartTime])

  // If start is e.g. 19:30, automatically cap duration at 30 min (and grey out longer options).
  const effectiveDuration = useMemo(() => {
    if (durationMinutes <= maxAvailableMins) {
      return durationMinutes
    }
    const allowed = BOOKING_DURATIONS.map((d) => d.value).filter((val) => val <= maxAvailableMins)
    return allowed[allowed.length - 1] ?? SLOT_STEP_MINUTES
  }, [durationMinutes, maxAvailableMins])

  const startIso = useMemo(() => `${selectedDay}T${selectedStartTime}:00`, [selectedDay, selectedStartTime])
  const endIso = useMemo(() => {
    const endTimeStr = addMinutesToTimeString(selectedStartTime, effectiveDuration)
    return `${selectedDay}T${endTimeStr}:00`
  }, [selectedDay, selectedStartTime, effectiveDuration])

  const {
    activeHold,
    holdRemainingSeconds,
    holdLoading,
    holdError,
    handleAcquireHold,
    handleReleaseHold,
    discardActiveHold,
    clearActiveHold,
  } = useSlotHold({ assetId: asset.id, startIso, endIso })

  // Check availability when slot changes
  useEffect(() => {
    let cancelled = false

    checkAvailability({
      assetId: asset.id,
      startTime: startIso,
      endTime: endIso,
    })
      .then((res) => {
        if (!cancelled) {
          setAvailability({ checking: false, data: res, error: null })
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setAvailability({ checking: false, data: null, error: err })
        }
      })

    return () => {
      cancelled = true
    }
  }, [asset.id, startIso, endIso, availabilityTick])

  function selectSlot(day: string, time: string) {
    if (holdLoading || bookingLoading) return
    discardActiveHold()
    setSelectedDay(day)
    setSelectedStartTime(time)
    setBookingError(null)
    setWaitlistError(null)
    setAvailability((prev) => ({ ...prev, checking: true, error: null }))
    setAvailabilityTick((t) => t + 1)
  }

  function handleDurationChange(val: number) {
    if (holdLoading || bookingLoading || val === durationMinutes) return
    discardActiveHold()
    setDurationMinutes(val)
    setBookingError(null)
    setWaitlistError(null)
    setAvailability((prev) => ({ ...prev, checking: true, error: null }))
    // Increment availabilityTick so changing duration when endIso stays capped at 20:00 still finishes re-checking.
    setAvailabilityTick((t) => t + 1)
  }

  const isPrevWeekDisabled = currentMonday.getTime() <= initialSchedule.monday.getTime()
  const isNextWeekDisabled =
    new Date(
      currentMonday.getFullYear(),
      currentMonday.getMonth(),
      currentMonday.getDate() + 7,
      OPEN_HOUR,
      0,
      0,
    ) > maxBookingDate

  function handlePrevWeek() {
    if (holdLoading || bookingLoading) return
    if (isPrevWeekDisabled) return
    const prevMon = new Date(currentMonday)
    prevMon.setDate(prevMon.getDate() - 7)
    setCurrentMonday(prevMon)

    // Shift selectedDay back by 7 days in local time so it stays in the displayed week
    const currSel = parseCampusLocalDateTime(selectedDay)
    const prevSel = new Date(currSel)
    prevSel.setDate(currSel.getDate() - 7)

    const firstValidDay = parseCampusLocalDateTime(initialSchedule.day)
    const targetDay = prevSel >= firstValidDay ? prevSel : firstValidDay
    let targetKey = formatDateKey(targetDay)
    let targetTime = selectedStartTime
    if (targetKey === formatDateKey(today)) {
      const currentTotalMins = today.getHours() * 60 + today.getMinutes()
      const [selH, selM] = selectedStartTime.split(':').map(Number)
      if (selH * 60 + selM <= currentTotalMins) {
        const firstTodaySlot = findFirstValidSlotForDay(today, today)
        if (firstTodaySlot !== null) {
          targetTime = firstTodaySlot
        } else {
          targetKey = initialSchedule.day
          targetTime = initialSchedule.time
        }
      }
    }
    selectSlot(targetKey, targetTime)
  }

  function handleNextWeek() {
    if (holdLoading || bookingLoading) return
    if (isNextWeekDisabled) return
    const nextMon = new Date(currentMonday)
    nextMon.setDate(nextMon.getDate() + 7)
    setCurrentMonday(nextMon)

    // Shift selectedDay forward by 7 days in local time and clamp against the 14-day horizon
    const currSel = parseCampusLocalDateTime(selectedDay)
    const nextSel = new Date(currSel)
    nextSel.setDate(currSel.getDate() + 7)

    const targetDay = toSlotDate(nextSel, OPENING_TIME) <= maxBookingDate ? nextSel : nextMon
    const targetTime =
      toSlotDate(targetDay, selectedStartTime) <= maxBookingDate ? selectedStartTime : OPENING_TIME
    selectSlot(formatDateKey(targetDay), targetTime)
  }

  // Recompute from a fresh Bucharest 'now' so weekends and late evenings jump to the next valid weekday slot.
  function handleToday() {
    if (holdLoading || bookingLoading) return
    const freshToday = nowInBucharest()
    const schedule = getInitialSchedule(freshToday)
    setToday(freshToday)
    setCurrentMonday(schedule.monday)
    selectSlot(schedule.day, schedule.time)
  }

  async function handleConfirmBooking() {
    setBookingLoading(true)
    setBookingError(null)
    try {
      let res: Reservation
      if (activeHold) {
        res = await confirmHold(activeHold.id)
      } else {
        res = await createReservation({
          assetId: asset.id,
          startTime: startIso,
          endTime: endIso,
        })
      }
      clearActiveHold()
      if (onSuccess) {
        onSuccess(res)
      } else {
        // Default redirect preserves SPA navigation state and passes bookedId for confirmation banner
        navigate('/reservations', { state: { bookedId: res.id } })
      }
    } catch (err) {
      setBookingError(err)
    } finally {
      setBookingLoading(false)
    }
  }

  const isSlotAvailable = availability.data?.available === true
  const currentSlotKey = `${asset.id}:${startIso}:${endIso}`
  const hasJoinedWaitlistForSlot = waitlistJoined?.key === currentSlotKey
  const conflictErrorCode =
    bookingError instanceof ApiError
      ? bookingError.code
      : holdError instanceof ApiError
        ? holdError.code
        : null
  const showWaitlistPanel =
    (!availability.checking &&
      !isSlotAvailable &&
      availability.data?.conflictReason === 'RESERVATION_CONFLICT') ||
    conflictErrorCode === 'RESERVATION_CONFLICT' ||
    conflictErrorCode === 'SLOT_HELD'

  async function handleJoinWaitlist() {
    setWaitlistLoading(true)
    setWaitlistError(null)
    try {
      const entry = await joinWaitlist({
        assetId: asset.id,
        startTime: startIso,
        endTime: endIso,
      })
      setWaitlistJoined({ key: currentSlotKey, entryId: entry.id })
    } catch (err) {
      setWaitlistError(err)
    } finally {
      setWaitlistLoading(false)
    }
  }

  const substituteKey =
    !availability.checking && availability.data && !availability.data.available
      ? `${asset.id}:${startIso}:${endIso}:${availability.data.availableAssetIds.slice(0, 3).join(',')}`
      : ''

  useEffect(() => {
    if (!substituteKey || !availability.data) {
      return
    }
    const subIds = availability.data.availableAssetIds.slice(0, 3)
    if (subIds.length === 0) {
      return
    }

    let cancelled = false
    Promise.all(subIds.map((id) => fetchAssetSummary(id).catch(() => null))).then((items) => {
      if (!cancelled) {
        setSubstituteState({
          key: substituteKey,
          items: items.filter((item): item is AssetSummary => item !== null),
        })
      }
    })

    return () => {
      cancelled = true
    }
  }, [substituteKey, availability.data])

  const substituteAssets =
    substituteKey && substituteState.key === substituteKey ? substituteState.items : []
  const totalAvailableSubstitutes = Math.max(
    availability.data?.availableAssetIds.length ?? 0,
    substituteAssets.length,
  )

  const conflictErrorCode =
    bookingError instanceof ApiError
      ? bookingError.code
      : holdError instanceof ApiError
        ? holdError.code
        : null

  const hasSlotConflict =
    (!availability.checking &&
      Boolean(availability.data) &&
      !availability.data!.available &&
      availability.data!.conflictReason === 'RESERVATION_CONFLICT') ||
    conflictErrorCode === 'RESERVATION_CONFLICT' ||
    conflictErrorCode === 'SLOT_HELD'

  const nextSlotsKey = hasSlotConflict ? `${asset.id}:${startIso}:${effectiveDuration}:${availabilityTick}` : ''

  useEffect(() => {
    if (!nextSlotsKey) return
    let cancelled = false
    fetchNextFreeSlots(asset.id, startIso, effectiveDuration, 4)
      .then((res) => {
        if (!cancelled) {
          setNextSlotsState({ key: nextSlotsKey, items: res.bookable ? res.slots : [] })
        }
      })
      .catch(() => {
        // Non-blocking recommendation helper; clear any stale items for this key on failure.
        if (!cancelled) {
          setNextSlotsState({ key: nextSlotsKey, items: [] })
        }
      })
    return () => {
      cancelled = true
    }
  }, [nextSlotsKey, asset.id, startIso, effectiveDuration])

  const nextFreeSlots = nextSlotsKey && nextSlotsState.key === nextSlotsKey ? nextSlotsState.items : []

  function handleSelectRecommendedSlot(slot: FreeSlotOption) {
    if (holdLoading || bookingLoading) return
    const parsedStart = parseCampusLocalDateTime(slot.startTime)
    const parsedEnd = parseCampusLocalDateTime(slot.endTime)
    const hhmm = `${String(parsedStart.getHours()).padStart(2, '0')}:${String(parsedStart.getMinutes()).padStart(2, '0')}`
    const slotDuration = Math.round((parsedEnd.getTime() - parsedStart.getTime()) / 60000)
    setCurrentMonday(getMonday(parsedStart))
    setDurationMinutes(slotDuration)
    selectSlot(formatDateKey(parsedStart), hhmm)
  }

  function handleSwitchToSubstitute(sub: AssetSummary) {
    if (holdLoading || bookingLoading) return
    discardActiveHold()
    setBookingError(null)
    setWaitlistError(null)
    setAvailability((prev) => ({ ...prev, checking: true, error: null }))
    setSwitchedAsset(sub.id === initialAsset.id ? null : { baseAssetId: initialAsset.id, selected: sub })
  }

  return (
    <Stack gap="lg">
      {/* Top Header with Back Button (if provided) */}
      <Group justify="space-between" align="center">
        {onBack ? (
          <Button
            variant="subtle"
            leftSection={<IconArrowLeft size={16} />}
            onClick={onBack}
          >
            Back to equipment
          </Button>
        ) : (
          <Box />
        )}

        <Badge
          size="lg"
          color={asset.status === 'AVAILABLE' ? 'green' : 'orange'}
        >
          {asset.status === 'AVAILABLE' ? 'Available for booking' : asset.status}
        </Badge>
      </Group>

      {/* Equipment info banner */}
      <Paper withBorder p="md" radius="md" bg="var(--app-surface)">
        <Group justify="space-between" align="center">
          <div>
            <Text size="xl" fw={700}>
              {asset.name}
            </Text>
            <Text size="sm" c="dimmed">
              Model: <b>{asset.model}</b> · Serial: <span style={{ fontFamily: 'monospace' }}>{asset.serialNumber}</span>
            </Text>
          </div>
        </Group>
      </Paper>

      {asset.status !== 'AVAILABLE' && (
        <Alert color="orange" title="Equipment Unavailable">
          This equipment is currently {asset.status === 'UNDER_MAINTENANCE' ? 'under maintenance' : 'retired'} and cannot accept new reservations.
        </Alert>
      )}

      {/* Booking Layout: Google Calendar-style Schedule + Confirmation Panel */}
      <Grid gap="lg">
        {/* Left Column: Google Calendar Interactive Week View */}
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Paper withBorder p="md" radius="md" bg="var(--app-surface)">
            {/* Calendar Controls */}
            <Group justify="space-between" mb="md" wrap="wrap">
              <Group gap="xs">
                <ActionIcon
                  variant="default"
                  size="lg"
                  onClick={handlePrevWeek}
                  disabled={isPrevWeekDisabled || holdLoading || bookingLoading}
                  aria-label="Previous week"
                >
                  <IconChevronLeft size={18} />
                </ActionIcon>
                <Button
                  variant="default"
                  size="xs"
                  onClick={handleToday}
                  disabled={holdLoading || bookingLoading}
                >
                  Today
                </Button>
                <ActionIcon
                  variant="default"
                  size="lg"
                  onClick={handleNextWeek}
                  disabled={isNextWeekDisabled || holdLoading || bookingLoading}
                  aria-label="Next week"
                >
                  <IconChevronRight size={18} />
                </ActionIcon>

                <Text fw={600} size="md" ml="xs">
                  {MONTH_NAMES[weekDays[0].getMonth()]} {weekDays[0].getDate()} –{' '}
                  {weekDays[4].getMonth() !== weekDays[0].getMonth()
                    ? `${MONTH_NAMES[weekDays[4].getMonth()]} `
                    : ''}
                  {weekDays[4].getDate()}, {weekDays[4].getFullYear()}
                </Text>
              </Group>

              {/* Duration selector with auto-greyed-out constraint */}
              <Group gap="xs" align="center">
                <Text size="xs" fw={600} c="dimmed">
                  Duration:
                </Text>
                <Group gap={4}>
                  {BOOKING_DURATIONS.map((dur) => {
                    const isDisabled = dur.value > maxAvailableMins || holdLoading || bookingLoading
                    const isSelected = effectiveDuration === dur.value
                    return (
                      <Tooltip
                        key={dur.value}
                        label={
                          isDisabled
                            ? `Labs close at ${CLOSING_TIME}. At ${selectedStartTime}, max duration is ${maxAvailableMins} min.`
                            : dur.label
                        }
                        disabled={!isDisabled}
                      >
                        <Button
                          size="xs"
                          variant={isSelected ? 'filled' : 'default'}
                          color={isSelected ? 'campus' : undefined}
                          disabled={isDisabled}
                          onClick={() => handleDurationChange(dur.value)}
                          style={{
                            opacity: isDisabled ? 0.4 : 1,
                            cursor: isDisabled ? 'not-allowed' : 'pointer',
                          }}
                        >
                          {dur.label}
                        </Button>
                      </Tooltip>
                    )
                  })}
                </Group>
              </Group>
            </Group>

            {/* Calendar Grid Container */}
            <Box
              style={{
                display: 'grid',
                gridTemplateColumns: '60px repeat(5, 1fr)',
                border: '1px solid var(--mantine-color-default-border)',
                borderRadius: '8px',
                overflow: 'hidden',
              }}
            >
              {/* Header Row: Corner + 5 Day Headers */}
              <Box
                style={{
                  padding: '8px',
                  borderBottom: '1px solid var(--mantine-color-default-border)',
                  borderRight: '1px solid var(--mantine-color-default-border)',
                  background: 'var(--mantine-color-body)',
                }}
              />
              {weekDays.map((d) => {
                const dayKey = formatDateKey(d)
                const isSelectedDay = dayKey === selectedDay
                const isPast = d < new Date(today.getFullYear(), today.getMonth(), today.getDate())
                // Check the day's first slot (08:00) for the header; each 30-min cell checks its exact timestamp.
                const isOutOfHorizon = toSlotDate(d, OPENING_TIME) > maxBookingDate
                const isToday = formatDateKey(d) === formatDateKey(today)
                const firstTodaySlot = isToday ? findFirstValidSlotForDay(d, today) : DEFAULT_START_TIME
                const isHeaderDisabled = isPast || isOutOfHorizon || firstTodaySlot === null

                return (
                  <Box
                    key={dayKey}
                    style={{
                      padding: '8px 4px',
                      textAlign: 'center',
                      borderBottom: '1px solid var(--mantine-color-default-border)',
                      borderRight: '1px solid var(--mantine-color-default-border)',
                      background: isSelectedDay
                        ? 'var(--mantine-color-campus-light)'
                        : isToday
                          ? 'var(--mantine-color-blue-light-hover)'
                          : 'var(--mantine-color-body)',
                      cursor: isHeaderDisabled ? 'not-allowed' : 'pointer',
                      opacity: isHeaderDisabled ? 0.5 : 1,
                    }}
                    onClick={() => {
                      if (!isHeaderDisabled) {
                        let newTime = selectedStartTime
                        if (isToday) {
                          const currentTotalMins = today.getHours() * 60 + today.getMinutes()
                          const [selH, selM] = selectedStartTime.split(':').map(Number)
                          if (selH * 60 + selM <= currentTotalMins) {
                            if (firstTodaySlot === null) return
                            newTime = firstTodaySlot
                          }
                        }
                        if (toSlotDate(d, newTime) > maxBookingDate) {
                          newTime = OPENING_TIME
                        }
                        selectSlot(dayKey, newTime)
                      }
                    }}
                  >
                    <Text size="xs" fw={700} c={isSelectedDay ? 'campus' : 'dimmed'}>
                      {WEEKDAY_NAMES[d.getDay() === 0 ? 6 : d.getDay() - 1]}
                    </Text>
                    <Text size="sm" fw={isSelectedDay || isToday ? 700 : 500}>
                      {d.getDate()} {MONTH_NAMES[d.getMonth()]}
                    </Text>
                  </Box>
                )
              })}

              {/* Time Rows */}
              {TIME_STEPS.map((stepTime) => {
                const isHourMark = stepTime.endsWith(':00')

                return (
                  <Box key={stepTime} style={{ display: 'contents' }}>
                    {/* Time Label on left axis */}
                    <Box
                      style={{
                        padding: '4px 6px',
                        fontSize: '11px',
                        textAlign: 'right',
                        color: 'var(--mantine-color-dimmed)',
                        borderBottom: isHourMark
                          ? '1px solid var(--mantine-color-default-border)'
                          : '1px dashed var(--mantine-color-default-border)',
                        borderRight: '1px solid var(--mantine-color-default-border)',
                        background: 'var(--mantine-color-body)',
                        userSelect: 'none',
                      }}
                    >
                      {stepTime}
                    </Box>

                    {/* 5 Day Cells for this time step */}
                    {weekDays.map((d) => {
                      const dayKey = formatDateKey(d)
                      const isPastDay = d < new Date(today.getFullYear(), today.getMonth(), today.getDate())
                      const isToday = formatDateKey(d) === formatDateKey(today)
                      const [stepH, stepM] = stepTime.split(':').map(Number)
                      const currentTotalMins = today.getHours() * 60 + today.getMinutes()
                      const stepTotalMins = stepH * 60 + stepM
                      const isPastTimeToday = isToday && stepTotalMins <= currentTotalMins
                      const isPastSlot = isPastDay || isPastTimeToday
                      const isOutOfHorizon = toSlotDate(d, stepTime) > maxBookingDate
                      const isCellDisabled = isPastSlot || isOutOfHorizon
                      const isSelectedSlot = dayKey === selectedDay && stepTime === selectedStartTime

                      // Check if this cell is inside the selected time block:
                      const [selH, selM] = selectedStartTime.split(':').map(Number)
                      const selStartMins = selH * 60 + selM
                      const isInsideSelectedBlock =
                        dayKey === selectedDay &&
                        stepTotalMins >= selStartMins &&
                        stepTotalMins < selStartMins + effectiveDuration

                      return (
                        <Box
                          key={`${dayKey}-${stepTime}`}
                          data-time={stepTime}
                          data-day={dayKey}
                          onClick={() => {
                            if (!isCellDisabled) {
                              selectSlot(dayKey, stepTime)
                            }
                          }}
                          style={{
                            height: '32px',
                            borderBottom: isHourMark
                              ? '1px solid var(--mantine-color-default-border)'
                              : '1px dashed var(--mantine-color-default-border)',
                            borderRight: '1px solid var(--mantine-color-default-border)',
                            background: isInsideSelectedBlock
                              ? 'var(--mantine-color-campus-filled)'
                              : isSelectedSlot
                                ? 'var(--mantine-color-campus-light)'
                                : 'transparent',
                            color: isInsideSelectedBlock ? '#ffffff' : undefined,
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            cursor: isCellDisabled ? 'not-allowed' : 'pointer',
                            opacity: isCellDisabled ? 0.35 : 1,
                            fontSize: '11px',
                            fontWeight: 600,
                            transition: 'background 0.15s ease',
                          }}
                        >
                          {isSelectedSlot && (
                            <Group gap={2} wrap="nowrap">
                              <IconCheck size={12} />
                              <span>Start</span>
                            </Group>
                          )}
                        </Box>
                      )
                    })}
                  </Box>
                )
              })}
            </Box>

            <Group justify="space-between" mt="sm">
              <Text size="xs" c="dimmed">
                💡 Click any {SLOT_STEP_MINUTES}-min slot cell on the grid to set your reservation start time.
              </Text>
              <Group gap="xs">
                <Group gap={4}>
                  <Box w={12} h={12} style={{ background: 'var(--mantine-color-campus-filled)', borderRadius: 2 }} />
                  <Text size="xs" c="dimmed">
                    Your selected window
                  </Text>
                </Group>
                <Group gap={4}>
                  <Box w={12} h={12} style={{ background: 'var(--mantine-color-default-border)', borderRadius: 2 }} />
                  <Text size="xs" c="dimmed">
                    Available slot
                  </Text>
                </Group>
              </Group>
            </Group>
          </Paper>
        </Grid.Col>

        {/* Right Column: Booking Confirmation & Rule Checks */}
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card withBorder p="lg" radius="md" bg="var(--app-surface)" style={{ position: 'sticky', top: 80 }}>
            <Text fw={700} size="lg" mb="sm">
              Confirm Reservation
            </Text>

            <ErrorAlert error={bookingError} />
            <ErrorAlert error={availability.error} />

            <Stack gap="sm">
              <div>
                <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                  Selected Equipment
                </Text>
                <Text fw={600} size="md">
                  {asset.name}
                </Text>
                <Text size="xs" c="dimmed">
                  {asset.model}
                </Text>
                {asset.id !== initialAsset.id && (
                  <Group gap={6} mt={4}>
                    <Badge size="xs" color="teal" variant="light">
                      Switched unit
                    </Badge>
                    <Button
                      size="xs"
                      variant="subtle"
                      disabled={holdLoading || bookingLoading}
                      onClick={() => handleSwitchToSubstitute(initialAsset)}
                    >
                      Back to {initialAsset.name}
                    </Button>
                  </Group>
                )}
              </div>

              <div>
                <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                  Date
                </Text>
                <Group gap="xs" mt={2}>
                  <IconCalendar size={16} />
                  <Text fw={600}>{formatSlotDate(selectedDay)}</Text>
                </Group>
              </div>

              <div>
                <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                  Time Slot
                </Text>
                <Group gap="xs" mt={2}>
                  <IconClock size={16} />
                  <Text fw={600}>
                    {selectedStartTime} – {addMinutesToTimeString(selectedStartTime, effectiveDuration)} ({effectiveDuration} min)
                  </Text>
                </Group>
              </div>

              {/* Live Availability Status */}
              <Alert
                color={availability.checking ? 'gray' : isSlotAvailable ? 'green' : 'red'}
                icon={
                  availability.checking ? (
                    <IconClock size={18} />
                  ) : isSlotAvailable ? (
                    <IconCheck size={18} />
                  ) : (
                    <IconX size={18} />
                  )
                }
                title={
                  availability.checking
                    ? 'Checking slot availability...'
                    : isSlotAvailable
                      ? 'Slot is available!'
                      : 'Slot is not available'
                }
              >
                {!availability.checking && isSlotAvailable && (
                  <Text size="xs">
                    This equipment is free and ready to be booked for the chosen interval.
                  </Text>
                )}
                {!availability.checking && !isSlotAvailable && (
                  <Text size="xs">
                    {availability.data?.conflictReason === 'RESERVATION_CONFLICT'
                      ? 'Another student has already reserved this equipment during this time window.'
                      : availability.data?.conflictReason === 'ASSET_NOT_BOOKABLE'
                        ? 'Equipment is currently under maintenance or retired.'
                        : 'Cannot book this slot (outside opening hours, limit reached, or invalid interval).'}
                  </Text>
                )}
              </Alert>

              {!availability.checking && !isSlotAvailable && substituteAssets.length > 0 && (
                <Paper withBorder p="xs" radius="sm" bg="var(--mantine-color-teal-light)">
                  <Stack gap={6}>
                    <Group justify="space-between" gap="xs">
                      <Text size="xs" fw={700} c="teal">
                        Free units of model {asset.model} for this slot:
                      </Text>
                      <Badge size="xs" color="teal" variant="light">
                        {totalAvailableSubstitutes} available
                      </Badge>
                    </Group>
                    {substituteAssets.map((sub) => (
                      <Group
                        key={sub.id}
                        justify="space-between"
                        align="center"
                        wrap="nowrap"
                        p={6}
                        style={{
                          backgroundColor: 'var(--app-surface)',
                          borderRadius: 'var(--mantine-radius-sm)',
                          border: '1px solid var(--mantine-color-teal-light-hover)',
                        }}
                      >
                        <Box style={{ minWidth: 0 }}>
                          <Group gap={6} wrap="nowrap">
                            <Text size="xs" fw={600} truncate>
                              {sub.name}
                            </Text>
                            {asset.labRoomId != null && sub.labRoomId === asset.labRoomId && (
                              <Badge size="xs" color="teal" variant="filled">
                                Same room
                              </Badge>
                            )}
                          </Group>
                          <Text size="xs" c="dimmed" ff="monospace">
                            SN: {sub.serialNumber}
                          </Text>
                        </Box>
                        <Button
                          size="xs"
                          variant="light"
                          color="teal"
                          disabled={holdLoading || bookingLoading}
                          onClick={() => handleSwitchToSubstitute(sub)}
                        >
                          Switch unit
                        </Button>
                      </Group>
                    ))}
                    {totalAvailableSubstitutes > substituteAssets.length && (
                      <Text size="xs" c="dimmed">
                        Showing the first {substituteAssets.length} of {totalAvailableSubstitutes} free units.
                      </Text>
                    )}
                  </Stack>
                </Paper>
              )}

              {!availability.checking && hasSlotConflict && nextFreeSlots.length > 0 && (
                <Paper withBorder p="xs" radius="sm" bg="var(--mantine-color-blue-light)">
                  <Stack gap={6}>
                    <Text size="xs" fw={700} c="blue">
                      Next free slots on this unit:
                    </Text>
                    {nextFreeSlots.map((slot) => (
                      <Button
                        key={slot.startTime}
                        size="xs"
                        variant="light"
                        color="blue"
                        justify="space-between"
                        fullWidth
                        disabled={holdLoading || bookingLoading}
                        onClick={() => handleSelectRecommendedSlot(slot)}
                      >
                        {formatSlotDate(slot.startTime)} • {formatSlotTime(slot.startTime, slot.endTime)}
                      </Button>
                    ))}
                  </Stack>
                </Paper>
              )}

              {showWaitlistPanel && (
                <Paper withBorder p="xs" radius="sm">
                  <Stack gap={6}>
                    <Text size="xs" fw={600}>
                      Need this exact slot?
                    </Text>
                    <ErrorAlert error={waitlistError} />
                    {hasJoinedWaitlistForSlot ? (
                      <Alert color="blue" variant="light" p="xs">
                        <Group justify="space-between" gap="xs" wrap="wrap">
                          <Text size="xs">
                            Added to FIFO waitlist (#{waitlistJoined?.entryId}). You will be promoted automatically if
                            the slot opens up.
                          </Text>
                          <Button size="xs" variant="subtle" onClick={() => navigate('/reservations')}>
                            View in My reservations
                          </Button>
                        </Group>
                      </Alert>
                    ) : (
                      <Button
                        size="xs"
                        variant="light"
                        color="blue"
                        fullWidth
                        loading={waitlistLoading}
                        disabled={holdLoading || bookingLoading}
                        onClick={() => void handleJoinWaitlist()}
                      >
                        Waitlist
                      </Button>
                    )}
                  </Stack>
                </Paper>
              )}

              {/* Rules reminders */}
              <Stack gap={4} p="xs" style={{ background: 'var(--mantine-color-body)', borderRadius: 6 }}>
                <Text size="xs" c="dimmed">
                  ✓ Labs open: <b>{OPENING_TIME} – {CLOSING_TIME} (Mon–Fri)</b>
                </Text>
                <Text size="xs" c="dimmed">
                  ✓ Maximum <b>{MAX_ACTIVE_RESERVATIONS} active reservations</b> per student
                </Text>
                <Text size="xs" c="dimmed">
                  ✓ Book up to <b>{MAX_ADVANCE_DAYS} days</b> in advance
                </Text>
              </Stack>

              <ErrorAlert error={holdError || bookingError} />

              {/* Concurrency Slot Hold Locking Banner */}
              {activeHold ? (
                <Paper withBorder p="xs" radius="sm" bg="var(--mantine-color-blue-light)">
                  <Group justify="space-between" mb={4}>
                    <Group gap={6}>
                      <IconLock size={16} color="var(--mantine-color-blue-filled)" />
                      <Text size="xs" fw={700} c="blue">
                        Slot locked for you: {holdRemainingSeconds}s
                      </Text>
                    </Group>
                    <Badge size="xs" color={holdRemainingSeconds < 15 ? 'red' : 'blue'}>
                      {holdRemainingSeconds}s left
                    </Badge>
                  </Group>
                  <Progress
                    value={(holdRemainingSeconds / HOLD_DURATION_SECONDS) * 100}
                    color={holdRemainingSeconds < 15 ? 'red' : 'blue'}
                    size="xs"
                    animated
                    mb="xs"
                  />
                  <Text size="xs" c="dimmed" mb="xs">
                    Slot held exclusively. Click Confirm to finalize your booking or Release to free the slot.
                  </Text>
                  <Group gap="xs" grow>
                    <Button
                      size="xs"
                      color="teal"
                      leftSection={<IconCheck size={14} />}
                      onClick={handleConfirmBooking}
                      loading={bookingLoading}
                      disabled={holdLoading}
                    >
                      Confirm reservation
                    </Button>
                    <Button
                      size="xs"
                      variant="default"
                      leftSection={<IconLockOpen size={14} />}
                      onClick={handleReleaseHold}
                      disabled={bookingLoading || holdLoading}
                    >
                      Release hold
                    </Button>
                  </Group>
                </Paper>
              ) : (
                <Stack gap="xs" mt="xs">
                  <Button
                    size="sm"
                    variant="light"
                    color="blue"
                    fullWidth
                    leftSection={<IconLock size={16} />}
                    onClick={() => {
                      setBookingError(null)
                      void handleAcquireHold()
                    }}
                    loading={holdLoading}
                    disabled={!isSlotAvailable || availability.checking || asset.status !== 'AVAILABLE' || bookingLoading}
                  >
                    Lock slot (1-min hold)
                  </Button>
                  <Button
                    size="md"
                    color="campus"
                    fullWidth
                    onClick={handleConfirmBooking}
                    loading={bookingLoading}
                    disabled={!isSlotAvailable || availability.checking || asset.status !== 'AVAILABLE' || holdLoading}
                  >
                    Confirm booking
                  </Button>
                </Stack>
              )}
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
