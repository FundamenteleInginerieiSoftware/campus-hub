import {
  Alert,
  Badge,
  Button,
  Card,
  Center,
  CopyButton,
  Divider,
  Group,
  List,
  Loader,
  Modal,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconCalendarShare,
  IconCheck,
  IconCopy,
  IconDownload,
  IconInfoCircle,
  IconRefresh,
  IconTrash,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { createCalendarFeed, fetchCalendarFeed, revokeCalendarFeed } from '../api/calendarFeed.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

type ConfirmAction = 'replace' | 'revoke' | null

// Private iCalendar (.ics) subscription link management (spec §8).
// The full URL is kept only in component state right after creation because the backend
// stores only a SHA-256 hash of the token and cannot return the raw URL again on reload.
export default function CalendarSyncPage() {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [createdPath, setCreatedPath] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmAction, setConfirmAction] = useState<ConfirmAction>(null)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let ignore = false
    fetchCalendarFeed()
      .then((data) => {
        if (!ignore) {
          setEnabled(data.enabled)
        }
      })
      .catch((err) => {
        if (!ignore) {
          setError(err)
        }
      })
      .finally(() => {
        if (!ignore) {
          setLoading(false)
        }
      })

    return () => {
      ignore = true
    }
  }, [reloadToken])

  function handleRetryLoad() {
    setError(null)
    setLoading(true)
    setReloadToken((t) => t + 1)
  }

  async function handleCreate() {
    setError(null)
    setNotice(null)
    setSubmitting(true)
    try {
      const created = await createCalendarFeed()
      setCreatedPath(created.path)
      setEnabled(true)
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleConfirmReplace() {
    setError(null)
    setNotice(null)
    setSubmitting(true)
    try {
      const created = await createCalendarFeed()
      setCreatedPath(created.path)
      setEnabled(true)
      setConfirmAction(null)
    } catch (err) {
      setError(err)
      setConfirmAction(null)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleConfirmRevoke() {
    setError(null)
    setNotice(null)
    setSubmitting(true)
    try {
      await revokeCalendarFeed()
      setCreatedPath(null)
      setEnabled(false)
      setConfirmAction(null)
      setNotice('Calendar link turned off. Any calendar app using the old link will stop updating.')
    } catch (err) {
      setError(err)
      setConfirmAction(null)
    } finally {
      setSubmitting(false)
    }
  }

  const feedUrl = createdPath ? `${window.location.origin}${createdPath}` : null

  return (
    <Stack gap="lg">
      <PageHeader
        title="Calendar sync"
        description="Subscribe to your Campus Hub bookings from Google Calendar, Apple Calendar, or Outlook. Cancellations and automatic reassignments update automatically."
      />

      <ErrorAlert error={error} />

      {notice && (
        <Alert
          color="green"
          icon={<IconCheck size={18} />}
          withCloseButton
          onClose={() => setNotice(null)}
        >
          {notice}
        </Alert>
      )}

      {loading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : enabled === null ? (
        <Card withBorder radius="md" p="lg" bg="var(--app-surface)">
          <Group justify="space-between" align="center" wrap="wrap">
            <Text size="sm" c="dimmed">
              Could not check your calendar link status.
            </Text>
            <Button variant="light" onClick={handleRetryLoad}>
              Try again
            </Button>
          </Group>
        </Card>
      ) : feedUrl ? (
        <Card withBorder radius="md" p="lg" bg="var(--app-surface)">
          <Stack gap="md">
            <Group justify="space-between" align="center">
              <Title order={4}>Your calendar link is ready</Title>
              <Badge color="teal" variant="light">
                Active
              </Badge>
            </Group>

            <Alert color="yellow" icon={<IconAlertTriangle size={18} />}>
              Anyone with this link can see your bookings. We show it only now.
            </Alert>

            <Group align="flex-end" gap="xs" wrap="nowrap">
              <TextInput
                label="Subscription URL"
                value={feedUrl}
                readOnly
                onClick={(event) => event.currentTarget.select()}
                style={{ flex: 1 }}
              />
              <CopyButton value={feedUrl} timeout={2000}>
                {({ copied, copy }) => (
                  <Button
                    color={copied ? 'teal' : 'campus'}
                    variant={copied ? 'filled' : 'light'}
                    leftSection={copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                    onClick={copy}
                  >
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                )}
              </CopyButton>
            </Group>

            <Group gap="sm" wrap="wrap">
              <Button
                component="a"
                href={feedUrl}
                download="campus-hub-reservations.ics"
                variant="default"
                leftSection={<IconDownload size={16} />}
              >
                Download .ics
              </Button>
              <Button
                variant="light"
                leftSection={<IconRefresh size={16} />}
                onClick={() => setConfirmAction('replace')}
                disabled={submitting}
              >
                Make a new link
              </Button>
              <Button
                variant="light"
                color="red"
                leftSection={<IconTrash size={16} />}
                onClick={() => setConfirmAction('revoke')}
                disabled={submitting}
              >
                Turn off
              </Button>
            </Group>
          </Stack>
        </Card>
      ) : enabled ? (
        <Card withBorder radius="md" p="lg" bg="var(--app-surface)">
          <Stack gap="md">
            <Group justify="space-between" align="center">
              <Title order={4}>A calendar link is active</Title>
              <Badge color="teal" variant="light">
                Active
              </Badge>
            </Group>

            <Text size="sm" c="dimmed">
              For security, the secret address is only shown when you create it. If you want to add it to
              another calendar app or cut off an old link, create a new link or turn sync off.
            </Text>

            <Group gap="sm" wrap="wrap">
              <Button
                color="campus"
                leftSection={<IconRefresh size={16} />}
                onClick={() => setConfirmAction('replace')}
                disabled={submitting}
              >
                Make a new link
              </Button>
              <Button
                variant="light"
                color="red"
                leftSection={<IconTrash size={16} />}
                onClick={() => setConfirmAction('revoke')}
                disabled={submitting}
              >
                Turn off
              </Button>
            </Group>
          </Stack>
        </Card>
      ) : (
        <Card withBorder radius="md" p="lg" bg="var(--app-surface)">
          <Stack gap="md" align="flex-start">
            <Title order={4}>No calendar link yet</Title>
            <Text size="sm" c="dimmed" maw="65ch">
              Create a personal iCalendar (.ics) link to see your confirmed, checked-out, overdue, and
              returned bookings alongside your classes. Cancelled bookings disappear automatically, and
              reassigned bookings update to the substitute equipment name.
            </Text>
            <Button
              color="campus"
              leftSection={<IconCalendarShare size={16} />}
              onClick={handleCreate}
              loading={submitting}
            >
              Create calendar link
            </Button>
          </Stack>
        </Card>
      )}

      <Card withBorder radius="md" p="lg" bg="var(--app-surface)">
        <Stack gap="sm">
          <Title order={4}>How to subscribe</Title>
          <List spacing="xs" size="sm">
            <List.Item>
              <Text span fw={600}>
                Google Calendar:
              </Text>{' '}
              <Text span fs="italic">
                Other calendars → From URL
              </Text>
            </List.Item>
            <List.Item>
              <Text span fw={600}>
                Apple Calendar:
              </Text>{' '}
              <Text span fs="italic">
                File → New Calendar Subscription
              </Text>
            </List.Item>
            <List.Item>
              <Text span fw={600}>
                Outlook:
              </Text>{' '}
              <Text span fs="italic">
                Add calendar → Subscribe from web
              </Text>
            </List.Item>
          </List>
          <Divider my={2} />
          <Group gap="xs" align="center" wrap="nowrap">
            <IconInfoCircle size={16} />
            <Text size="xs" c="dimmed">
              Calendar apps refresh on their own schedule; Google can take several hours.
            </Text>
          </Group>
        </Stack>
      </Card>

      <Modal
        opened={confirmAction === 'replace'}
        onClose={() => !submitting && setConfirmAction(null)}
        title={<Text fw={600}>Make a new calendar link?</Text>}
        centered
      >
        <Stack>
          <Text size="sm">
            This creates a brand-new link and immediately disables your previous one. The old link will
            stop working in any calendar app where you already added it.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={() => setConfirmAction(null)} disabled={submitting}>
              Cancel
            </Button>
            <Button color="campus" onClick={handleConfirmReplace} loading={submitting}>
              Make a new link
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={confirmAction === 'revoke'}
        onClose={() => !submitting && setConfirmAction(null)}
        title={<Text fw={600}>Turn off calendar link?</Text>}
        centered
      >
        <Stack>
          <Text size="sm">
            Your current calendar link will stop working right away. Any calendar app subscribed to it
            will no longer receive updates.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={() => setConfirmAction(null)} disabled={submitting}>
              Cancel
            </Button>
            <Button color="red" onClick={handleConfirmRevoke} loading={submitting}>
              Turn off
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
