import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Paper,
  Progress,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertCircle,
  IconCalendar,
  IconChartBar,
  IconCheck,
  IconClock,
  IconDeviceAnalytics,
  IconFlame,
  IconRefresh,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import {
  formatLocalIsoDateTime,
  getDemandHeatmap,
  getUtilizationAnalytics,
  type HeatmapCell,
  type HeatmapResponse,
  type UtilizationAnalyticsResponse,
} from '../api/analytics.ts'
import { searchAssets, type Asset } from '../api/catalog.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

type TimeRangePreset = '7d' | '30d' | '90d'

const DAYS_OF_WEEK = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' },
]

const HOURS_RANGE = Array.from({ length: 12 }, (_, i) => i + 8) // 8..19

function getDateWindow(preset: TimeRangePreset): { from: string; to: string } {
  const toDate = new Date()
  const fromDate = new Date()
  const days = preset === '7d' ? 7 : preset === '90d' ? 90 : 30
  fromDate.setDate(toDate.getDate() - days)
  fromDate.setHours(8, 0, 0, 0)
  toDate.setHours(20, 0, 0, 0)

  return {
    from: formatLocalIsoDateTime(fromDate),
    to: formatLocalIsoDateTime(toDate),
  }
}

export default function AdminAnalyticsPage() {
  const [rangePreset, setRangePreset] = useState<TimeRangePreset>('30d')
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null)
  const [assetsList, setAssetsList] = useState<Asset[]>([])

  const [utilization, setUtilization] = useState<UtilizationAnalyticsResponse | null>(null)
  const [heatmap, setHeatmap] = useState<HeatmapResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)

  // Fetch available assets for the heatmap filter
  useEffect(() => {
    let ignore = false
    searchAssets({ size: 100 })
      .then((res) => {
        if (!ignore) {
          setAssetsList(res.content)
        }
      })
      .catch((err) => console.warn('Could not load assets list for analytics:', err))
    return () => {
      ignore = true
    }
  }, [])

  useEffect(() => {
    let ignore = false
    void Promise.resolve().then(() => {
      if (!ignore) setLoading(true)
    })

    const { from, to } = getDateWindow(rangePreset)
    const assetFilter = selectedAssetId ? Number(selectedAssetId) : undefined

    Promise.all([getUtilizationAnalytics(from, to), getDemandHeatmap(from, to, assetFilter)])
      .then(([utilRes, heatRes]) => {
        if (ignore) return
        setUtilization(utilRes)
        setHeatmap(heatRes)
        setError(null)
      })
      .catch((err: unknown) => {
        if (ignore) return
        setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })

    return () => {
      ignore = true
    }
  }, [rangePreset, selectedAssetId, reloadToken])

  // Map cells into a day-by-hour lookup: lookup[day][hour]
  const heatmapLookup: Record<number, Record<number, HeatmapCell>> = {}
  if (heatmap?.cells) {
    for (const cell of heatmap.cells) {
      if (!heatmapLookup[cell.dayOfWeek]) {
        heatmapLookup[cell.dayOfWeek] = {}
      }
      heatmapLookup[cell.dayOfWeek][cell.hour] = cell
    }
  }

  function getCellColor(intensity: number, count: number): string {
    if (count === 0) return 'var(--mantine-color-gray-1)'
    if (intensity < 0.25) return 'var(--mantine-color-blue-1)'
    if (intensity < 0.5) return 'var(--mantine-color-blue-3)'
    if (intensity < 0.75) return 'var(--mantine-color-orange-3)'
    return 'var(--mantine-color-red-5)'
  }

  function getCellTextColor(intensity: number, count: number): string {
    if (count === 0) return 'var(--mantine-color-dimmed)'
    if (intensity >= 0.75) return '#ffffff'
    return 'var(--mantine-color-text)'
  }

  return (
    <Stack gap="lg">
      <PageHeader
        title="Equipment Utilization & Demand Heatmap"
        description="Comprehensive analytics dashboard for equipment utilization, demand intensity matrices, and automated capacity planning."
        actions={
          <Group gap="xs">
            <Button
              variant="default"
              leftSection={<IconRefresh size={16} />}
              onClick={() => setReloadToken((t) => t + 1)}
              loading={loading}
            >
              Refresh
            </Button>
          </Group>
        }
      />

      <ErrorAlert error={error} />

      {/* Control bar: Preset selection & asset filter */}
      <Paper withBorder p="md" radius="md">
        <Group justify="space-between" wrap="wrap" gap="md">
          <Group gap="sm">
            <Text size="sm" fw={600}>
              Reporting Period:
            </Text>
            <SegmentedControl
              value={rangePreset}
              onChange={(val) => setRangePreset(val as TimeRangePreset)}
              data={[
                { label: 'Last 7 Days', value: '7d' },
                { label: 'Last 30 Days', value: '30d' },
                { label: 'Last 90 Days', value: '90d' },
              ]}
            />
          </Group>

          <Group gap="sm">
            <Text size="sm" fw={600}>
              Filter by Asset:
            </Text>
            <Select
              placeholder="All Equipment"
              clearable
              searchable
              value={selectedAssetId}
              onChange={setSelectedAssetId}
              data={assetsList.map((a) => ({
                value: String(a.id),
                label: `${a.name} (${a.model})`,
              }))}
              style={{ width: 260 }}
            />
          </Group>
        </Group>
      </Paper>

      {loading && !utilization && (
        <Center py="xl">
          <Loader size="lg" />
        </Center>
      )}

      {utilization && (
        <>
          {/* Key Metric Overview Cards */}
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="md">
            <Card withBorder radius="md" p="md">
              <Group justify="space-between" mb="xs">
                <Text size="xs" c="dimmed" fw={700} tt="uppercase">
                  Lab Utilization
                </Text>
                <ThemeIcon color="blue" variant="light" size={32} radius="md">
                  <IconDeviceAnalytics size={18} />
                </ThemeIcon>
              </Group>
              <Title order={3}>{utilization.averageUtilizationPercent}%</Title>
              <Progress value={utilization.averageUtilizationPercent} mt="sm" size="sm" color="blue" />
              <Text size="xs" c="dimmed" mt={8}>
                {utilization.totalBookedHours} hrs of {utilization.totalCapacityHours} total lab capacity
              </Text>
            </Card>

            <Card withBorder radius="md" p="md">
              <Group justify="space-between" mb="xs">
                <Text size="xs" c="dimmed" fw={700} tt="uppercase">
                  Total Bookings
                </Text>
                <ThemeIcon color="teal" variant="light" size={32} radius="md">
                  <IconCalendar size={18} />
                </ThemeIcon>
              </Group>
              <Title order={3}>{utilization.totalBookingsCount}</Title>
              <Text size="sm" c="dimmed" mt="xs">
                Realized across {utilization.reportingDays} days
              </Text>
              <Text size="xs" c="dimmed" mt={4}>
                {utilization.totalAssetsTracked} equipment units monitored
              </Text>
            </Card>

            <Card withBorder radius="md" p="md">
              <Group justify="space-between" mb="xs">
                <Text size="xs" c="dimmed" fw={700} tt="uppercase">
                  Cancellations & No-Shows
                </Text>
                <ThemeIcon color="orange" variant="light" size={32} radius="md">
                  <IconAlertCircle size={18} />
                </ThemeIcon>
              </Group>
              <Group gap="xs" align="baseline">
                <Title order={3}>{utilization.cancellationRate}%</Title>
                <Text size="xs" c="dimmed">
                  cancellation rate
                </Text>
              </Group>
              <Group gap="xs" align="baseline" mt={4}>
                <Text size="sm" fw={600} c={utilization.noShowRate > 10 ? 'red' : 'dimmed'}>
                  {utilization.noShowRate}%
                </Text>
                <Text size="xs" c="dimmed">
                  no-show rate
                </Text>
              </Group>
            </Card>

            <Card withBorder radius="md" p="md">
              <Group justify="space-between" mb="xs">
                <Text size="xs" c="dimmed" fw={700} tt="uppercase">
                  Idle Equipment
                </Text>
                <ThemeIcon
                  color={utilization.idleAssetsCount > 0 ? 'yellow' : 'green'}
                  variant="light"
                  size={32}
                  radius="md"
                >
                  <IconClock size={18} />
                </ThemeIcon>
              </Group>
              <Group gap="xs" align="baseline">
                <Title order={3}>{utilization.idleAssetsCount}</Title>
                <Text size="xs" c="dimmed">
                  of {utilization.totalAssetsTracked} units
                </Text>
              </Group>
              <Badge color={utilization.idleAssetsCount > 0 ? 'yellow' : 'green'} variant="light" mt="sm">
                {utilization.idleAssetsCount > 0 ? 'Zero Bookings Recorded' : 'All Units Utilized'}
              </Badge>
            </Card>
          </SimpleGrid>

          {/* Resource Capacity Planning & Bottleneck Alerts */}
          {utilization.bottlenecks.length > 0 ? (
            <Alert
              icon={<IconFlame size={20} />}
              title="Resource Capacity Planning & Saturated Bottlenecks"
              color="red"
              radius="md"
            >
              <Stack gap="sm">
                <Text size="sm">
                  Concurrency analysis detected high peak demand exceeding or fully saturating available equipment
                  units. Consider expanding inventory for the following models:
                </Text>
                {utilization.bottlenecks.map((b) => (
                  <Paper key={b.model} p="sm" withBorder radius="sm" bg="var(--mantine-color-body)">
                    <Group justify="space-between" wrap="wrap">
                      <Group gap="xs">
                        <Badge color="red" variant="filled">
                          {b.model}
                        </Badge>
                        <Text size="sm" fw={600}>
                          Peak demand: {b.peakConcurrentDemand} requests vs {b.totalUnits} available (
                          {b.peakUtilizationRate}%)
                        </Text>
                      </Group>
                      <Badge variant="outline" color="orange">
                        {b.saturatedEventsCount} saturation events
                      </Badge>
                    </Group>
                    <Text size="xs" c="dimmed" mt={6}>
                      {b.recommendation}
                    </Text>
                  </Paper>
                ))}
              </Stack>
            </Alert>
          ) : (
            <Alert
              icon={<IconCheck size={20} />}
              title="Resource Capacity Status"
              color="teal"
              variant="light"
              radius="md"
            >
              No model bottlenecks detected. Concurrency levels remained within existing unit limits throughout the
              chosen window.
            </Alert>
          )}

          {/* Tabbed View: Demand Heatmap & Asset Breakdown */}
          <Tabs defaultValue="heatmap" radius="md">
            <Tabs.List>
              <Tabs.Tab value="heatmap" leftSection={<IconFlame size={16} />}>
                Booking Demand Heatmap
              </Tabs.Tab>
              <Tabs.Tab value="assets" leftSection={<IconChartBar size={16} />}>
                Equipment Utilization Table
              </Tabs.Tab>
            </Tabs.List>

            {/* TAB 1: Heatmap Grid */}
            <Tabs.Panel value="heatmap" pt="md">
              <Card withBorder radius="md" p="md">
                <Group justify="space-between" mb="md" wrap="wrap">
                  <div>
                    <Title order={4}>Weekly Demand Intensity Matrix</Title>
                    <Text size="xs" c="dimmed">
                      Distribution of reservation demand across operating hours (08:00 - 20:00).
                      {heatmap?.assetId ? ` Filtered for Asset #${heatmap.assetId}.` : ' Aggregated for all equipment.'}
                    </Text>
                  </div>
                  <Group gap="xs">
                    <Text size="xs" fw={600} c="dimmed">
                      Peak hourly demand:
                    </Text>
                    <Badge color="red" size="lg" variant="light">
                      {heatmap?.peakHourlyDemand ?? 0} bookings/hr
                    </Badge>
                  </Group>
                </Group>

                {/* Heatmap Legend */}
                <Group gap="xs" mb="lg">
                  <Text size="xs" c="dimmed">
                    Intensity:
                  </Text>
                  <Group gap={4}>
                    <Box w={16} h={16} style={{ backgroundColor: 'var(--mantine-color-gray-1)', borderRadius: 3 }} />
                    <Text size="xs" c="dimmed">
                      0
                    </Text>
                  </Group>
                  <Group gap={4}>
                    <Box w={16} h={16} style={{ backgroundColor: 'var(--mantine-color-blue-1)', borderRadius: 3 }} />
                    <Text size="xs" c="dimmed">
                      Low
                    </Text>
                  </Group>
                  <Group gap={4}>
                    <Box w={16} h={16} style={{ backgroundColor: 'var(--mantine-color-blue-3)', borderRadius: 3 }} />
                    <Text size="xs" c="dimmed">
                      Moderate
                    </Text>
                  </Group>
                  <Group gap={4}>
                    <Box w={16} h={16} style={{ backgroundColor: 'var(--mantine-color-orange-3)', borderRadius: 3 }} />
                    <Text size="xs" c="dimmed">
                      High
                    </Text>
                  </Group>
                  <Group gap={4}>
                    <Box w={16} h={16} style={{ backgroundColor: 'var(--mantine-color-red-5)', borderRadius: 3 }} />
                    <Text size="xs" c="dimmed">
                      Peak
                    </Text>
                  </Group>
                </Group>

                {/* Grid Table */}
                <Box style={{ overflowX: 'auto' }}>
                  <Table withTableBorder withColumnBorders style={{ minWidth: 700, textAlign: 'center' }}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th style={{ width: 100, textAlign: 'left' }}>Day</Table.Th>
                        {HOURS_RANGE.map((hour) => (
                          <Table.Th key={hour} style={{ textAlign: 'center', fontSize: 11, padding: 4 }}>
                            {String(hour).padStart(2, '0')}:00
                          </Table.Th>
                        ))}
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {DAYS_OF_WEEK.map((d) => (
                        <Table.Tr key={d.value}>
                          <Table.Td style={{ textAlign: 'left', fontWeight: 600, fontSize: 12 }}>{d.label}</Table.Td>
                          {HOURS_RANGE.map((hour) => {
                            const cell = heatmapLookup[d.value]?.[hour]
                            const count = cell?.bookingCount ?? 0
                            const intensity = cell?.intensityScore ?? 0.0

                            return (
                              <Table.Td
                                key={hour}
                                p={2}
                                style={{
                                  backgroundColor: getCellColor(intensity, count),
                                  color: getCellTextColor(intensity, count),
                                  transition: 'background-color 0.2s ease',
                                }}
                              >
                                <Tooltip
                                  label={`${d.label} ${String(hour).padStart(2, '0')}:00 - ${String(hour + 1).padStart(
                                    2,
                                    '0'
                                  )}:00 | ${count} booking(s) (${Math.round(intensity * 100)}% intensity)`}
                                  withArrow
                                >
                                  <Box
                                    py={8}
                                    style={{
                                      cursor: 'pointer',
                                      fontSize: 11,
                                      fontWeight: count > 0 ? 600 : 400,
                                    }}
                                  >
                                    {count > 0 ? count : '·'}
                                  </Box>
                                </Tooltip>
                              </Table.Td>
                            )
                          })}
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Box>
              </Card>
            </Tabs.Panel>

            {/* TAB 2: Asset Breakdown Table */}
            <Tabs.Panel value="assets" pt="md">
              <Card withBorder radius="md" p="md">
                <Title order={4} mb="xs">
                  Per-Equipment Utilization Metrics
                </Title>
                <Text size="xs" c="dimmed" mb="md">
                  Breakdown of booked hours, operational status, and relative utilization rate per asset.
                </Text>

                <Table withTableBorder withColumnBorders verticalSpacing="sm" highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Asset Name</Table.Th>
                      <Table.Th>Model</Table.Th>
                      <Table.Th>Status</Table.Th>
                      <Table.Th>Booked Hours</Table.Th>
                      <Table.Th style={{ width: 220 }}>Utilization Rate</Table.Th>
                      <Table.Th>Bookings</Table.Th>
                      <Table.Th>Activity State</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {utilization.assets.length === 0 ? (
                      <Table.Tr>
                        <Table.Td colSpan={7} style={{ textAlign: 'center' }}>
                          <Text c="dimmed" py="md">
                            No equipment found in catalog.
                          </Text>
                        </Table.Td>
                      </Table.Tr>
                    ) : (
                      utilization.assets.map((asset) => (
                        <Table.Tr key={asset.assetId}>
                          <Table.Td fw={600}>
                            {asset.name} <Text span size="xs" c="dimmed">#{asset.assetId}</Text>
                          </Table.Td>
                          <Table.Td>{asset.model}</Table.Td>
                          <Table.Td>
                            <Badge
                              color={
                                asset.status === 'AVAILABLE'
                                  ? 'green'
                                  : asset.status === 'UNDER_MAINTENANCE'
                                  ? 'yellow'
                                  : 'gray'
                              }
                              variant="light"
                              size="sm"
                            >
                              {asset.status}
                            </Badge>
                          </Table.Td>
                          <Table.Td>{asset.bookedHours} hrs</Table.Td>
                          <Table.Td>
                            <Group gap="xs" wrap="nowrap">
                              <Progress
                                value={asset.utilizationPercent}
                                color={
                                  asset.utilizationPercent > 75
                                    ? 'red'
                                    : asset.utilizationPercent > 40
                                    ? 'blue'
                                    : 'teal'
                                }
                                size="sm"
                                style={{ flex: 1 }}
                              />
                              <Text size="xs" fw={600} style={{ width: 45 }}>
                                {asset.utilizationPercent}%
                              </Text>
                            </Group>
                          </Table.Td>
                          <Table.Td>{asset.bookingCount}</Table.Td>
                          <Table.Td>
                            {asset.idle ? (
                              <Badge color="yellow" variant="dot" size="sm">
                                Idle
                              </Badge>
                            ) : asset.utilizationPercent > 75 ? (
                              <Badge color="red" variant="dot" size="sm">
                                High Demand
                              </Badge>
                            ) : (
                              <Badge color="teal" variant="dot" size="sm">
                                Active
                              </Badge>
                            )}
                          </Table.Td>
                        </Table.Tr>
                      ))
                    )}
                  </Table.Tbody>
                </Table>
              </Card>
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  )
}
