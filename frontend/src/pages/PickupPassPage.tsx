import { Button, Center, Loader, Paper, RingProgress, Stack, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { fetchPickupPass, type PickupPass } from '../api/pickupPass.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

const STEP_SECONDS = 30 // the backend changes the code every 30 s (PickupPassService.STEP_SECONDS)

// "493027" → "493 027", easier to read out loud at the desk.
function spaced(code: string): string {
  return `${code.slice(0, 3)} ${code.slice(3)}`
}

export default function PickupPassPage() {
  const [pass, setPass] = useState<PickupPass | null>(null)
  // When the shown code stops working, as a clock time. The ring is computed from it, so the countdown
  // cannot drift and there is no second counter to keep in step.
  const [expiresAt, setExpiresAt] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  // Adding 1 asks the backend for a code again (timer at 0, tab visible again, "Try again").
  const [reloadToken, setReloadToken] = useState(0)

  function reload() {
    setError(null) // a retry must not show the old alert next to the new request
    setReloadToken((t) => t + 1)
  }

  // Every change of reloadToken fetches one code. "ignore" drops the answer of an older request.
  useEffect(() => {
    let ignore = false
    fetchPickupPass()
      .then((fresh) => {
        if (ignore) return
        setPass(fresh)
        setExpiresAt(Date.now() + fresh.validForSeconds * 1000)
        setNow(Date.now())
      })
      .catch((err) => {
        if (ignore) return
        // Bug fixed: after a failed refresh the old code stayed on screen with "0s", so a student could
        // show an expired code at the desk. Fix: drop the code, so only the error and "Try again" show.
        setPass(null)
        setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [reloadToken])

  // One tick per second. At 0 the code is already old on the backend, so ask for the next one.
  useEffect(() => {
    if (!pass) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= expiresAt) {
        clearInterval(timer)
        reload()
      }
    }, 1000)
    return () => clearInterval(timer)
    // reload only calls state setters, which never change
  }, [pass, expiresAt])

  // A hidden tab (phone locked) has its timers paused or slowed, so the shown code may be old when
  // the student comes back: fetch a fresh one at once.
  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') reload()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  function retry() {
    setLoading(true)
    reload()
  }

  const secondsLeft = Math.max(Math.ceil((expiresAt - now) / 1000), 0)

  return (
    <>
      <PageHeader
        title="Pickup pass"
        description="Show this code at the desk when you collect your equipment. It changes every 30 seconds."
      />
      <ErrorAlert error={error} />
      {error !== null && (
        <Button variant="default" onClick={retry} loading={loading} mb="md">
          Try again
        </Button>
      )}
      {loading && !pass && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {pass && (
        <Paper withBorder p="xl" maw={420} bg="var(--app-surface)">
          <Stack align="center" gap="md">
            <Text fz={48} fw={700} ff="monospace" aria-label="Pickup code">
              {spaced(pass.code)}
            </Text>
            <RingProgress
              size={120}
              thickness={10}
              sections={[{ value: (secondsLeft / STEP_SECONDS) * 100, color: 'blue' }]}
              label={
                <Text ta="center" fw={600}>
                  {secondsLeft}s
                </Text>
              }
            />
            <Text size="sm" c="dimmed" ta="center">
              A new code appears by itself. Don't share a screenshot: it stops working in under a minute.
            </Text>
          </Stack>
        </Paper>
      )}
    </>
  )
}
