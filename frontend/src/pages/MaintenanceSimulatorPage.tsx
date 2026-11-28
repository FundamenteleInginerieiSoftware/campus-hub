import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  ThemeIcon,
} from '@mantine/core'
import { IconAlertTriangle, IconArrowsExchange, IconCalendarTime, IconCheck, IconTool } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { searchAssets, type Asset } from '../api/catalog.ts'
import { formatCampusDateTime } from '../api/incidents.ts'
import { simulateMaintenanceImpact, type MaintenanceImpactResponse } from '../api/reassignments.ts'
import { nowInBucharest } from '../api/reservations.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

const MAX_WINDOW_DAYS = 90
const MAX_WINDOW_MS = MAX_WINDOW_DAYS * 24 * 60 * 60 * 1000

const STRATEGY_LABELS: Record<string, string> = {
  SameModelStrategy: 'Same model',
  SameCategoryStrategy: 'Same category',
}

function defaultWindowInputs(): { start: string; end: string } {
  const d = nowInBucharest()
  const day = d.getDay()
  // Skip weekend days: Friday (+3) and Saturday (+2) default to Monday; Sunday (+1) -> Monday.
  const offset = day === 5 ? 3 : day === 6 ? 2 : 1
  d.setDate(d.getDate() + offset)
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return {
    start: `${yyyy}-${mm}-${dd}T08:00`,
    end: `${yyyy}-${mm}-${dd}T20:00`,
  }
}

function normalizeDateTimeLocal(value: string): string {
  return value.length === 16 ? `${value}:00` : value
}

// Parse local datetime string into DST-free UTC milliseconds to match Java Duration.between(LocalDateTime).
function parseLocalDateTimeUtcMs(value: string): number {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!match) return Number.NaN
  return Date.UTC(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    Number(match[6] ?? '0'),
  )
}

/**
 * Admin dashboard page for simulating planned maintenance impact (#58) without mutating bookings.
 */
