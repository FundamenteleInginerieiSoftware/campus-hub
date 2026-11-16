import { Button, Group, Modal, Stack, Text } from '@mantine/core'
import { useState } from 'react'
import { cancelReservation, type Reservation } from '../../api/reservations.ts'
import ErrorAlert from '../ErrorAlert.tsx'
import { formatSlotDate, formatSlotTime } from './reservationFormatters.ts'

type Props = {
  reservation: Reservation
  assetName?: string
  onClose: () => void
  onCancelled: (reservationId: number) => void
}

export default function CancelReservationModal({ reservation, assetName, onClose, onCancelled }: Props) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function handleConfirm() {
    setSubmitting(true)
    setError(null)
    try {
      await cancelReservation(reservation.id)
      onCancelled(reservation.id)
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      opened
      onClose={() => !submitting && onClose()}
      title={<Text fw={600}>Cancel Reservation #{reservation.id}</Text>}
      centered
    >
      <Stack>
        <ErrorAlert error={error} />
        <div>
          <Text size="sm">
            Are you sure you want to cancel your booking for{' '}
            <b>{assetName ?? `Asset #${reservation.assetId}`}</b>?
          </Text>
          <Text size="xs" c="dimmed" mt={4}>
            Time slot: {formatSlotDate(reservation.startTime)} ({formatSlotTime(reservation.startTime, reservation.endTime)})
          </Text>
        </div>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={onClose} disabled={submitting}>
            Keep booking
          </Button>
          <Button color="red" onClick={handleConfirm} loading={submitting}>
            Confirm cancel
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
