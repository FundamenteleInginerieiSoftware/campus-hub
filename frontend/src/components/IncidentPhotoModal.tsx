import { Center, Image, Loader, Modal } from '@mantine/core'
import { useEffect, useState } from 'react'
import { fetchIncidentPhoto } from '../api/incidents.ts'
import ErrorAlert from './ErrorAlert.tsx'

type Props = {
  incidentId: number
  onClose: () => void
}

/**
 * Shows the photo of one incident. The image is fetched with the token (apiFetchBlob) and shown through a
 * temporary blob: URL, which is released again when the window closes so the browser frees the memory.
 * Mounted only while open ({id && <IncidentPhotoModal />}).
 */
export default function IncidentPhotoModal({ incidentId, onClose }: Props) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    let objectUrl: string | null = null
    let ignore = false
    fetchIncidentPhoto(incidentId)
      .then((blob) => {
        if (ignore) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
    return () => {
      ignore = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [incidentId])

  return (
    <Modal opened onClose={onClose} title={`Photo of incident #${incidentId}`} size="xl" centered>
      <ErrorAlert error={error} />
      {!error && !url && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {url && <Image src={url} alt={`Photo of incident #${incidentId}`} radius="sm" fit="contain" mah="70vh" />}
    </Modal>
  )
}
