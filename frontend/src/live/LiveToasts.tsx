import { Notification, Stack } from '@mantine/core'
import { IconBell } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { connectLive, subscribeLive, type LiveEvent } from '../api/live.ts'

type ToastItem = {
  id: number
  event: LiveEvent
}

const MAX_VISIBLE_TOASTS = 3
const AUTO_CLOSE_MS = 8000

/**
 * Floating bottom-right toast stack that opens the live SSE stream on mount and displays
 * incoming notifications (newest on top, max 3 cards, auto-closing after 8 seconds).
 *
 * Mounted once inside AppLayout.tsx (Stefania).
 */
export default function LiveToasts() {
  const [items, setItems] = useState<ToastItem[]>([])
  const nextIdRef = useRef(1)
  const timersRef = useRef<Map<number, number>>(new Map())

  const dismiss = (id: number) => {
    const timer = timersRef.current.get(id)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timersRef.current.delete(id)
    }
    setItems((prev) => prev.filter((item) => item.id !== id))
  }

  useEffect(() => {
    const timers = timersRef.current
    const stop = connectLive()
    const off = subscribeLive((event) => {
      const id = nextIdRef.current
      nextIdRef.current += 1

      setItems((prev) => {
        const next = [{ id, event }, ...prev]
        if (next.length > MAX_VISIBLE_TOASTS) {
          const evicted = next.slice(MAX_VISIBLE_TOASTS)
          for (const old of evicted) {
            const oldTimer = timers.get(old.id)
            if (oldTimer !== undefined) {
              window.clearTimeout(oldTimer)
              timers.delete(old.id)
            }
          }
          return next.slice(0, MAX_VISIBLE_TOASTS)
        }
        return next
      })

      const timer = window.setTimeout(() => {
        timers.delete(id)
        setItems((prev) => prev.filter((item) => item.id !== id))
      }, AUTO_CLOSE_MS)
      timers.set(id, timer)
    })

    return () => {
      off()
      stop()
      for (const timer of timers.values()) {
        window.clearTimeout(timer)
      }
      timers.clear()
    }
  }, [])

  if (items.length === 0) {
    return null
  }

  return (
    <div
      aria-live="polite"
      style={{
        position: 'fixed',
        right: 16,
        bottom: 16,
        zIndex: 400,
        width: 360,
        maxWidth: 'calc(100vw - 32px)',
      }}
    >
      <Stack gap="xs">
        {items.map(({ id, event }) => (
          <Notification
            key={id}
            icon={<IconBell size={18} />}
            color="blue"
            title={event.title}
            onClose={() => dismiss(id)}
            withBorder
            radius="md"
          >
            {event.message}
          </Notification>
        ))}
      </Stack>
    </div>
  )
}
