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
  IconX,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  checkAvailability,
  createReservation,
  type AssetSummary,
  type AvailabilityResponse,
  type Reservation,
} from '../api/reservations.ts'
import ErrorAlert from './ErrorAlert.tsx'

type Props = {
  asset: AssetSummary
  onBack?: () => void
  onSuccess?: (reservation: Reservation) => void
}

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']
const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

// 30-min steps from 08:00 to 20:00 (closing hour).
const TIME_STEPS: string[] = []
for (let h = 8; h < 20; h++) {
  TIME_STEPS.push(`${String(h).padStart(2, '0')}:00`)
  TIME_STEPS.push(`${String(h).padStart(2, '0')}:30`)
}

const ALL_DURATIONS = [
  { label: '30 min', value: 30 },
  { label: '1 h', value: 60 },
  { label: '1.5 h', value: 90 },
  { label: '2 h', value: 120 },
  { label: '3 h', value: 180 },
  { label: '4 h', value: 240 },
]

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

function parseDateKey(dateKey: string): Date {
  const [y, m, d] = dateKey.split('-').map(Number)
  return new Date(y, m - 1, d)
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

function formatFriendlyDate(dateKey: string): string {
  if (!dateKey) return ''
  const [y, m, d] = dateKey.split('-').map(Number)
  const dateObj = new Date(y, m - 1, d)
  const dayName = DAY_NAMES[dateObj.getDay() === 0 ? 4 : dateObj.getDay() - 1] ?? 'Day'
  const monthName = MONTH_NAMES[m - 1] ?? ''
  return `${dayName}, ${d} ${monthName} ${y}`
}

function findFirstValidSlotForDay(targetDay: Date, today: Date): string {
  const isToday = formatDateKey(targetDay) === formatDateKey(today)
  if (!isToday) {
    return '09:00'
  }
  const currentTotalMins = today.getHours() * 60 + today.getMinutes()
  for (const step of TIME_STEPS) {
    const [h, m] = step.split(':').map(Number)
    const slotMins = h * 60 + m
    if (slotMins > currentTotalMins && slotMins <= 19 * 60 + 30) {
      return step
    }
  }
  return '09:00'
}

function getInitialSchedule(today: Date): { day: string; time: string; monday: Date } {
  const dayOfWeek = today.getDay()
  const isWeekend = dayOfWeek === 0 || dayOfWeek === 6
  const currentTotalMins = today.getHours() * 60 + today.getMinutes()
  const isTooLateToday = currentTotalMins >= 19 * 60 + 30

  if (isWeekend) {
    const nextMon = getMonday(today)
    nextMon.setDate(nextMon.getDate() + 7)
    return {
      day: formatDateKey(nextMon),
      time: '09:00',
      monday: nextMon,
    }
  }

  if (isTooLateToday) {
    if (dayOfWeek === 5) {
      const nextMon = getMonday(today)
      nextMon.setDate(nextMon.getDate() + 7)
      return {
        day: formatDateKey(nextMon),
        time: '09:00',
        monday: nextMon,
      }
    }
    const tomorrow = new Date(today)
    tomorrow.setDate(tomorrow.getDate() + 1)
    return {
      day: formatDateKey(tomorrow),
      time: '09:00',
      monday: getMonday(today),
    }
  }

  return {
    day: formatDateKey(today),
    time: findFirstValidSlotForDay(today, today),
    monday: getMonday(today),
  }
}

/**
 * Google Calendar-style weekly equipment scheduling and booking component.
 * Calin's booking view ready to be embedded into Liviu's catalog equipment pages.
 */
export default function CalendarBookingView({ asset, onBack, onSuccess }: Props) {
  const navigate = useNavigate()
  const [today] = useState(() => new Date())
  const initialSchedule = useMemo(() => getInitialSchedule(today), [today])
  const maxBookingDate = useMemo(() => new Date(today.getFullYear(), today.getMonth(), today.getDate() + 14, today.getHours(), today.getMinutes(), today.getSeconds()), [today])

  // Current view's Monday
  const [currentMonday, setCurrentMonday] = useState(() => initialSchedule.monday)

  // Selected slot state
  const [selectedDay, setSelectedDay] = useState<string>(() => initialSchedule.day)
  const [selectedStartTime, setSelectedStartTime] = useState<string>(() => initialSchedule.time)
  const [durationMinutes, setDurationMinutes] = useState<number>(60)

  const [availability, setAvailability] = useState<{
    checking: boolean
    data: AvailabilityResponse | null
    error: unknown
  }>({ checking: true, data: null, error: null })

  const [bookingLoading, setBookingLoading] = useState(false)
  const [bookingError, setBookingError] = useState<unknown>(null)

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
    const closeMins = 20 * 60 // 20:00 = 1200 mins
    return Math.max(0, closeMins - startMins)
  }, [selectedStartTime])

  // If start is e.g. 19:30, automatically cap duration at 30 min (and grey out longer options).
  const effectiveDuration = useMemo(() => {
    if (durationMinutes <= maxAvailableMins) {
      return durationMinutes
    }
    const allowed = ALL_DURATIONS.map((d) => d.value).filter((val) => val <= maxAvailableMins)
    return allowed[allowed.length - 1] ?? 30
  }, [durationMinutes, maxAvailableMins])

  const startIso = useMemo(() => `${selectedDay}T${selectedStartTime}:00`, [selectedDay, selectedStartTime])
  const endIso = useMemo(() => {
    const endTimeStr = addMinutesToTimeString(selectedStartTime, effectiveDuration)
    return `${selectedDay}T${endTimeStr}:00`
  }, [selectedDay, selectedStartTime, effectiveDuration])

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
  }, [asset.id, startIso, endIso])

  function selectSlot(day: string, time: string) {
    setSelectedDay(day)
    setSelectedStartTime(time)
    setAvailability((prev) => ({ ...prev, checking: true, error: null }))
  }

  function handleDurationChange(val: number) {
    setDurationMinutes(val)
    setAvailability((prev) => ({ ...prev, checking: true, error: null }))
  }

  const isPrevWeekDisabled = currentMonday.getTime() <= initialSchedule.monday.getTime()
  const isNextWeekDisabled =
    new Date(
      currentMonday.getFullYear(),
      currentMonday.getMonth(),
      currentMonday.getDate() + 7,
      8,
      0,
      0,
    ) > maxBookingDate

  function handlePrevWeek() {
    if (isPrevWeekDisabled) return
    const prevMon = new Date(currentMonday)
    prevMon.setDate(prevMon.getDate() - 7)
    setCurrentMonday(prevMon)

    // Shift selectedDay back by 7 days in local time so it stays in the displayed week
    const currSel = parseDateKey(selectedDay)
    const prevSel = new Date(currSel)
    prevSel.setDate(currSel.getDate() - 7)

    const firstValidDay = parseDateKey(initialSchedule.day)
    const targetDay = prevSel >= firstValidDay ? prevSel : firstValidDay
    const targetKey = formatDateKey(targetDay)
    let targetTime = selectedStartTime
    if (targetKey === formatDateKey(today)) {
      const currentTotalMins = today.getHours() * 60 + today.getMinutes()
      const [selH, selM] = selectedStartTime.split(':').map(Number)
      if (selH * 60 + selM <= currentTotalMins) {
        targetTime = findFirstValidSlotForDay(today, today)
      }
    }
    selectSlot(targetKey, targetTime)
  }

  function handleNextWeek() {
    if (isNextWeekDisabled) return
    const nextMon = new Date(currentMonday)
    nextMon.setDate(nextMon.getDate() + 7)
    setCurrentMonday(nextMon)

    // Shift selectedDay forward by 7 days in local time and clamp against the 14-day horizon
    const currSel = parseDateKey(selectedDay)
    const nextSel = new Date(currSel)
    nextSel.setDate(currSel.getDate() + 7)

    const targetDay = toSlotDate(nextSel, '08:00') <= maxBookingDate ? nextSel : nextMon
    const targetTime =
      toSlotDate(targetDay, selectedStartTime) <= maxBookingDate ? selectedStartTime : '08:00'
    selectSlot(formatDateKey(targetDay), targetTime)
  }

  // Bug fixed: clicking "Today" on a weekend or after 19:30 previously selected 09:00 on today,
  // which immediately failed availability with OUTSIDE_OPENING_HOURS or BOOKING_IN_PAST.
  // Fix: reuse initialSchedule so weekends and late evenings jump to the next valid weekday slot.
  function handleToday() {
    setCurrentMonday(initialSchedule.monday)
    selectSlot(initialSchedule.day, initialSchedule.time)
  }

  async function handleConfirmBooking() {
    setBookingLoading(true)
    setBookingError(null)
    try {
      const res = await createReservation({
        assetId: asset.id,
        startTime: startIso,
        endTime: endIso,
      })
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
                  disabled={isPrevWeekDisabled}
                  aria-label="Previous week"
                >
                  <IconChevronLeft size={18} />
                </ActionIcon>
                <Button variant="default" size="xs" onClick={handleToday}>
                  Today
                </Button>
                <ActionIcon
                  variant="default"
                  size="lg"
                  onClick={handleNextWeek}
                  disabled={isNextWeekDisabled}
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
                  {ALL_DURATIONS.map((dur) => {
                    const isDisabled = dur.value > maxAvailableMins
                    const isSelected = effectiveDuration === dur.value
                    return (
                      <Tooltip
                        key={dur.value}
                        label={
                          isDisabled
                            ? `Labs close at 20:00. At ${selectedStartTime}, max duration is ${maxAvailableMins} min.`
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
                // Bug fixed: comparing midnight d > maxBookingDate enabled slots on day +14 after today's current time of day.
                // Fix: check the day's first slot (08:00) for the header and the exact slot timestamp for each 30-min cell.
                const isOutOfHorizon = toSlotDate(d, '08:00') > maxBookingDate
                const isToday = formatDateKey(d) === formatDateKey(today)

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
                      cursor: isPast || isOutOfHorizon ? 'not-allowed' : 'pointer',
                      opacity: isPast || isOutOfHorizon ? 0.5 : 1,
                    }}
                    onClick={() => {
                      if (!isPast && !isOutOfHorizon) {
                        let newTime = selectedStartTime
                        if (dayKey === formatDateKey(today)) {
                          const currentTotalMins = today.getHours() * 60 + today.getMinutes()
                          const [selH, selM] = selectedStartTime.split(':').map(Number)
                          if (selH * 60 + selM <= currentTotalMins) {
                            newTime = findFirstValidSlotForDay(d, today)
                          }
                        }
                        if (toSlotDate(d, newTime) > maxBookingDate) {
                          newTime = '08:00'
                        }
                        selectSlot(dayKey, newTime)
                      }
                    }}
                  >
                    <Text size="xs" fw={700} c={isSelectedDay ? 'campus' : 'dimmed'}>
                      {DAY_NAMES[d.getDay() === 0 ? 6 : d.getDay() - 1]}
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
                💡 Click any 30-min slot cell on the grid to set your reservation start time.
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
              </div>

              <div>
                <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                  Date
                </Text>
                <Group gap="xs" mt={2}>
                  <IconCalendar size={16} />
                  <Text fw={600}>{formatFriendlyDate(selectedDay)}</Text>
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

              {/* Rules reminders */}
              <Stack gap={4} p="xs" style={{ background: 'var(--mantine-color-body)', borderRadius: 6 }}>
                <Text size="xs" c="dimmed">
                  ✓ Labs open: <b>08:00 – 20:00 (Mon–Fri)</b>
                </Text>
                <Text size="xs" c="dimmed">
                  ✓ Maximum <b>2 active reservations</b> per student
                </Text>
                <Text size="xs" c="dimmed">
                  ✓ Book up to <b>14 days</b> in advance
                </Text>
              </Stack>

              <Button
                size="md"
                color="campus"
                fullWidth
                onClick={handleConfirmBooking}
                loading={bookingLoading}
                disabled={!isSlotAvailable || availability.checking || asset.status !== 'AVAILABLE'}
                mt="xs"
              >
                Confirm booking
              </Button>
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