export default function MaintenanceSimulatorPage() {
  // Lazy-initialize once on mount so nowInBucharest() / Intl.DateTimeFormat isn't recreated every render (#352 nit).
  const [initialDefaults] = useState(defaultWindowInputs)
  const [assets, setAssets] = useState<Asset[]>([])
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null)
  const [windowStart, setWindowStart] = useState(initialDefaults.start)
  const [windowEnd, setWindowEnd] = useState(initialDefaults.end)
  const [loadingAssets, setLoadingAssets] = useState(true)
  const [simulating, setSimulating] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<MaintenanceImpactResponse | null>(null)

  useEffect(() => {
    let ignore = false
    searchAssets({ status: 'AVAILABLE', size: 100, sort: 'name,asc' })
      .then((page) => {
        if (ignore) return
        setAssets(page.content)
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoadingAssets(false)
      })
    return () => {
      ignore = true
    }
  }, [])

  async function handleSimulate() {
    if (!selectedAssetId) return
    setResult(null)
    setError(null)
    setValidationError(null)

    const startMs = parseLocalDateTimeUtcMs(windowStart)
    const endMs = parseLocalDateTimeUtcMs(windowEnd)
    if (Number.isNaN(startMs) || Number.isNaN(endMs)) {
      setValidationError('Enter valid maintenance window start and end times.')
      return
    }
    if (endMs <= startMs) {
      setValidationError('Maintenance window end must be after start.')
      return
    }
    if (endMs - startMs > MAX_WINDOW_MS) {
      setValidationError(`Maintenance window cannot exceed ${MAX_WINDOW_DAYS} days.`)
      return
    }

    setSimulating(true)
    try {
      const response = await simulateMaintenanceImpact({
        assetId: Number(selectedAssetId),
        windowStart: normalizeDateTimeLocal(windowStart),
        windowEnd: normalizeDateTimeLocal(windowEnd),
      })
      setResult(response)
    } catch (err) {
      setError(err)
    } finally {
      setSimulating(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Maintenance simulator"
        description="Dry-run what happens to upcoming bookings if an available equipment unit (up to 100 listed) is taken offline during a planned maintenance window. No reservations are modified."
      />

      {validationError && (
        <Alert color="red" icon={<IconAlertTriangle size={18} />} mb="md">
          {validationError}
        </Alert>
      )}
      <ErrorAlert error={error} />

      <Card withBorder radius="md" p="md" bg="var(--app-surface)" mb="lg">
        <Stack gap="md">
          <SimpleGrid cols={{ base: 1, md: 3 }}>
            <Select
              label="Equipment unit"
              description="Available units (max 100)"
              placeholder={loadingAssets ? 'Loading equipment...' : 'Select asset'}
              searchable
              data={assets.map((a) => ({
                value: String(a.id),
                label: `${a.name} (${a.model} · ${a.labRoomCode})`,
              }))}
              value={selectedAssetId}
              onChange={(val) => {
                setSelectedAssetId(val)
                setResult(null)
                setError(null)
                setValidationError(null)
              }}
              disabled={loadingAssets || simulating}
            />
            <TextInput
              label="Maintenance window start"
              type="datetime-local"
              value={windowStart}
              onChange={(e) => {
                setWindowStart(e.currentTarget.value)
                setResult(null)
                setError(null)
                setValidationError(null)
              }}
              disabled={simulating}
            />
            <TextInput
              label="Maintenance window end"
              type="datetime-local"
              value={windowEnd}
              onChange={(e) => {
                setWindowEnd(e.currentTarget.value)
                setResult(null)
                setError(null)
                setValidationError(null)
              }}
              disabled={simulating}
            />
          </SimpleGrid>

          <Group justify="flex-end">
            <Button
              leftSection={<IconTool size={16} />}
              onClick={() => void handleSimulate()}
              loading={simulating}
              disabled={!selectedAssetId || !windowStart || !windowEnd}
            >
              Simulate maintenance impact
            </Button>
          </Group>
        </Stack>
      </Card>

      {result && (
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            Simulated window for <b>{result.assetName}</b>: {formatCampusDateTime(result.windowStart)} →{' '}
            {formatCampusDateTime(result.windowEnd)}
          </Text>

          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
              <Group justify="space-between">
                <div>
                  <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                    Affected bookings
                  </Text>
                  <Text fw={700} size="xl" mt={2}>
                    {result.totalAffected}
                  </Text>
                </div>
                <ThemeIcon size={36} radius="md" variant="light" color="blue">
                  <IconCalendarTime size={20} />
                </ThemeIcon>
              </Group>
            </Card>

            <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
              <Group justify="space-between">
                <div>
                  <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                    Auto-reassignable
                  </Text>
                  <Text fw={700} size="xl" mt={2}>
                    {result.reassignedCount}
                  </Text>
                </div>
                <ThemeIcon size={36} radius="md" variant="light" color="green">
                  <IconArrowsExchange size={20} />
                </ThemeIcon>
              </Group>
            </Card>

            <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
              <Group justify="space-between">
                <div>
                  <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                    Would force-cancel
                  </Text>
                  <Text fw={700} size="xl" mt={2}>
                    {result.forceCancelledCount}
                  </Text>
                </div>
                <ThemeIcon size={36} radius="md" variant="light" color="red">
                  <IconAlertTriangle size={20} />
                </ThemeIcon>
              </Group>
            </Card>

            <Card withBorder padding="sm" radius="md" bg="var(--app-surface)">
              <Group justify="space-between">
                <div>
                  <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                    Disruption score
                  </Text>
                  <Text fw={700} size="xl" mt={2}>
                    {result.disruptionScore}
                  </Text>
                </div>
                <ThemeIcon
                  size={36}
                  radius="md"
                  variant="light"
                  color={result.disruptionScore === 0 ? 'teal' : result.forceCancelledCount > 0 ? 'red' : 'orange'}
                >
                  {result.disruptionScore === 0 ? <IconCheck size={20} /> : <IconAlertTriangle size={20} />}
                </ThemeIcon>
              </Group>
            </Card>
          </SimpleGrid>

          {result.bookings.length === 0 ? (
            <Alert color="teal" icon={<IconCheck size={18} />}>
              No active upcoming reservations overlap this maintenance window for <b>{result.assetName}</b>.
            </Alert>
          ) : (
            <Card withBorder radius="md" p="md" bg="var(--app-surface)">
              <Text fw={700} size="sm" mb="sm">
                Simulated outcomes for {result.assetName}
              </Text>
              <Table.ScrollContainer minWidth={640}>
                <Table striped highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Booking</Table.Th>
                      <Table.Th>Student</Table.Th>
                      <Table.Th>Time window</Table.Th>
                      <Table.Th>Simulated outcome</Table.Th>
                      <Table.Th>Substitute unit</Table.Th>
                      <Table.Th>Strategy</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {result.bookings.map((b) => (
                      <Table.Tr key={b.reservationId}>
                        <Table.Td>#{b.reservationId}</Table.Td>
                        <Table.Td>Student #{b.userId}</Table.Td>
                        <Table.Td>
                          {formatCampusDateTime(b.startTime)} → {formatCampusDateTime(b.endTime)}
                        </Table.Td>
                        <Table.Td>
                          {b.outcome === 'REASSIGNED' ? (
                            <Badge color="green">Would move</Badge>
                          ) : (
                            <Badge color="red">Would cancel</Badge>
                          )}
                        </Table.Td>
                        <Table.Td>
                          {b.substituteAssetId ? (
                            <Anchor component={Link} to={`/catalog/${b.substituteAssetId}`} size="sm">
                              {b.substituteAssetName}
                            </Anchor>
                          ) : (
                            <Text size="xs" c="dimmed">
                              None free
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>
                          {b.strategy ? (
                            <Badge variant="light" color="gray">
                              {STRATEGY_LABELS[b.strategy] ?? b.strategy}
                            </Badge>
                          ) : (
                            '—'
                          )}
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </Card>
          )}
        </Stack>
      )}
    </>
  )
}
