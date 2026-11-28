import { Alert, Button, Group, Modal, PinInput, SegmentedControl, Stack, Text, TextInput } from '@mantine/core'
import { IconAlertTriangle, IconCircleCheckFilled } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client.ts'
import { errorMessage } from '../api/errorMessages.ts'
import { verifyPickupPass, type VerifiedStudent } from '../api/pickupPass.ts'
import { ScanPassPanel } from './ScanPassPanel.tsx'

type Props = {
  // When set, the dialog verifies that specific student from a table row;
  // when omitted (top-bar "Scan student pass"), it accepts any student's QR or typed ID + code.
  userId?: number
  studentName?: string
  onClose: () => void
  onVerified: (student: VerifiedStudent) => void
}

function parsePositiveUserId(raw: string): number | null {
  const trimmed = raw.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const parsed = Number(trimmed)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

// Verifies a student's 6-digit pickup pass (typed or scanned as QR) against Matei's PickupPassService.
// Row-level verify starts on typing; top-bar "Scan student pass" opens straight to the camera for kiosk use.
export function VerifyStudentDialog({ userId, studentName, onClose, onVerified }: Props) {
  const isAnyStudent = userId === undefined
  const [typedUserId, setTypedUserId] = useState('')
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<'type' | 'scan'>(isAnyStudent ? 'scan' : 'type')
  const userIdBox = useRef<HTMLInputElement>(null)
  const firstBox = useRef<HTMLInputElement>(null)
  // Guards against double submits within the same render tick (e.g. auto-submit + Enter key).
  const submitting = useRef(false)

  // The inputs are disabled while checking, which drops focus: after an error in type mode,
  // focus Student ID if it is still empty, otherwise focus the first code box to re-type at once.
  useEffect(() => {
    if (busy || !error || mode !== 'type') return
    if (isAnyStudent && parsePositiveUserId(userIdBox.current?.value ?? '') === null) {
      userIdBox.current?.focus()
    } else {
      firstBox.current?.focus()
    }
  }, [busy, error, isAnyStudent, mode])

  async function submit(targetUserId: number, raw: string, fromScan: boolean) {
    // A pasted "493 027" arrives with the space; only the digits count.
    const code = raw.replace(/\s+/g, '')
    if (code.length !== 6 || submitting.current) return
    submitting.current = true
    setBusy(true)
    setError(null)
    try {
      const student = await verifyPickupPass(targetUserId, code)
      setValue('')
      onVerified(student)
      onClose()
    } catch (err) {
      setValue('')
      setError(
        err instanceof ApiError && err.code === 'PICKUP_CODE_INVALID'
          ? isAnyStudent && !fromScan
            ? 'The student ID or code is wrong, or the code has expired. Check the ID and ask the student for the new code.'
            : 'The code is wrong or has expired. Ask the student for the new one.'
          : errorMessage(err),
      )
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  function close() {
    if (busy) return
    setTypedUserId('')
    setValue('')
    setError(null)
    onClose()
  }

  const parsedTypedUserId = parsePositiveUserId(typedUserId)
  const canSubmitManual = parsedTypedUserId !== null && value.replace(/\s+/g, '').length === 6

  const title = isAnyStudent
    ? 'Scan student pass'
    : studentName
      ? `Verify ${studentName} (#${userId})`
      : `Verify student #${userId}`

  return (
    <Modal
      opened
      onClose={close}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      withCloseButton={!busy}
      title={title}
      centered
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (mode === 'type' && isAnyStudent && parsedTypedUserId !== null) {
            void submit(parsedTypedUserId, value, false)
          }
        }}
      >
        <Stack align="center">
          <SegmentedControl
            value={mode}
            onChange={(next) => {
              setError(null)
              setMode(next as 'type' | 'scan')
            }}
            data={[
              { label: 'Type code', value: 'type' },
              { label: 'Scan QR', value: 'scan' },
            ]}
            disabled={busy}
          />
          {mode === 'scan' ? (
            <ScanPassPanel
              userId={userId}
              paused={busy}
              onScan={(scanned) => void submit(scanned.userId, scanned.code, true)}
              onUnavailable={(message) => {
                setError(message)
                setMode('type')
              }}
            />
          ) : (
            <>
              {isAnyStudent && (
                <TextInput
                  label="Student ID"
                  placeholder="e.g. 4"
                  inputMode="numeric"
                  autoFocus
                  ref={userIdBox}
                  value={typedUserId}
                  onChange={(e) => {
                    // Only digits belong in a numeric user id; strip spaces or letters on paste.
                    setTypedUserId(e.currentTarget.value.replace(/\D+/g, ''))
                  }}
                  disabled={busy}
                  w="100%"
                />
              )}
              <Text size="sm" c="dimmed" ta="center">
                {isAnyStudent
                  ? 'Enter the student ID and the 6-digit code shown on their Pickup pass page.'
                  : 'Ask the student for the 6-digit code on their Pickup pass page.'}
              </Text>
              <PinInput
                length={6}
                type="number"
                oneTimeCode
                autoFocus={!isAnyStudent}
                ref={firstBox}
                value={value}
                onChange={(next) => {
                  setValue(next)
                  // Checked here, not in onComplete: a pasted code did not trigger onComplete in testing.
                  const targetId = isAnyStudent ? parsePositiveUserId(typedUserId) : userId
                  if (targetId !== null) {
                    void submit(targetId, next, false)
                  }
                }}
                disabled={busy}
                aria-label="Pickup code"
              />
            </>
          )}
          {error && (
            <Alert color="red" icon={<IconAlertTriangle size={18} />} w="100%">
              {error}
            </Alert>
          )}
          <Group justify="center" gap="sm">
            <Button variant="default" onClick={close} disabled={busy}>
              Cancel
            </Button>
            {isAnyStudent && mode === 'type' && (
              <Button type="submit" loading={busy} disabled={!canSubmitManual}>
                Verify
              </Button>
            )}
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}

// The name and a green check, shown in the Student column once the code was right.
export function VerifiedMark({ student }: { student: VerifiedStudent }) {
  return (
    <Text fw={500} title={student.email}>
      {student.fullName}{' '}
      <IconCircleCheckFilled size={16} color="var(--mantine-color-teal-6)" style={{ verticalAlign: 'text-bottom' }} />
    </Text>
  )
}
