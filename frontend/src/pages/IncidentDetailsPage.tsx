import {
  Alert,
  Anchor,
  Badge,
  Button,
  Center,
  FileInput,
  Grid,
  Group,
  Image,
  Loader,
  Paper,
  Stack,
  Text,
  Timeline,
  Title,
  Tooltip,
} from '@mantine/core'
import { IconArrowLeft, IconCheck, IconFlag, IconPhoto, IconTool, IconUpload, IconX } from '@tabler/icons-react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { fetchAdminUser, type AdminUser } from '../api/adminUsers.ts'
import {
  fetchIncident,
  fetchIncidentPhoto,
  formatCampusDateTime,
  photoProblem,
  PHOTO_TYPES,
  severityColor,
  SEVERITIES,
  startRepair,
  statusColor,
  statusLabel,
  takesOutOfUseAtOnce,
  uploadIncidentPhoto,
  type Incident,
} from '../api/incidents.ts'
import { fetchAssetSummary, type AssetSummary } from '../api/reservations.ts'
import { useAuth } from '../auth/useAuth.ts'
import CloseIncidentModal from '../components/CloseIncidentModal.tsx'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

/**
 * One incident in full (W7): the reporter or an admin opens it from My incidents, the Incident queue or the
 * Reassignment log. Everything the list rows cut short is here: the whole description, the photo, who closed
 * it and why. An admin can also start the repair, resolve or reject from here; the reporter or an admin can
 * add or replace the photo while the incident is open. Someone else's incident is a 404 from the backend.
 */
