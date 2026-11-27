import { Anchor, Badge, Button, Collapse, List, Paper, SegmentedControl, Text, Tooltip } from '@mantine/core'
import { IconInfoCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { fetchReliability, type AssetReliabilityItem, type ReliabilityReport } from '../api/reliability.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'
import { statusBadgeColor } from './catalog/assetStatus.ts'

const WINDOWS = [
  { value: '90', label: '90 days' },
  { value: '180', label: '180 days' },
  { value: '365', label: '365 days' },
]

// A badge in a narrow table column is cut to "INSP..."; keep it as wide as its text, the table scrolls instead.
const NO_SHRINK = { minWidth: 'max-content' }

const STATUS_LABELS: Record<AssetReliabilityItem['status'], string> = {
  AVAILABLE: 'Available',
  UNDER_MAINTENANCE: 'Under maintenance',
  RETIRED: 'Retired',
}

// The backend sends numbers like 5 or 5.5 in JSON; always show one decimal, and "–" for "not enough data".
function oneDecimal(value: number | null, suffix = '') {
  return value === null ? (
    <Text size="sm" c="dimmed">
      –
    </Text>
  ) : (
    `${value.toFixed(1)}${suffix}`
  )
}

/**
 * Equipment health (admin, #291): the units that keep breaking, worst first, with one sentence why.
 * The backend does the maths (ReliabilityCalculator); this page only shows it. Read only: retiring a unit
 * stays in the inventory, the page links to the unit so the admin can decide.
 */
export default function AdminReliabilityPage() {
  const [days, setDays] = useState('180')
  const [report, setReport] = useState<ReliabilityReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [showHelp, setShowHelp] = useState(false)

  useEffect(() => {
    let ignore = false
    fetchReliability(Number(days))
      .then((response) => {
        if (ignore) return
        setError(null)
        setReport(response)
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [days])

  const columns: Column<AssetReliabilityItem>[] = [
    {
      header: 'Unit',
      render: (r) => (
        <div style={{ minWidth: 180 }}>
          <Anchor component={Link} to={`/catalog/${r.assetId}`} size="sm" fw={500}>
            {r.assetName}
          </Anchor>
          <Text size="xs" c="dimmed">
            {r.model}
          </Text>
        </div>
      ),
    },
    {
      header: 'Status',
      render: (r) => (
        <Badge color={statusBadgeColor(r.status)} variant="light" style={NO_SHRINK}>
          {STATUS_LABELS[r.status] ?? r.status}
        </Badge>
      ),
    },
    {
      header: 'Level',
      render: (r) =>
        r.level === 'INSPECT' ? (
          <Tooltip label="Send it for inspection">
            <Badge color="red" style={NO_SHRINK}>
              Inspect
            </Badge>
          </Tooltip>
        ) : (
          <Tooltip label="Keep an eye on it">
            <Badge color="yellow" style={NO_SHRINK}>
              Watch
            </Badge>
          </Tooltip>
        ),
    },
    {
      header: 'Failures',
      render: (r) => (
        <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
          {r.failures}
          {r.severeFailures > 0 && (
            <Text span size="sm" c="red">
              {' '}
              ({r.severeFailures} severe)
            </Text>
          )}
        </Text>
      ),
    },
    { header: 'Hours used', render: (r) => oneDecimal(r.usageHours) },
    { header: 'Per 100 h', render: (r) => oneDecimal(r.failuresPer100h) },
    { header: 'Vs. model', render: (r) => oneDecimal(r.vsModel, '×') },
    {
      header: 'Why',
      render: (r) => (
        <Text size="sm" miw={220} maw="40ch">
          {r.reason}
        </Text>
      ),
    },
  ]

  // The list is short and not paged, so it is shown as one page of PagedTable (same look as the other admin tables).
  const page = report && {
    content: report.items,
    page: 0,
    size: report.items.length,
    totalElements: report.items.length,
    totalPages: 1,
  }

  return (
    <>
      <PageHeader
        title="Equipment health"
        description="Units that had incidents, worst first. A failure is an incident that was not rejected; it is counted per 100 hours the unit was really checked out and compared with the other units of the same model."
        actions={
          <SegmentedControl
            aria-label="Period"
            value={days}
            data={WINDOWS}
            onChange={(value) => {
              if (value === days) return
              // #364: the error of the old period must not stay on screen while the new one loads.
              setError(null)
              setLoading(true)
              setDays(value)
            }}
          />
        }
      />

      <Button
        variant="subtle"
        size="xs"
        leftSection={<IconInfoCircle size={16} />}
        onClick={() => setShowHelp((open) => !open)}
        mb="sm"
        aria-expanded={showHelp}
      >
        How is this calculated?
      </Button>
      <Collapse expanded={showHelp}>
        <Paper withBorder p="md" mb="md" bg="var(--app-surface)">
          <List size="sm" spacing={4}>
            <List.Item>
              <b>Failure:</b> an incident that was not rejected (open, in repair or resolved). Severe = High or
              Critical.
            </List.Item>
            <List.Item>
              <b>Per 100 h:</b> failures × 100 ÷ hours used. Below 10 hours of use we count 10 hours, so one incident
              after 1 hour does not read as "100 per 100 h".
            </List.Item>
            <List.Item>
              <b>Vs. model:</b> how many times more often the unit fails than the other units of the same model
              together. "–" when they have less than 10 hours of use together or never failed.
            </List.Item>
            <List.Item>
              <b>Inspect:</b> at least 3 failures, or at least 2 failures and failing at least 2× as often as its model.
            </List.Item>
            <List.Item>
              <b>Watch:</b> at least 1 failure. Units without failures are not listed.
            </List.Item>
          </List>
        </Paper>
      </Collapse>

      <ErrorAlert error={error} />
      {/* On an error the table is hidden, so "Every unit is healthy" is never shown for a report that failed. */}
      {!error && (
        <PagedTable
          page={loading ? null : page}
          columns={columns}
          rowKey={(r) => r.assetId}
          onPageChange={() => {}}
          loading={loading}
          emptyText="No incidents in this period. Every unit is healthy."
        />
      )}
    </>
  )
}
