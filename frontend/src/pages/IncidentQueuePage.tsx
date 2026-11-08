import { ActionIcon, Alert, Badge, Button, Group, SegmentedControl, Text, Tooltip } from '@mantine/core'
import { IconCheck, IconPhoto, IconTool, IconX } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { fetchAdminUser, type AdminUser } from '../api/adminUsers.ts'
import {
  fetchAdminIncidents,
  formatCampusDateTime,
  severityColor,
  SEVERITIES,
  startRepair,
  statusColor,
  statusLabel,
  type Incident,
  type IncidentStatus,
} from '../api/incidents.ts'
import { fetchAssetSummary, type AssetSummary } from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import CloseIncidentModal from '../components/CloseIncidentModal.tsx'
import ErrorAlert from '../components/ErrorAlert.tsx'
import IncidentPhotoModal from '../components/IncidentPhotoModal.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

const PAGE_SIZE = 10

// "ALL" means no status filter. OPEN first: that is what an admin has to look at.
type Filter = IncidentStatus | 'ALL'
const FILTERS: { value: Filter; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'IN_REPAIR', label: 'In repair' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All' },
]

type Closing = { incident: Incident; outcome: 'resolve' | 'reject' }

/**
 * Incident queue (admin): every reported problem, newest first, filtered by status.
 * OPEN → Start repair → IN_REPAIR → Resolve; OPEN or IN_REPAIR → Reject. RESOLVED and REJECTED are final.
 * The backend decides what happens to the equipment (back to AVAILABLE when no other incident is open).
 */
