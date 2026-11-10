import {
  Alert,
  Anchor,
  Badge,
  Box,
  Card,
  Group,
  Paper,
  Progress,
  SimpleGrid,
  Stack,
  Table,
  Text,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertCircle,
  IconCalendar,
  IconCheck,
  IconClock,
  IconDeviceAnalytics,
  IconFlame,
} from '@tabler/icons-react'
import { Link } from 'react-router'
import {
  DAYS_OF_WEEK,
  getCellColor,
  getCellTextColor,
  HOURS_RANGE,
  type AssetUtilizationItem,
  type CapacityBottleneckItem,
  type HeatmapResponse,
  type UtilizationAnalyticsResponse,
} from '../api/analytics.ts'

interface AnalyticsKpiCardsProps {
  utilization: UtilizationAnalyticsResponse
  selectedAssetId: string | null
}

export function AnalyticsKpiCards({ utilization, selectedAssetId }: AnalyticsKpiCardsProps) {
  return (
    <Stack gap="xs">
      {selectedAssetId && (
        <Alert color="blue" variant="light" p="xs" radius="md">
          <Text size="xs">
            <strong>Fleet Scope Notice:</strong> Key metrics above and capacity alerts reflect overall laboratory totals.
            The demand heatmap and equipment table below are filtered for your selected equipment unit.
          </Text>
        </Alert>
      )}

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
            {utilization.totalBookedHours} hrs realized of {utilization.totalCapacityHours} hrs lab working capacity
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
            Total bookings logged across {utilization.reportingDays} days
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
    </Stack>
  )
}

interface BottlenecksAlertProps {
  bottlenecks: CapacityBottleneckItem[]
}

export function BottlenecksAlert({ bottlenecks }: BottlenecksAlertProps) {
  if (bottlenecks.length === 0) {
    return (
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
    )
  }

  return (
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
        {bottlenecks.map((b) => (
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
  )
}

interface DemandHeatmapGridProps {
  heatmap: HeatmapResponse | null
  heatmapLookup: Record<number, Record<number, { bookingCount: number; intensityScore: number }>>
}

export function DemandHeatmapGrid({ heatmap, heatmapLookup }: DemandHeatmapGridProps) {
  return (
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
          <Box
            w={16}
            h={16}
            style={{ border: '1px solid var(--mantine-color-default-border)', borderRadius: 3 }}
          />
          <Text size="xs" c="dimmed">
            0
          </Text>
        </Group>
        <Group gap={4}>
          <Box
            w={16}
            h={16}
            style={{ backgroundColor: 'rgba(59, 130, 246, 0.18)', borderRadius: 3 }}
          />
          <Text size="xs" c="dimmed">
            Low
          </Text>
        </Group>
        <Group gap={4}>
          <Box
            w={16}
            h={16}
            style={{ backgroundColor: 'rgba(59, 130, 246, 0.40)', borderRadius: 3 }}
          />
          <Text size="xs" c="dimmed">
            Moderate
          </Text>
        </Group>
        <Group gap={4}>
          <Box
            w={16}
            h={16}
            style={{ backgroundColor: 'rgba(249, 115, 22, 0.55)', borderRadius: 3 }}
          />
          <Text size="xs" c="dimmed">
            High
          </Text>
        </Group>
        <Group gap={4}>
          <Box
            w={16}
            h={16}
            style={{ backgroundColor: 'rgba(239, 68, 68, 0.85)', borderRadius: 3 }}
          />
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
  )
}

interface AssetUtilizationTableProps {
  displayedAssets: AssetUtilizationItem[]
  selectedAssetId: string | null
}

export function AssetUtilizationTable({ displayedAssets, selectedAssetId }: AssetUtilizationTableProps) {
  return (
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
          {displayedAssets.length === 0 ? (
            <Table.Tr>
              <Table.Td colSpan={7} style={{ textAlign: 'center' }}>
                <Text c="dimmed" py="md">
                  {selectedAssetId
                    ? 'No equipment matches the chosen asset filter.'
                    : 'No equipment found in catalog.'}
                </Text>
              </Table.Td>
            </Table.Tr>
          ) : (
            displayedAssets.map((asset) => (
              <Table.Tr key={asset.assetId}>
                <Table.Td fw={600}>
                  <Anchor component={Link} to={`/catalog/${asset.assetId}`} size="sm" fw={600}>
                    {asset.name}
                  </Anchor>{' '}
                  <Text span size="xs" c="dimmed">
                    #{asset.assetId}
                  </Text>
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
  )
}
