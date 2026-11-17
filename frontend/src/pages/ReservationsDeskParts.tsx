import { Alert, Button, Modal, PinInput, Stack, Text } from '@mantine/core'
import { IconAlertTriangle, IconCircleCheckFilled } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client.ts'
import { errorMessage } from '../api/errorMessages.ts'
import { verifyPickupPass, type VerifiedStudent } from '../api/pickupPass.ts'

type Props = {
  userId: number
  onClose: () => void
  onVerified: (student: VerifiedStudent) => void
}

// The admin types the 6 digits the student shows. The backend answers with the student's name,
// so the desk knows the person in front of them owns the booking (the Student column only has an id).
export function VerifyStudentDialog({ userId, onClose, onVerified }: Props) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const firstBox = useRef<HTMLInputElement>(null)

  // The boxes are disabled while checking, which drops the focus: after a wrong code put it back on
  // the first box so the admin can type again at once.
  useEffect(() => {
    if (!busy && error) firstBox.current?.focus()
  }, [busy, error])

  async function submit(raw: string) {
    // A pasted "493 027" arrives with the space; only the digits count.
    const code = raw.replace(/\s+/g, '')
    if (code.length !== 6) return
    setBusy(true)
    setError(null)
    try {
      onVerified(await verifyPickupPass(userId, code))
      setValue('')
      onClose()
    } catch (err) {
      setValue('')
      setError(
        err instanceof ApiError && err.code === 'PICKUP_CODE_INVALID'
          ? 'The code is wrong or has expired. Ask the student for the new one.'
          : errorMessage(err),
      )
    } finally {
      setBusy(false)
    }
  }

  function close() {
    setValue('')
    setError(null)
    onClose()
  }

  return (
    <Modal
      opened
      onClose={close}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      withCloseButton={!busy}
      title={`Verify student #${userId}`}
      centered
    >
      <Stack align="center">
        <Text size="sm" c="dimmed" ta="center">
          Ask the student for the 6-digit code on their Pickup pass page.
        </Text>
        <PinInput
          length={6}
          type="number"
          oneTimeCode
          autoFocus
          ref={firstBox}
          value={value}
          onChange={(next) => {
            setValue(next)
            // Checked here, not in onComplete: a pasted code did not trigger onComplete in testing.
            void submit(next)
          }}
          disabled={busy}
          aria-label="Pickup code"
        />
        {error && (
          <Alert color="red" icon={<IconAlertTriangle size={18} />}>
            {error}
          </Alert>
        )}
        <Button variant="default" onClick={close} disabled={busy}>
          Cancel
        </Button>
      </Stack>
    </Modal>
  )
}

// The name and a green check, shown in the Student column once the code was right.
export function VerifiedMark({ student }: { student: VerifiedStudent }) {
  return (
    <Text fw={500} title={student.email}>
      {student.fullName} <IconCircleCheckFilled size={16} color="var(--mantine-color-teal-6)" style={{ verticalAlign: 'text-bottom' }} />
    </Text>
  )
}
