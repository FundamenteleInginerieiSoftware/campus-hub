import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client.ts'
import {
  createHold,
  releaseHold,
  type ReservationHoldResponse,
} from '../../api/reservations.ts'
import { HOLD_DURATION_SECONDS } from './bookingRules.ts'

type UseSlotHoldParams = {
  assetId: number
  startIso: string
  endIso: string
}

/**
 * Manages the 1-minute slot hold lifecycle: acquire/release requests,
 * wall-clock countdown timer, selection mismatch guard, and unmount cleanup.
 */
export function useSlotHold({ assetId, startIso, endIso }: UseSlotHoldParams) {
  const [activeHold, setActiveHold] = useState<ReservationHoldResponse | null>(null)
  const [holdRemainingSeconds, setHoldRemainingSeconds] = useState<number>(0)
  const [holdLoading, setHoldLoading] = useState(false)
  const [holdError, setHoldError] = useState<unknown>(null)

  const activeHoldRef = useRef<ReservationHoldResponse | null>(null)
  const deadlineRef = useRef<number>(0)
  const isMountedRef = useRef(true)
  const selectionRef = useRef({ assetId, startIso, endIso })

  useEffect(() => {
    activeHoldRef.current = activeHold
  }, [activeHold])

  useEffect(() => {
    selectionRef.current = { assetId, startIso, endIso }
  }, [assetId, startIso, endIso])

  // Wall-clock deadline resists browser timer throttling in background tabs.
  useEffect(() => {
    if (!activeHold) return
    const updateTimer = () => {
      const remaining = Math.max(0, Math.round((deadlineRef.current - Date.now()) / 1000))
      setHoldRemainingSeconds(remaining)
      if (remaining <= 0) {
        activeHoldRef.current = null
        setActiveHold(null)
      }
    }
    updateTimer()
    const timer = setInterval(updateTimer, 1000)
    return () => clearInterval(timer)
  }, [activeHold])

  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      if (activeHoldRef.current) {
        releaseHold(activeHoldRef.current.id).catch(() => {})
        activeHoldRef.current = null
      }
    }
  }, [])

  function clearActiveHold() {
    activeHoldRef.current = null
    setActiveHold(null)
    setHoldRemainingSeconds(0)
  }

  function discardActiveHold() {
    if (activeHoldRef.current) {
      releaseHold(activeHoldRef.current.id).catch(() => {})
    }
    clearActiveHold()
    setHoldError(null)
  }

  async function handleAcquireHold() {
    setHoldLoading(true)
    setHoldError(null)
    const reqAssetId = assetId
    const reqStart = startIso
    const reqEnd = endIso
    try {
      const hold = await createHold({
        assetId: reqAssetId,
        startTime: reqStart,
        endTime: reqEnd,
      })
      if (!isMountedRef.current) {
        releaseHold(hold.id).catch(() => {})
        return
      }
      // Drop a hold if the server returned a different slot or the user moved on while in flight.
      const matchesServer =
        hold.assetId === reqAssetId &&
        new Date(hold.startTime).getTime() === new Date(reqStart).getTime() &&
        new Date(hold.endTime).getTime() === new Date(reqEnd).getTime()
      const matchesCurrentSelection =
        selectionRef.current.assetId === reqAssetId &&
        selectionRef.current.startIso === reqStart &&
        selectionRef.current.endIso === reqEnd

      if (!matchesServer || !matchesCurrentSelection) {
        releaseHold(hold.id).catch(() => {})
        if (!matchesServer) {
          setHoldError(new ApiError(0, 'HOLD_MISMATCH', 'The server held a different slot. Please try again.'))
        }
        return
      }
      const initialSeconds = hold.remainingSeconds > 0 ? hold.remainingSeconds : HOLD_DURATION_SECONDS
      activeHoldRef.current = hold
      deadlineRef.current = Date.now() + initialSeconds * 1000
      setActiveHold(hold)
      setHoldRemainingSeconds(initialSeconds)
    } catch (err) {
      if (isMountedRef.current) {
        setHoldError(err)
      }
    } finally {
      if (isMountedRef.current) {
        setHoldLoading(false)
      }
    }
  }

  async function handleReleaseHold() {
    if (!activeHold) return
    setHoldLoading(true)
    try {
      await releaseHold(activeHold.id)
      clearActiveHold()
    } catch (err) {
      setHoldError(err)
    } finally {
      setHoldLoading(false)
    }
  }

  return {
    activeHold,
    holdRemainingSeconds,
    holdLoading,
    holdError,
    handleAcquireHold,
    handleReleaseHold,
    discardActiveHold,
    clearActiveHold,
  }
}