export default function IncidentDetailsPage() {
  const { id } = useParams()
  const incidentId = Number(id)
  const validId = Number.isInteger(incidentId) && incidentId > 0
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'

  const [incident, setIncident] = useState<Incident | null>(null)
  const [loading, setLoading] = useState(validId)
  const [error, setError] = useState<unknown>(null)

  const [asset, setAsset] = useState<AssetSummary | null>(null)
  const [reporter, setReporter] = useState<AdminUser | null>(null)
  const [closer, setCloser] = useState<AdminUser | null>(null)

  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [starting, setStarting] = useState(false)
  const [closing, setClosing] = useState<'resolve' | 'reject' | null>(null)

  useEffect(() => {
    if (!validId) return
    let ignore = false
    fetchIncident(incidentId)
      .then(async (found) => {
        if (ignore) return
        setError(null)
        setIncident(found)
        // Names only make the page nicer: a failed lookup shows "#id" and is not an error for the page.
        // Only an admin may read other users (GET /admin/users/{id}); a student is the reporter anyway.
        const [foundAsset, foundReporter] = await Promise.all([
          fetchAssetSummary(found.assetId).catch(() => null),
          isAdmin ? fetchAdminUser(found.reportedById).catch(() => null) : Promise.resolve(null),
        ])
        if (ignore) return
        setAsset(foundAsset)
        setReporter(foundReporter)
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
  }, [incidentId, validId, isAdmin])

  // Who closed it: looked up separately, because it changes when the admin closes the incident on this page.
  const closedById = incident?.resolvedById ?? null
  useEffect(() => {
    if (!isAdmin || closedById === null) return
    let ignore = false
    fetchAdminUser(closedById)
      .then((found) => {
        if (!ignore) setCloser(found)
      })
      .catch(() => {
        // only the name is missing; the date and the notes are still shown
      })
    return () => {
      ignore = true
    }
  }, [closedById, isAdmin])

  async function handleStartRepair(current: Incident) {
    setStarting(true)
    setActionError(null)
    try {
      setIncident(await startRepair(current.id))
      setNotice(
        takesOutOfUseAtOnce(current.severity)
          ? 'The incident is now in repair.'
          : 'The incident is now in repair. The equipment is out of use and its bookings are being moved.',
      )
    } catch (err) {
      setActionError(err)
    } finally {
      setStarting(false)
    }
  }

  function handleClosed(updated: Incident) {
    setClosing(null)
    setIncident(updated)
    setNotice(
      `The incident was ${updated.status === 'RESOLVED' ? 'resolved' : 'rejected'}. ` +
        'The equipment is available again unless another incident is still open on it.',
    )
  }

  const backTo = isAdmin ? '/admin/incidents' : '/incidents'
  const backLabel = isAdmin ? 'Incident queue' : 'My incidents'
  const backButton = (
    <Button component={Link} to={backTo} variant="default" leftSection={<IconArrowLeft size={16} />}>
      {backLabel}
    </Button>
  )

  if (!validId) {
    return (
      <>
        <PageHeader title="Incident not found" actions={backButton} />
        <Text c="dimmed">This address does not point to an incident.</Text>
      </>
    )
  }

  if (loading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }

  if (!incident) {
    return (
      <>
        <PageHeader title={`Incident #${incidentId}`} actions={backButton} />
        <ErrorAlert error={error} />
      </>
    )
  }

  const open = incident.status === 'OPEN' || incident.status === 'IN_REPAIR'
  const closed = !open
  const severity = SEVERITIES.find((s) => s.value === incident.severity)
  const reporterName = isAdmin ? (reporter ? reporter.fullName : `user #${incident.reportedById}`) : 'you'

  return (
    <>
      <PageHeader
        title={`Incident #${incident.id}`}
        description={asset ? `${asset.name} (${asset.model})` : `Equipment #${incident.assetId}`}
        actions={backButton}
      />

      <Group gap="xs" mb="md">
        <Badge color={statusColor(incident.status)} size="lg">
          {statusLabel(incident.status)}
        </Badge>
        <Tooltip label={severity?.hint ?? ''} disabled={!severity}>
          <Badge color={severityColor(incident.severity)} variant="light" size="lg">
            {severity?.label ?? incident.severity} severity
          </Badge>
        </Tooltip>
      </Group>

      {notice && (
        <Alert color="green" icon={<IconCheck size={18} />} withCloseButton onClose={() => setNotice(null)} mb="md">
          {notice}
        </Alert>
      )}
      <ErrorAlert error={actionError} />

      {isAdmin && open && (
        <Paper withBorder p="md" mb="md" bg="var(--app-surface)">
          <Group justify="space-between" wrap="wrap" gap="sm">
            <Text size="sm" c="dimmed" maw="60ch">
              {incident.status === 'OPEN'
                ? takesOutOfUseAtOnce(incident.severity)
                  ? 'The equipment is already out of use. Start the repair, or close the incident.'
                  : 'The equipment is still in use. Starting the repair takes it out of use and moves its bookings.'
                : 'The repair is in progress. Resolve it when the equipment works again.'}
            </Text>
            <Group gap="xs">
              {incident.status === 'OPEN' && (
                <Button
                  variant="light"
                  leftSection={<IconTool size={16} />}
                  loading={starting}
                  onClick={() => handleStartRepair(incident)}
                >
                  Start repair
                </Button>
              )}
              <Button
                variant="light"
                color="green"
                leftSection={<IconCheck size={16} />}
                disabled={starting}
                onClick={() => setClosing('resolve')}
              >
                Resolve
              </Button>
              <Button
                variant="subtle"
                color="red"
                leftSection={<IconX size={16} />}
                disabled={starting}
                onClick={() => setClosing('reject')}
              >
                Reject
              </Button>
            </Group>
          </Group>
        </Paper>
      )}

      <Grid gap="md">
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Stack gap="md">
            <Paper withBorder p="md" bg="var(--app-surface)">
              <Title order={4} mb="xs">
                What is wrong
              </Title>
              {/* pre-wrap keeps the line breaks the reporter typed */}
              <Text style={{ whiteSpace: 'pre-wrap' }}>{incident.description}</Text>
            </Paper>

            <Paper withBorder p="md" bg="var(--app-surface)">
              <Title order={4} mb="sm">
                Details
              </Title>
              <Stack gap="xs">
                <Row label="Equipment">
                  {asset ? (
                    <div>
                      <Anchor component={Link} to={`/catalog/${asset.id}`} fw={500}>
                        {asset.name}
                      </Anchor>
                      <Text size="xs" c="dimmed">
                        {asset.model} · SN {asset.serialNumber}
                      </Text>
                    </div>
                  ) : (
                    `#${incident.assetId}`
                  )}
                </Row>
                <Row label="Reported">
                  {formatCampusDateTime(incident.createdAt)}, by{' '}
                  {isAdmin && reporter ? (
                    <Tooltip label={reporter.email}>
                      <span>{reporterName}</span>
                    </Tooltip>
                  ) : (
                    reporterName
                  )}
                </Row>
                <Row label="Booking">{incident.reservationId !== null ? `#${incident.reservationId}` : '–'}</Row>
                <Row label="Equipment use">
                  {open
                    ? incident.status === 'IN_REPAIR' || takesOutOfUseAtOnce(incident.severity)
                      ? 'Out of use while this incident is open.'
                      : 'Still in use until an admin starts the repair.'
                    : 'This incident no longer keeps the equipment out of use.'}
                </Row>
              </Stack>
            </Paper>
          </Stack>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 5 }}>
          <Stack gap="md">
            <Paper withBorder p="md" bg="var(--app-surface)">
              <Title order={4} mb="sm">
                History
              </Title>
              <Timeline active={closed ? 2 : incident.status === 'IN_REPAIR' ? 1 : 0} bulletSize={24} lineWidth={2}>
                <Timeline.Item bullet={<IconFlag size={14} />} title="Reported">
                  <Text size="sm" c="dimmed">
                    {formatCampusDateTime(incident.createdAt)}
                  </Text>
                </Timeline.Item>
                <Timeline.Item bullet={<IconTool size={14} />} title="In repair">
                  <Text size="sm" c="dimmed">
                    {incident.status === 'IN_REPAIR'
                      ? 'An admin is working on it.'
                      : incident.status === 'OPEN'
                        ? 'Waiting for an admin.'
                        : '–'}
                  </Text>
                </Timeline.Item>
                <Timeline.Item
                  bullet={incident.status === 'REJECTED' ? <IconX size={14} /> : <IconCheck size={14} />}
                  title={closed ? statusLabel(incident.status) : 'Resolved or rejected'}
                  color={closed ? statusColor(incident.status) : undefined}
                >
                  {closed ? (
                    <>
                      <Text size="sm" c="dimmed">
                        {formatCampusDateTime(incident.resolvedAt)}
                        {isAdmin && closer ? `, by ${closer.fullName}` : ''}
                      </Text>
                      {incident.resolutionNotes && (
                        <Text size="sm" mt={4} style={{ whiteSpace: 'pre-wrap' }}>
                          {incident.resolutionNotes}
                        </Text>
                      )}
                    </>
                  ) : (
                    <Text size="sm" c="dimmed">
                      Not yet.
                    </Text>
                  )}
                </Timeline.Item>
              </Timeline>
            </Paper>

            <PhotoPanel incident={incident} canChange={open} onChanged={(updated) => setIncident(updated)} />
          </Stack>
        </Grid.Col>
      </Grid>

      {closing && (
        <CloseIncidentModal
          incident={incident}
          outcome={closing}
          onClose={() => setClosing(null)}
          onDone={handleClosed}
        />
      )}
    </>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group align="flex-start" gap="md" wrap="nowrap">
      <Text size="sm" c="dimmed" w={120} style={{ flexShrink: 0 }}>
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </Group>
  )
}

