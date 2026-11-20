import { Anchor, Badge, Text, Tooltip } from '@mantine/core'
import { IconArrowRight } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { formatCampusDateTime } from '../api/incidents.ts'
import { fetchReassignmentLogs, type ReassignmentLog } from '../api/reassignments.ts'
import { fetchAssetSummary, type AssetSummary } from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

const PAGE_SIZE = 20

// SameModelStrategy → "Same model". Unknown names are shown as they come.
const STRATEGY_LABELS: Record<string, string> = {
  SameModelStrategy: 'Same model',
  SameCategoryStrategy: 'Same category',
}

/**
 * Reassignment log (admin, #306): what the reassignment engine did after a HIGH/CRITICAL incident or a
 * retired asset. Each row is one booking: moved to a substitute, or cancelled when none was free.
 * The rows only have asset ids, so the names are looked up separately and cached (module boundaries).
 */
export default function ReassignmentLogPage() {
  const [pageData, setPageData] = useState<PageResponse<ReassignmentLog> | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const [assets, setAssets] = useState<Record<number, AssetSummary>>({})
  const assetsRef = useRef<Record<number, AssetSummary>>({})

  useEffect(() => {
    let ignore = false
    fetchReassignmentLogs(pageNumber, PAGE_SIZE)
      .then(async (response) => {
        if (ignore) return
        setError(null)
        setPageData(response)

        const ids = response.content.flatMap((r) => (r.toAssetId ? [r.fromAssetId, r.toAssetId] : [r.fromAssetId]))
        const missing = [...new Set(ids)].filter((id) => !assetsRef.current[id])
        if (missing.length === 0) return
        // A failed lookup only shows "#id"; it is not an error for the page.
        const found = await Promise.all(missing.map((id) => fetchAssetSummary(id).catch(() => null)))
        if (ignore) return
        const added = found.filter((a): a is AssetSummary => a !== null).map((a) => [a.id, a])
        if (added.length > 0) {
          assetsRef.current = { ...assetsRef.current, ...Object.fromEntries(added) }
          setAssets(assetsRef.current)
        }
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
  }, [pageNumber])

  function assetName(id: number) {
    const asset = assets[id]
    return asset ? (
      <Anchor component={Link} to={`/catalog/${id}`} size="sm">
        {asset.name}
      </Anchor>
    ) : (
      <Text size="sm">#{id}</Text>
    )
  }

  const columns: Column<ReassignmentLog>[] = [
    { header: 'When', render: (r) => formatCampusDateTime(r.createdAt) },
    {
      header: 'Why',
      render: (r) =>
        r.trigger === 'RETIRED' ? (
          <Badge color="gray" variant="light">
            Asset retired
          </Badge>
        ) : (
          <Anchor component={Link} to="/admin/incidents" size="sm">
            Incident #{r.incidentId}
          </Anchor>
        ),
    },
    { header: 'Booking', render: (r) => `#${r.reservationId}` },
    {
      header: 'Equipment',
      render: (r) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          {assetName(r.fromAssetId)}
          {r.toAssetId !== null && (
            <>
              <IconArrowRight size={14} aria-label="moved to" />
              {assetName(r.toAssetId)}
            </>
          )}
        </div>
      ),
    },
    {
      header: 'Outcome',
      render: (r) =>
        r.outcome === 'REASSIGNED' ? (
          <Badge color="green">Moved</Badge>
        ) : (
          <Tooltip label="No free substitute: the booking was cancelled and the student was told">
            <Badge color="red">Cancelled</Badge>
          </Tooltip>
        ),
    },
    {
      header: 'Matched by',
      render: (r) =>
        r.strategy ? (
          <Text size="sm">{STRATEGY_LABELS[r.strategy] ?? r.strategy}</Text>
        ) : (
          <Text size="sm" c="dimmed">
            –
          </Text>
        ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Reassignment log"
        description="What happened to bookings automatically when equipment broke (High/Critical incident or repair started) or was retired: moved to a free substitute, or cancelled when none was free."
      />
      <ErrorAlert error={error} />
      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(r) => r.id}
        onPageChange={(next) => {
          if (next === pageNumber) return
          setLoading(true)
          setPageNumber(next)
        }}
        loading={loading}
        emptyText="No bookings have been moved or cancelled automatically yet."
      />
    </>
  )
}