export default function IncidentQueuePage() {
  const [filter, setFilter] = useState<Filter>('OPEN')
  const [pageData, setPageData] = useState<PageResponse<Incident> | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)

  // Who and what, for the rows: the list itself only has ids. Cached across pages and filters.
  const [assets, setAssets] = useState<Record<number, AssetSummary>>({})
  const [users, setUsers] = useState<Record<number, AdminUser>>({})
  const assetsRef = useRef<Record<number, AssetSummary>>({})
  const usersRef = useRef<Record<number, AdminUser>>({})

  const [startingId, setStartingId] = useState<number | null>(null)
  const [closing, setClosing] = useState<Closing | null>(null)
  const [photoOf, setPhotoOf] = useState<number | null>(null)

  useEffect(() => {
    let ignore = false
    fetchAdminIncidents(filter === 'ALL' ? null : filter, pageNumber, PAGE_SIZE)
      .then(async (response) => {
        if (ignore) return
        // The last row of a later page was just moved to another status: go back one page.
        if (response.content.length === 0 && pageNumber > 0) {
          setPageNumber(pageNumber - 1)
          return
        }
        setError(null)
        setPageData(response)

        // A failed lookup only shows "#id" instead of the name; it is not an error for the page.
        const assetIds = [...new Set(response.content.map((i) => i.assetId))].filter((id) => !assetsRef.current[id])
        const userIds = [...new Set(response.content.map((i) => i.reportedById))].filter((id) => !usersRef.current[id])
        const [foundAssets, foundUsers] = await Promise.all([
          Promise.all(assetIds.map((id) => fetchAssetSummary(id).catch(() => null))),
          Promise.all(userIds.map((id) => fetchAdminUser(id).catch(() => null))),
        ])
        if (ignore) return
        for (const asset of foundAssets) if (asset) assetsRef.current = { ...assetsRef.current, [asset.id]: asset }
        for (const user of foundUsers) if (user) usersRef.current = { ...usersRef.current, [user.id]: user }
        setAssets(assetsRef.current)
        setUsers(usersRef.current)
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
  }, [filter, pageNumber, reloadToken])

  function reload() {
    setLoading(true)
    setReloadToken((t) => t + 1)
  }

  function handleFilterChange(value: string) {
    setFilter(value as Filter)
    setPageNumber(0)
    setPageData(null)
    setLoading(true)
    setNotice(null)
  }

  async function handleStartRepair(incident: Incident) {
    setStartingId(incident.id)
    setError(null)
    try {
      await startRepair(incident.id)
      setNotice(`Incident #${incident.id} is now in repair.`)
      reload()
    } catch (err) {
      setError(err)
    } finally {
      setStartingId(null)
    }
  }

  function handleClosed(updated: Incident) {
    setClosing(null)
    setNotice(
      `Incident #${updated.id} was ${updated.status === 'RESOLVED' ? 'resolved' : 'rejected'}. ` +
        'The equipment is available again unless another incident is still open on it.',
    )
    reload()
  }

  const columns: Column<Incident>[] = [
    { header: '#', render: (i) => i.id },
    {
      header: 'Equipment',
      render: (i) => {
        const asset = assets[i.assetId]
        return asset ? (
          <div>
            <Text size="sm" fw={500}>
              {asset.name}
            </Text>
            <Text size="xs" c="dimmed">
              SN {asset.serialNumber}
            </Text>
          </div>
        ) : (
          `#${i.assetId}`
        )
      },
    },
    {
      header: 'Problem',
      render: (i) => (
        <Tooltip label={i.description} multiline maw={400} openDelay={300}>
          <Text size="sm" lineClamp={2} maw="36ch">
            {i.description}
          </Text>
        </Tooltip>
      ),
    },
    {
      header: 'Severity',
      render: (i) => (
        <Badge color={severityColor(i.severity)} variant="light">
          {SEVERITIES.find((s) => s.value === i.severity)?.label ?? i.severity}
        </Badge>
      ),
    },
    { header: 'Status', render: (i) => <Badge color={statusColor(i.status)}>{statusLabel(i.status)}</Badge> },
    {
      header: 'Reported',
      render: (i) => {
        const user = users[i.reportedById]
        return (
          <div>
            <Text size="sm">{formatCampusDateTime(i.createdAt)}</Text>
            <Text size="xs" c="dimmed">
              {user ? user.fullName : `user #${i.reportedById}`}
            </Text>
          </div>
        )
      },
    },
    {
      header: 'Photo',
      render: (i) =>
        i.photoKey ? (
          <Tooltip label="View photo">
            <ActionIcon variant="subtle" aria-label={`View photo of incident #${i.id}`} onClick={() => setPhotoOf(i.id)}>
              <IconPhoto size={18} />
            </ActionIcon>
          </Tooltip>
        ) : null,
    },
    {
      header: 'Action',
      render: (i) => {
        if (i.status === 'RESOLVED' || i.status === 'REJECTED') {
          return (
            <div>
              <Text size="sm">{formatCampusDateTime(i.resolvedAt)}</Text>
              {i.resolutionNotes && (
                <Text size="xs" c="dimmed" lineClamp={2} maw="30ch">
                  {i.resolutionNotes}
                </Text>
              )}
            </div>
          )
        }
        return (
          <Group gap="xs" wrap="nowrap">
            {i.status === 'OPEN' && (
              <Button
                size="xs"
                variant="light"
                leftSection={<IconTool size={14} />}
                loading={startingId === i.id}
                onClick={() => handleStartRepair(i)}
              >
                Start repair
              </Button>
            )}
            <Button
              size="xs"
              variant="light"
              color="green"
              leftSection={<IconCheck size={14} />}
              onClick={() => setClosing({ incident: i, outcome: 'resolve' })}
            >
              Resolve
            </Button>
            <Button
              size="xs"
              variant="subtle"
              color="red"
              leftSection={<IconX size={14} />}
              onClick={() => setClosing({ incident: i, outcome: 'reject' })}
            >
              Reject
            </Button>
          </Group>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        title="Incident queue"
        description="Reported problems, newest first. Equipment with an open incident is out of use; resolving or rejecting the last one makes it available again."
      />

      <SegmentedControl data={FILTERS} value={filter} onChange={handleFilterChange} mb="md" />

      {notice && (
        <Alert color="green" icon={<IconCheck size={18} />} withCloseButton onClose={() => setNotice(null)} mb="md">
          {notice}
        </Alert>
      )}
      <ErrorAlert error={error} />

      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(i) => i.id}
        onPageChange={(next) => {
          // Mantine also calls this for the current page; without the guard loading would stay true.
          if (next === pageNumber) return
          setLoading(true)
          setPageNumber(next)
        }}
        loading={loading}
        emptyText={filter === 'OPEN' ? 'No open incidents. Nothing to triage.' : 'No incidents with this status.'}
      />

      {closing && (
        <CloseIncidentModal
          incident={closing.incident}
          outcome={closing.outcome}
          onClose={() => setClosing(null)}
          onDone={handleClosed}
        />
      )}
      {photoOf !== null && <IncidentPhotoModal incidentId={photoOf} onClose={() => setPhotoOf(null)} />}
    </>
  )
}