/**
 * The photo, fetched with the token and shown through a blob: URL (a plain <img src> sends no token).
 * While the incident is open the reporter or an admin can add one, or replace it; the backend keeps one.
 */
function PhotoPanel({
  incident,
  canChange,
  onChanged,
}: {
  incident: Incident
  canChange: boolean
  onChanged: (updated: Incident) => void
}) {
  // Each result remembers which photoKey it belongs to, so after a replacement the old (already freed)
  // image is never shown while the new one loads.
  const [loaded, setLoaded] = useState<{ key: string; url: string } | null>(null)
  const [failed, setFailed] = useState<{ key: string; error: unknown } | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<unknown>(null)

  // A new photoKey (first upload or a replacement) loads the new image.
  const photoKey = incident.photoKey
  useEffect(() => {
    if (!photoKey) return
    let objectUrl: string | null = null
    let ignore = false
    fetchIncidentPhoto(incident.id)
      .then((blob) => {
        if (ignore) return
        objectUrl = URL.createObjectURL(blob)
        setLoaded({ key: photoKey, url: objectUrl })
      })
      .catch((err) => {
        if (!ignore) setFailed({ key: photoKey, error: err })
      })
    return () => {
      ignore = true
      // Frees the old image when it is replaced or the page is left.
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [incident.id, photoKey])

  const url = loaded && loaded.key === photoKey ? loaded.url : null
  const photoError = failed && failed.key === photoKey ? failed.error : null
  const problem = photoProblem(file)

  async function handleUpload() {
    if (!file || problem) return
    setUploading(true)
    setUploadError(null)
    try {
      onChanged(await uploadIncidentPhoto(incident.id, file))
      setFile(null)
    } catch (err) {
      setUploadError(err)
    } finally {
      setUploading(false)
    }
  }

  if (!photoKey && !canChange) {
    return null
  }

  return (
    <Paper withBorder p="md" bg="var(--app-surface)">
      <Title order={4} mb="sm">
        Photo
      </Title>
      {photoKey ? (
        <>
          <ErrorAlert error={photoError} />
          {!photoError && !url && (
            <Center py="lg">
              <Loader size="sm" />
            </Center>
          )}
          {url && (
            <Anchor href={url} target="_blank" rel="noreferrer" aria-label="Open the photo in full size">
              <Image src={url} alt={`Photo of incident #${incident.id}`} radius="sm" fit="contain" mah={320} />
            </Anchor>
          )}
        </>
      ) : (
        <Text size="sm" c="dimmed" mb="sm">
          No photo yet. A photo helps the admin see the problem before going to the lab.
        </Text>
      )}

      {canChange && (
        <Stack gap="xs" mt="sm">
          <ErrorAlert error={uploadError} />
          <FileInput
            aria-label={photoKey ? 'Replace the photo' : 'Add a photo'}
            placeholder={
              photoKey
                ? 'Replace the photo (JPEG, PNG or WebP, max 5 MB)'
                : 'Pick a photo (JPEG, PNG or WebP, max 5 MB)'
            }
            accept={PHOTO_TYPES.join(',')}
            leftSection={<IconPhoto size={16} />}
            value={file}
            onChange={setFile}
            error={problem}
            clearable
          />
          <Button
            leftSection={<IconUpload size={16} />}
            disabled={!file || problem !== null}
            loading={uploading}
            onClick={handleUpload}
          >
            {photoKey ? 'Replace photo' : 'Upload photo'}
          </Button>
        </Stack>
      )}
    </Paper>
  )
}
