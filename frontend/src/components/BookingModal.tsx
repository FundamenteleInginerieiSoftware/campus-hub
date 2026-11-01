import {
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core'
import { IconCalendar, IconCheck, IconClock, IconX } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import {
  checkAvailability,
  createReservation,
  fetchAssetSummary,
  type AssetSummary,
  type Reservation,
} from '../api/reservations.ts'
import ErrorAlert from './ErrorAlert.tsx'

type Props = {
  opened: boolean
  onClose: () => void
  initialAssetId?: number
  onSuccess?: (reservation: Reservation) => void
}

// 30-minute interval slots from 08:00 to 19:30 (labs open 08:00 - 20:00, Mon-Fri).
const TIME_SLOTS = [
  '08:00',
  '08:30',
  '09:00',
  '09:30',
  '10:00',
  '10:30',
  '11:00',
  '11:30',
  '12:00',
  '12:30',
  '13:00',
  '13:30',
  '14:00',
  '14:30',
  '15:00',
  '15:30',
  '16:00',
  '16:30',
  '17:00',
  '17:30',
  '18:00',
  '18:30',
  '19:00',
  '19:30',
]

const DURATION_OPTIONS = [
  { label: '30m', value: '30' },
  { label: '1h', value: '60' },
  { label: '1.5h', value: '90' },
  { label: '2h', value: '120' },
  { label: '3h', value: '180' },
  { label: '4h', value: '240' },
]

function getTodayString(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function getMaxDateString(): string {
  const max = new Date()
  max.setDate(max.getDate() + 14)
  const year = max.getFullYear()
  const month = String(max.getMonth() + 1).padStart(2, '0')
  const day = String(max.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function addMinutesToTime(timeStr: string, minutesToAdd: number): string {
  const [hoursStr, minsStr] = timeStr.split(':')
  const totalMins = Number(hoursStr) * 60 + Number(minsStr) + minutesToAdd
  const newHours = Math.floor(totalMins / 60)
  const newMins = totalMins % 60
  return `${String(newHours).padStart(2, '0')}:${String(newMins).padStart(2, '0')}:00`
}

export default function BookingModal({ opened, onClose, initialAssetId, onSuccess }: Props) {
  const [assetId, setAssetId] = useState<number | string>(initialAssetId ?? 1)
  const [assetDetails, setAssetDetails] = useState<AssetSummary | null>(null)
  const [date, setDate] = useState(getTodayString())
  const [startHour, setStartHour] = useState('09:00')
  const [durationMinutes, setDurationMinutes] = useState('60')

  const [availabilityState, setAvailabilityState] = useState<{
    checking: boolean
    available: boolean | null
    reason: string | null
  }>({ checking: false, available: null, reason: null })

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const [prevInitialAssetId, setPrevInitialAssetId] = useState(initialAssetId)
  if (initialAssetId !== prevInitialAssetId) {
    setPrevInitialAssetId(initialAssetId)
    if (initialAssetId !== undefined) {
      setAssetId(initialAssetId)
    }
  }

  // Try to load asset info when assetId changes.
  useEffect(() => {
    const id = Number(assetId)
    if (!id || id <= 0) {
      return
    }
    let ignore = false
    fetchAssetSummary(id)
      .then((summary) => {
        if (!ignore) setAssetDetails(summary)
      })
      .catch(() => {
        if (!ignore) setAssetDetails(null)
      })
    return () => {
      ignore = true
    }
  }, [assetId])

  // Computed ISO timestamps without offset: "2026-11-03T14:00:00"
  const maxAvailableMins = useMemo(() => {
    const [h, m] = startHour.split(':').map(Number)
    const startMins = h * 60 + m
    const closeMins = 20 * 60 // 1200 mins
    return Math.max(0, closeMins - startMins)
  }, [startHour])

  const durationOptions = useMemo(() => {
    return DURATION_OPTIONS.map((opt) => ({
      ...opt,
      disabled: Number(opt.value) > maxAvailableMins,
    }))
  }, [maxAvailableMins])

  const effectiveDuration = useMemo(() => {
    const numeric = Number(durationMinutes)
    if (numeric <= maxAvailableMins) {
      return durationMinutes
    }
    const allowed = [240, 180, 120, 90, 60, 30].filter((val) => val <= maxAvailableMins)
    return String(allowed[allowed.length - 1] ?? 30)
  }, [durationMinutes, maxAvailableMins])

  const startIso = useMemo(() => `${date}T${startHour}:00`, [date, startHour])
  const endIso = useMemo(() => {
    const endTime = addMinutesToTime(startHour, Number(effectiveDuration))
    return `${date}T${endTime}`
  }, [date, startHour, effectiveDuration])

  // Check availability when asset, date, or time changes.
  useEffect(() => {
    const numericAssetId = Number(assetId)
    if (!opened || !numericAssetId || !date || !startHour) {
      return
    }

    let cancelled = false
    checkAvailability({
      assetId: numericAssetId,
      startTime: startIso,
      endTime: endIso,
    })
      .then((res) => {
        if (!cancelled) {
          setAvailabilityState({
            checking: false,
            available: res.available,
            reason: res.conflictReason,
          })
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setAvailabilityState({
            checking: false,
            available: false,
            reason: err instanceof Error ? err.message : 'Unavailable',
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [opened, assetId, startIso, endIso, date, startHour])

  async function handleBook() {
    setError(null)
    setSubmitting(true)
    try {
      const reservation = await createReservation({
        assetId: Number(assetId),
        startTime: startIso,
        endTime: endIso,
      })
      if (onSuccess) {
        onSuccess(reservation)
      }
      onClose()
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Group gap="xs">
          <IconCalendar size={20} />
          <Text fw={600}>Book equipment slot</Text>
        </Group>
      }
      centered
      size="md"
    >
      <Stack gap="md">
        <ErrorAlert error={error} />

        <NumberInput
          label="Equipment ID"
          description={
            assetDetails
              ? `${assetDetails.name} (${assetDetails.model}) · Status: ${assetDetails.status}`
              : 'Enter the target equipment asset ID'
          }
          value={assetId}
          onChange={(val) => setAssetId(val ?? '')}
          min={1}
          required
        />

        <TextInput
          label="Reservation Date"
          type="date"
          value={date}
          min={getTodayString()}
          max={getMaxDateString()}
          onChange={(e) => setDate(e.currentTarget.value)}
          required
        />

        <Group grow align="flex-start">
          <Select
            label="Start Time"
            data={TIME_SLOTS}
            value={startHour}
            onChange={(val) => setStartHour(val ?? '09:00')}
            required
            comboboxProps={{ withinPortal: false }}
          />

          <div>
            <Text size="sm" fw={500} mb={4}>
              Duration
            </Text>
            <SegmentedControl
              value={effectiveDuration}
              onChange={setDurationMinutes}
              data={durationOptions}
              fullWidth
            />
          </div>
        </Group>

        {/* Selected Slot Summary & Availability status */}
        <Alert color="gray" icon={<IconClock size={18} />}>
          <Group justify="space-between" align="center">
            <div>
              <Text size="xs" c="dimmed">
                Evaluated slot:
              </Text>
              <Text size="sm" fw={600}>
                {date} · {startHour} to {addMinutesToTime(startHour, Number(effectiveDuration)).slice(0, 5)}
              </Text>
            </div>

            <div>
              {availabilityState.checking && <Badge color="gray">Checking availability...</Badge>}
              {!availabilityState.checking && availabilityState.available === true && (
                <Badge color="green" leftSection={<IconCheck size={12} />}>
                  Available
                </Badge>
              )}
              {!availabilityState.checking && availabilityState.available === false && (
                <Badge color="red" leftSection={<IconX size={12} />}>
                  {availabilityState.reason ?? 'Unavailable'}
                </Badge>
              )}
            </div>
          </Group>
        </Alert>

        <Group justify="flex-end" mt="sm">
          <Button variant="default" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            onClick={handleBook}
            loading={submitting}
            disabled={availabilityState.available === false}
          >
            Confirm booking
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
