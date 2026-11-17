import { Button, Group, Modal, NumberInput, Stack, Text } from '@mantine/core'
import { useState } from 'react'
import { initiateTransfer, type Reservation } from '../../api/reservations.ts'
import ErrorAlert from '../ErrorAlert.tsx'
import { formatSlotDate, formatSlotTime } from './reservationFormatters.ts'

type Props = {
  reservation: Reservation
  assetName?: string
  onClose: () => void
  onTransferred: (reservationId: number, recipientUserId: number) => void
}

export default function TransferReservationModal({ reservation, assetName, onClose, onTransferred }: Props) {
  const [recipientUserId, setRecipientUserId] = useState<number | string>('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function handleConfirm() {
    const toId = Number(recipientUserId)
    if (!toId || isNaN(toId)) return
    setLoading(true)
    setError(null)
    try {
      await initiateTransfer(reservation.id, toId)
      onTransferred(reservation.id, toId)
    } catch (err: unknown) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      opened
      onClose={() => !loading && onClose()}
      title={<Text fw={600}>Transfer Reservation #{reservation.id}</Text>}
      centered
    >
      <Stack>
        <ErrorAlert error={error} />
        <div>
          <Text size="sm">
            Offer booking for <b>{assetName ?? `Asset #${reservation.assetId}`}</b> to another student.
          </Text>
          <Text size="xs" c="dimmed" mt={2}>
            Slot: {formatSlotDate(reservation.startTime)} ({formatSlotTime(reservation.startTime, reservation.endTime)})
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
          <Button variant="default" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button
            color="violet"
            onClick={handleConfirm}
            loading={loading}
            disabled={!recipientUserId}
          >
            Send transfer offer
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
