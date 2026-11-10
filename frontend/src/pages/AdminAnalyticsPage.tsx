import {
  Button,
  Center,
  Group,
  Loader,
  Paper,
  SegmentedControl,
  Select,
  Stack,
  Tabs,
  Text,
} from '@mantine/core'
import { IconChartBar, IconFlame, IconRefresh } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import {
  getDateWindow,
  getDemandHeatmap,
  getUtilizationAnalytics,
  type HeatmapCell,
  type HeatmapResponse,
  type TimeRangePreset,
  type UtilizationAnalyticsResponse,
} from '../api/analytics.ts'
import { searchAssets, type Asset } from '../api/catalog.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import {
  AnalyticsKpiCards,
  AssetUtilizationTable,
  BottlenecksAlert,
  DemandHeatmapGrid,
} from './AdminAnalyticsParts.tsx'

export default function AdminAnalyticsPage() {
  const [rangePreset, setRangePreset] = useState<TimeRangePreset>('30d')
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null)
  const [assetsList, setAssetsList] = useState<Asset[]>([])

  const [utilization, setUtilization] = useState<UtilizationAnalyticsResponse | null>(null)
  const [heatmap, setHeatmap] = useState<HeatmapResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)

  // Fetch all available assets across all pages for the dropdown filter
  useEffect(() => {
    let ignore = false
    async function fetchAllAssets() {
      try {
        let page = 0
        const all: Asset[] = []
        let totalPages = 1
        while (page < totalPages) {
          const res = await searchAssets({ page, size: 100 })
          if (ignore) return
          all.push(...res.content)
          totalPages = res.totalPages
          page++
        }
        if (!ignore) {
          setAssetsList(all)
        }
      } catch (err) {
        console.warn('Could not load assets list for analytics:', err)
      }
    }
    fetchAllAssets()
    return () => {
      ignore = true
    }
  }, [])

  // Handlers for user interactions that trigger data loading
  function handlePresetChange(val: string) {
    setRangePreset(val as TimeRangePreset)
    setLoading(true)
    setError(null)
  }

  function handleAssetChange(val: string | null) {
    setSelectedAssetId(val)
    setLoading(true)
    setError(null)
  }

  function handleRefresh() {
    setReloadToken((t) => t + 1)
    setLoading(true)
    setError(null)
  }

  // Fetch utilization statistics and heatmap matrix when preset, selected asset or reload token changes
  useEffect(() => {
    let ignore = false
    const windowRange = getDateWindow(rangePreset)
    const assetIdNum = selectedAssetId ? Number(selectedAssetId) : undefined

    Promise.all([
      getUtilizationAnalytics(windowRange.from, windowRange.to),
      getDemandHeatmap(windowRange.from, windowRange.to, assetIdNum),
    ])
      .then(([utilRes, heatRes]) => {
        if (!ignore) {
          setUtilization(utilRes)
          setHeatmap(heatRes)
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
  }, [rangePreset, selectedAssetId, reloadToken])

  // Heatmap quick lookup table: [dayOfWeek][hour] -> HeatmapCell
  const heatmapLookup = useMemo(() => {
    const map: Record<number, Record<number, HeatmapCell>> = {}
    if (!heatmap) return map

    for (const cell of heatmap.cells) {
      if (!map[cell.dayOfWeek]) {
        map[cell.dayOfWeek] = {}
      }
      map[cell.dayOfWeek][cell.hour] = cell
    }
    return map
  }, [heatmap])

  // Per-equipment table: filtered when an asset is selected
  const displayedAssets = useMemo(() => {
    if (!utilization) return []
    if (!selectedAssetId) return utilization.assets
    const targetId = Number(selectedAssetId)
    return utilization.assets.filter((a) => a.assetId === targetId)
  }, [utilization, selectedAssetId])

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
              onClick={handleRefresh}
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
              onChange={handlePresetChange}
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
              onChange={handleAssetChange}
              data={assetsList.map((a) => ({
                value: String(a.id),
                label: `${a.name} (${a.model})`,
              }))}
              style={{ width: 280 }}
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
          <AnalyticsKpiCards utilization={utilization} selectedAssetId={selectedAssetId} />

          {/* Resource Capacity Planning & Bottleneck Alerts */}
          <BottlenecksAlert bottlenecks={utilization.bottlenecks} />

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
              <DemandHeatmapGrid heatmap={heatmap} heatmapLookup={heatmapLookup} />
            </Tabs.Panel>

            {/* TAB 2: Asset Breakdown Table */}
            <Tabs.Panel value="assets" pt="md">
              <AssetUtilizationTable displayedAssets={displayedAssets} selectedAssetId={selectedAssetId} />
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </Stack>
  )
}
