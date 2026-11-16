import { Alert, Button, Group, Modal, Select, Stack, Text } from '@mantine/core'
import { useMemo, useState } from 'react'
import { extendReservation, type Reservation } from '../../api/reservations.ts'
import ErrorAlert from '../ErrorAlert.tsx'
import {
  CLOSE_MINUTES,
  CLOSING_TIME,
  EXTENSION_STEP_MINUTES,
  MAX_DURATION_MINUTES,
} from './bookingRules.ts'
import { formatSlotDate, formatSlotTime } from './reservationFormatters.ts'

type Props = {
  reservation: Reservation
  assetName?: string
  onClose: () => void
  onExtended: (updated: Reservation) => void
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

  const maxMinsAllowed = Math.min(startMins + MAX_DURATION_MINUTES, CLOSE_MINUTES)
  const remainingMins = maxMinsAllowed - endMins

  const options: { value: string; label: string }[] = []
  for (const step of EXTENSION_STEP_MINUTES) {
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

export default function ExtendReservationModal({ reservation, assetName, onClose, onExtended }: Props) {
  const options = useMemo(() => computeExtensionOptions(reservation), [reservation])
  const [endTime, setEndTime] = useState<string | null>(() => options[0]?.value ?? null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const maxHours = MAX_DURATION_MINUTES / 60

  async function handleConfirm() {
    if (!endTime) return
    setLoading(true)
    setError(null)
    try {
      const updated = await extendReservation(reservation.id, endTime)
      onExtended(updated)
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      opened
      onClose={() => !loading && onClose()}
      title={<Text fw={600}>Extend Reservation #{reservation.id}</Text>}
      centered
    >
      <Stack>
        <ErrorAlert error={error} />
        <div>
          <Text size="sm">
            Equipment: <b>{assetName ?? `Asset #${reservation.assetId}`}</b>
          </Text>
          <Text size="xs" c="dimmed" mt={2}>
            Current slot: {formatSlotDate(reservation.startTime)} ({formatSlotTime(reservation.startTime, reservation.endTime)})
          </Text>
        </div>
        {options.length > 0 ? (
          <Select
            label="New end time"
            description={`Select new end time (maximum ${maxHours} hours total duration, closing at ${CLOSING_TIME}).`}
            data={options}
            value={endTime}
            onChange={setEndTime}
            allowDeselect={false}
          />
        ) : (
          <Alert color="yellow">
            This reservation cannot be extended: it already reaches the {maxHours}-hour duration limit or the {CLOSING_TIME} lab closing time.
          </Alert>
        )}
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button
            color="blue"
            onClick={handleConfirm}
            loading={loading}
            disabled={!endTime || options.length === 0}
          >
            Confirm extension
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
