import { Button, Group, Modal, Stack, Text } from '@mantine/core'
import { useState } from 'react'
import { cancelBundle, type Reservation } from '../../api/reservations.ts'
import ErrorAlert from '../ErrorAlert.tsx'
import { formatSlotDate, formatSlotTime } from './reservationFormatters.ts'

type Props = {
  bundleId: string
  reservation: Reservation
  onClose: () => void
  onCancelled: (bundleId: string, cancelledCount: number) => void
}

export default function CancelBundleModal({ bundleId, reservation, onClose, onCancelled }: Props) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function handleConfirm() {
    setSubmitting(true)
    setError(null)
    try {
      const updated = await cancelBundle(bundleId)
      const cancelledCount = updated.filter((item) => item.status === 'CANCELLED').length
      onCancelled(bundleId, cancelledCount)
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
      title={<Text fw={600}>Cancel Entire Bundle</Text>}
      centered
    >
      <Stack>
        <ErrorAlert error={error} />
        <div>
          <Text size="sm">
            Are you sure you want to cancel all remaining confirmed items in this bundle booking?
          </Text>
          <Text size="xs" c="dimmed" mt={4}>
            Time slot: {formatSlotDate(reservation.startTime)} ({formatSlotTime(reservation.startTime, reservation.endTime)})
          </Text>
        </div>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={onClose} disabled={submitting}>
            Keep bundle
          </Button>
          <Button color="red" onClick={handleConfirm} loading={submitting}>
            Cancel entire bundle
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
