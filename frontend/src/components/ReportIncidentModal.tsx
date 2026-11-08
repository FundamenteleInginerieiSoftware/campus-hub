import { Alert, Button, FileInput, Group, Modal, Select, Stack, Text, Textarea } from '@mantine/core'
import { useDebouncedValue } from '@mantine/hooks'
import { IconCheck, IconPhoto } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import {
  MAX_DESCRIPTION_LENGTH,
  MAX_PHOTO_BYTES,
  PHOTO_TYPES,
  SEVERITIES,
  reportIncident,
  searchAssets,
  uploadIncidentPhoto,
  type Incident,
  type IncidentSeverity,
} from '../api/incidents.ts'
import { fetchAssetSummary, type AssetSummary } from '../api/reservations.ts'
import ErrorAlert from './ErrorAlert.tsx'

// Mounted only while open ({open && <ReportIncidentModal />}), so every opening starts with an empty form.
type Props = {
  onClose: () => void
  // Called once the incident exists. photoSaved is false when the photo upload failed and the user gave up.
  onReported: (incident: Incident, photoSaved: boolean) => void
  // Pre-selects the equipment, e.g. /incidents?assetId=7 from another page.
  initialAssetId?: number
}

function assetLabel(asset: AssetSummary): string {
  return `${asset.name} (${asset.model}, SN ${asset.serialNumber})`
}

// Checked here only to give a quick answer; the backend checks again (type from the first bytes, 5 MB).
function photoProblem(file: File | null): string | null {
  if (!file) {
    return null
  }
  if (!PHOTO_TYPES.includes(file.type)) {
    return 'Only JPEG, PNG or WebP images are allowed.'
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return 'The photo is too large (max 5 MB).'
  }
  return null
}

/**
 * Report a problem with a piece of equipment: pick it, describe it, choose how bad it is, add a photo
 * if you have one. Two requests: first the report (JSON), then the photo (multipart). If only the photo
 * fails, the incident already exists, so the form stays open with "Try again" instead of reporting twice.
 */
