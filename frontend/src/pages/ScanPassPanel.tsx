import { Stack, Text } from '@mantine/core'
import { prepareZXingModule, Scanner, type IScannerError } from '@yudiel/react-qr-scanner'
import { useRef, useState } from 'react'
// "?url" makes Vite copy the decoder into our own build and give back its address.
import zxingWasmUrl from 'zxing-wasm/reader/zxing_reader.wasm?url'
import { parseQrText } from '../api/pickupPass.ts'

// Hidden behavior: the QR reader runs on a WebAssembly file, and by default the library downloads it from
// jsDelivr on first use. That breaks when the laptop at the desk is offline (demo) or when a Content
// Security Policy on CloudFront blocks other hosts. This tells it to load the file from our own server.
prepareZXingModule({
  overrides: {
    locateFile: (path: string, prefix: string) => (path.endsWith('.wasm') ? zxingWasmUrl : prefix + path),
  },
})

type Props = {
  // When set, only this student's QR is accepted; when omitted (top-level desk scan), any valid pass works.
  userId?: number
  paused: boolean // true while the backend checks a code
  onScan: (scanned: { userId: number; code: string }) => void // called once per good scan
  onUnavailable: (message: string) => void // the camera cannot start: the dialog shows it and goes back to typing
}

// Camera errors in plain words. All of them leave typing as the way forward.
function cameraMessage(error: IScannerError, anyStudent: boolean): string {
  const fallbackLower = anyStudent ? 'enter the student ID and 6-digit code' : 'type the 6-digit code'
  const fallbackSentence = anyStudent ? 'Enter the student ID and 6-digit code.' : 'Type the 6-digit code.'
  switch (error.kind) {
    case 'permission-denied':
      return `Camera access was blocked. Allow the camera for this site, or ${fallbackLower}.`
    case 'no-camera':
      return `No camera found on this device. ${fallbackSentence}`
    case 'in-use':
      return `The camera is being used by another app. Close it, or ${fallbackLower}.`
    case 'insecure-context':
      // Browsers only give the camera to pages served over HTTPS (or localhost).
      return `The camera only works on a secure (https) page. ${fallbackSentence}`
    default:
      return `The camera could not start. ${fallbackSentence}`
  }
}

export function ScanPassPanel({ userId, paused, onScan, onUnavailable }: Props) {
  const [hint, setHint] = useState<string | null>(null)
  // The scanner keeps reading the same QR several times a second. A pass ("userId:code") that was already
  // sent (and maybe refused, e.g. it expired a moment ago) is not sent again; the next rotation or a
  // different student gives a new key.
  const lastSent = useRef<string | null>(null)

  return (
    <Stack align="center" gap="xs" w="100%">
      <div style={{ width: '100%', maxWidth: 320 }}>
        <Scanner
          formats={['qr_code']}
          // While the backend checks the code the scanner stays frozen, so one QR cannot be sent twice.
          paused={paused}
          onScan={(found) => {
            const scanned = parseQrText(found[0]?.rawValue ?? '')
            if (!scanned || !Number.isSafeInteger(scanned.userId) || scanned.userId < 1) {
              setHint("This is not a campus pickup pass. Scan the QR on the student's Pickup pass page.")
            } else if (userId !== undefined && scanned.userId !== userId) {
              setHint(`This pass belongs to another student (#${scanned.userId}). Ask the right student to scan.`)
            } else {
              const key = `${scanned.userId}:${scanned.code}`
              if (key !== lastSent.current) {
                lastSent.current = key
                setHint(null)
                onScan(scanned)
              }
            }
          }}
          onError={(error) => onUnavailable(cameraMessage(error, userId === undefined))}
        />
      </div>
      {hint && (
        <Text size="sm" c="red" ta="center">
          {hint}
        </Text>
      )}
    </Stack>
  )
}
