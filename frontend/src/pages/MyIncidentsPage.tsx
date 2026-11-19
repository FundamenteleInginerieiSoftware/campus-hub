import { ActionIcon, Alert, Badge, Button, Text, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconCheck, IconAlertTriangle, IconPhoto, IconPlus } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import {
  fetchMyIncidents,
  formatCampusDateTime,
  severityColor,
  SEVERITIES,
  statusColor,
  statusLabel,
  takesOutOfUseAtOnce,
  type Incident,
} from '../api/incidents.ts'
import { fetchAssetSummary, type AssetSummary } from '../api/reservations.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import IncidentPhotoModal from '../components/IncidentPhotoModal.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'
import ReportIncidentModal from '../components/ReportIncidentModal.tsx'

const PAGE_SIZE = 10

type Notice = { color: 'green' | 'yellow'; text: string }

/**
 * My incidents: what I reported and where it is now (OPEN → IN_REPAIR → RESOLVED, or REJECTED).
 * "Report a problem" opens the form. Other pages can open it for one piece of equipment with
 * /incidents?assetId=7 (for example a "Report a problem" button next to a booking).
 */
export default function MyIncidentsPage() {
  const [pageData, setPageData] = useState<PageResponse<Incident> | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [photoOf, setPhotoOf] = useState<number | null>(null)

  // Names for the equipment column; the list itself only has the asset id.
  const [assets, setAssets] = useState<Record<number, AssetSummary>>({})
  const assetsRef = useRef<Record<number, AssetSummary>>({})

  const [searchParams, setSearchParams] = useSearchParams()
  const linkedAssetId = Number(searchParams.get('assetId')) || undefined
  const [formOpened, { open: openForm, close: closeForm }] = useDisclosure(linkedAssetId !== undefined)

  useEffect(() => {
    let ignore = false
    fetchMyIncidents(pageNumber, PAGE_SIZE)
      .then(async (response) => {
        if (ignore) return
        setError(null)
        setPageData(response)

        const missing = [...new Set(response.content.map((i) => i.assetId))].filter((id) => !assetsRef.current[id])
        if (missing.length === 0) return
        // A failed name lookup only shows "#id" instead of the name; it is not an error for the page.
        const found = await Promise.all(missing.map((id) => fetchAssetSummary(id).catch(() => null)))
        if (ignore) return
        const added: Record<number, AssetSummary> = {}
        for (const asset of found) {
          if (asset) added[asset.id] = asset
        }
        assetsRef.current = { ...assetsRef.current, ...added }
        setAssets(assetsRef.current)
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
  }, [pageNumber, reloadToken])

  function handleCloseForm() {
    closeForm()
    // Drop ?assetId= so a reload does not open the form again.
    if (linkedAssetId !== undefined) {
      setSearchParams({}, { replace: true })
    }
  }

  function handleReported(incident: Incident, photoSaved: boolean) {
    handleCloseForm()
    setNotice(
      photoSaved
        ? {
            color: 'green',
            text: takesOutOfUseAtOnce(incident.severity)
              ? `Incident #${incident.id} was reported. The equipment is out of use until an admin closes it.`
              : `Incident #${incident.id} was reported. An admin will check the equipment; it stays in use meanwhile.`,
          }
        : { color: 'yellow', text: `Incident #${incident.id} was reported without a photo.` },
    )
    // The new incident is the newest one, so it is first on page 0.
    setLoading(true)
    setPageNumber(0)
    setReloadToken((t) => t + 1)
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
              {asset.model}
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
        <Text size="sm" lineClamp={2} maw="40ch">
          {i.description}
        </Text>
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
    {
      header: 'Status',
      render: (i) => <Badge color={statusColor(i.status)}>{statusLabel(i.status)}</Badge>,
    },
    { header: 'Reported', render: (i) => formatCampusDateTime(i.createdAt) },
    {
      header: 'Outcome',
      render: (i) =>
        i.resolvedAt ? (
          <div>
            <Text size="sm">{formatCampusDateTime(i.resolvedAt)}</Text>
            {i.resolutionNotes && (
              <Text size="xs" c="dimmed" lineClamp={2} maw="30ch">
                {i.resolutionNotes}
              </Text>
            )}
          </div>
        ) : (
          <Text size="sm" c="dimmed">
            –
          </Text>
        ),
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
  ]

  return (
    <>
      <PageHeader
        title="My incidents"
        description="Problems you reported and where they are now. High and Critical reports take the equipment out of use until an admin closes them; Low and Medium are checked first. You can have at most 3 open reports."
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={openForm}>
            Report a problem
          </Button>
        }
      />

      {notice && (
        <Alert
          color={notice.color}
          icon={notice.color === 'green' ? <IconCheck size={18} /> : <IconAlertTriangle size={18} />}
          withCloseButton
          onClose={() => setNotice(null)}
          mb="md"
        >
          {notice.text}
        </Alert>
      )}
      <ErrorAlert error={error} />

      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(i) => i.id}
        onPageChange={(next) => {
          // Mantine also calls this for the page you are already on; the effect would not run, so loading
          // would stay true (same guard as #133 / #196).
          if (next === pageNumber) return
          setLoading(true)
          setPageNumber(next)
        }}
        loading={loading}
        emptyText="You have not reported any problems yet."
      />

      {photoOf !== null && <IncidentPhotoModal incidentId={photoOf} onClose={() => setPhotoOf(null)} />}
      {formOpened && (
        <ReportIncidentModal onClose={handleCloseForm} onReported={handleReported} initialAssetId={linkedAssetId} />
      )}
    </>
  )
}