export default function ReportIncidentModal({ onClose, onReported, initialAssetId }: Props) {
  const [selected, setSelected] = useState<AssetSummary | null>(null)
  const [description, setDescription] = useState('')
  const [severity, setSeverity] = useState<IncidentSeverity | null>(null)
  const [photo, setPhoto] = useState<File | null>(null)

  const [search, setSearch] = useState('')
  const [debouncedSearch] = useDebouncedValue(search, 300)
  const [results, setResults] = useState<AssetSummary[]>([])
  // The search text the current results belong to; while it differs from the box, a search is running.
  const [resultsFor, setResultsFor] = useState<string | null>(null)

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  // Kept apart from error: a failed search must not look like a failed report.
  const [searchError, setSearchError] = useState<string | null>(null)
  const [touched, setTouched] = useState(false)
  // Set when the report was saved but the photo was not: the next submit only retries the photo.
  const [savedIncident, setSavedIncident] = useState<Incident | null>(null)

  // The pre-selected asset is loaded by id. GET /assets/{id} also returns RETIRED assets, which cannot be
  // reported (409 ASSET_RETIRED), so those and wrong ids just leave the box empty.
  useEffect(() => {
    if (!initialAssetId) {
      return
    }
    let ignore = false
    fetchAssetSummary(initialAssetId)
      .then((asset) => {
        if (!ignore && asset.status !== 'RETIRED') setSelected(asset)
      })
      .catch(() => {
        // nothing to pre-select: the student picks the equipment by hand
      })
    return () => {
      ignore = true
    }
  }, [initialAssetId])

  // After a pick, the Select puts the whole label in the search box; that is not something to search for.
  const searchText = selected && debouncedSearch === assetLabel(selected) ? '' : debouncedSearch

  // Searching happens on the server (Liviu's GET /api/v1/assets?search=), 300 ms after the last key.
  useEffect(() => {
    let ignore = false
    searchAssets(searchText)
      .then((page) => {
        if (ignore) return
        setResults(page.content.filter((asset) => asset.status !== 'RETIRED'))
        setSearchError(null)
      })
      .catch(() => {
        if (!ignore) setSearchError('Could not load the equipment list. Type again to retry.')
      })
      .finally(() => {
        if (!ignore) setResultsFor(searchText)
      })
    return () => {
      ignore = true
    }
  }, [searchText])

  // The selected asset stays in the list even when the current search does not return it,
  // otherwise the Select would show an empty box.
  const options = selected && !results.some((a) => a.id === selected.id) ? [selected, ...results] : results
  const assetId = selected ? String(selected.id) : null

  const trimmed = description.trim()
  const descriptionError =
    touched && !trimmed
      ? 'Describe what is wrong'
      : description.length > MAX_DESCRIPTION_LENGTH
        ? `At most ${MAX_DESCRIPTION_LENGTH} characters`
        : null
  const photoError = photoProblem(photo)
  const canSubmit = savedIncident
    ? photo !== null && !photoError
    : assetId !== null && trimmed.length > 0 && description.length <= MAX_DESCRIPTION_LENGTH && severity !== null && !photoError

  async function handleSubmit() {
    setTouched(true)
    if (!canSubmit || submitting) {
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const incident =
        savedIncident ??
        (await reportIncident({ assetId: Number(assetId), description: trimmed, severity: severity as IncidentSeverity }))
      if (!photo) {
        onReported(incident, true)
        return
      }
      try {
        onReported(await uploadIncidentPhoto(incident.id, photo), true)
      } catch (photoErr) {
        setSavedIncident(incident)
        setError(photoErr)
      }
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  function handleClose() {
    if (submitting) {
      return
    }
    // Closing after a failed photo: the incident exists, so the list must still show it.
    if (savedIncident) {
      onReported(savedIncident, false)
      return
    }
    onClose()
  }

  return (
    <Modal opened onClose={handleClose} title="Report a problem" size="lg" centered>
      <Stack>
        {savedIncident && (
          <Alert color="green" icon={<IconCheck size={18} />}>
            Incident #{savedIncident.id} was reported and the equipment is now out of use. Only the photo was not
            saved: pick another one and try again, or close without a photo.
          </Alert>
        )}
        <ErrorAlert error={error} />

        <Select
          label="Equipment"
          placeholder="Search by name, model or serial number"
          required
          searchable
          clearable
          disabled={savedIncident !== null}
          data={options.map((asset) => ({ value: String(asset.id), label: assetLabel(asset) }))}
          value={assetId}
          onChange={(value) => setSelected(options.find((a) => String(a.id) === value) ?? null)}
          searchValue={search}
          onSearchChange={setSearch}
          // The server already filtered by the text; filtering again here would hide nothing useful.
          filter={({ options: all }) => all}
          nothingFoundMessage={resultsFor !== searchText ? 'Searching…' : 'No equipment matches'}
          error={searchError ?? (touched && assetId === null ? 'Pick the equipment' : null)}
        />

        <Textarea
          label="What is wrong?"
          placeholder="E.g. the display is cracked and channel 2 shows no signal"
          required
          autosize
          minRows={3}
          maxRows={8}
          disabled={savedIncident !== null}
          value={description}
          onChange={(event) => setDescription(event.currentTarget.value)}
          error={descriptionError}
          description={`${description.length} / ${MAX_DESCRIPTION_LENGTH}`}
        />

        <Select
          label="How bad is it?"
          placeholder="Pick a severity"
          required
          disabled={savedIncident !== null}
          data={SEVERITIES.map((s) => ({ value: s.value, label: `${s.label} – ${s.hint}` }))}
          value={severity}
          onChange={(value) => setSeverity(value as IncidentSeverity | null)}
          error={touched && severity === null ? 'Pick a severity' : null}
        />

        <FileInput
          label="Photo (optional)"
          placeholder="JPEG, PNG or WebP, max 5 MB"
          accept={PHOTO_TYPES.join(',')}
          clearable
          leftSection={<IconPhoto size={16} />}
          value={photo}
          onChange={setPhoto}
          error={photoError}
        />

        <Text size="sm" c="dimmed">
          The equipment is taken out of use as soon as you report it. Students with a booking on it are moved to
          another one automatically, if one is free.
        </Text>

        <Group justify="flex-end">
          <Button variant="default" onClick={handleClose} disabled={submitting}>
            {savedIncident ? 'Close without photo' : 'Cancel'}
          </Button>
          <Button onClick={handleSubmit} loading={submitting} disabled={touched && !canSubmit}>
            {savedIncident ? 'Upload photo again' : 'Report'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
