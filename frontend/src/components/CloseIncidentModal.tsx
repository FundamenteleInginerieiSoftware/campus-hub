import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { useState } from 'react'
import { closeIncident, MAX_DESCRIPTION_LENGTH, type Incident } from '../api/incidents.ts'
import ErrorAlert from './ErrorAlert.tsx'

type Props = {
  incident: Incident
  outcome: 'resolve' | 'reject'
  onClose: () => void
  onDone: (updated: Incident) => void
}

const TEXT = {
  resolve: {
    title: 'Resolve incident',
    explain: 'The problem was fixed. The equipment becomes available again if no other incident is open on it.',
    label: 'What was done (optional)',
    placeholder: 'E.g. replaced the display panel',
    button: 'Resolve',
    color: 'green',
  },
  reject: {
    title: 'Reject incident',
    explain:
      'Not a real problem. The equipment becomes available again if no other incident is open on it; bookings already moved stay moved.',
    label: 'Why it is rejected (optional)',
    placeholder: 'E.g. tested it, works fine; wrong probe was used',
    button: 'Reject',
    color: 'red',
  },
} as const

// Resolve and reject take the same optional notes (max 2000 characters), so one form serves both.
export default function CloseIncidentModal({ incident, outcome, onClose, onDone }: Props) {
  const [notes, setNotes] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const text = TEXT[outcome]
  const tooLong = notes.length > MAX_DESCRIPTION_LENGTH

  async function handleSubmit() {
    if (tooLong || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      onDone(await closeIncident(incident.id, outcome, notes))
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal opened onClose={() => !submitting && onClose()} title={`${text.title} #${incident.id}`} centered>
      <Stack>
        <ErrorAlert error={error} />
        <Text size="sm" c="dimmed">
          {text.explain}
        </Text>
        <Textarea
          label={text.label}
          placeholder={text.placeholder}
          autosize
          minRows={3}
          maxRows={8}
          value={notes}
          onChange={(event) => setNotes(event.currentTarget.value)}
          description={`${notes.length} / ${MAX_DESCRIPTION_LENGTH}`}
          error={tooLong ? `At most ${MAX_DESCRIPTION_LENGTH} characters` : null}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button color={text.color} onClick={handleSubmit} loading={submitting} disabled={tooLong}>
            {text.button}
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
