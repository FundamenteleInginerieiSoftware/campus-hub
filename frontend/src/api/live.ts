import { tokenStorage } from './client.ts'

export type LiveEvent = {
  type: 'NOTIFICATION'
  title: string
  message: string
  at: string
}

type LiveListener = (event: LiveEvent) => void

const RETRY_DELAYS_MS = [1000, 2000, 5000, 15000, 30000]
// On 409 LIVE_STREAM_LIMIT (e.g. rapid page reloads before the 20 s server heartbeat drops
// closed tabs), wait 30 s before retrying so we recover without hammering the server.
const LIMIT_RETRY_DELAY_MS = 30000

const listeners = new Set<LiveListener>()

/**
 * Registers a listener for incoming live events and returns an unsubscribe function.
 * Each listener is invoked inside a try/catch so one failing page never blocks others.
 */
export function subscribeLive(listener: LiveListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function notifyListeners(event: LiveEvent) {
  for (const listener of listeners) {
    try {
      listener(event)
    } catch {
      // keep delivering to remaining listeners even if one throws
    }
  }
}

/**
 * Splits a raw SSE text buffer on blank lines and parses complete JSON notification frames.
 * Any trailing incomplete block (when a frame is split across network chunks) is returned in `rest`.
 */
export function parseSseChunk(buffer: string): { events: LiveEvent[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const blocks = normalized.split('\n\n')
  const rest = blocks.pop() ?? ''
  const events: LiveEvent[] = []

  for (const block of blocks) {
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) {
        continue
      }
      if (line.startsWith('data:')) {
        const value = line.slice(5)
        dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
      }
    }
    if (dataLines.length === 0) {
      continue
    }
    try {
      const parsed: unknown = JSON.parse(dataLines.join('\n'))
      if (isLiveEvent(parsed)) {
        events.push(parsed)
      }
    } catch {
      // skip malformed JSON frames rather than breaking the stream
    }
  }

  return { events, rest }
}

function isLiveEvent(value: unknown): value is LiveEvent {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return (
    record.type === 'NOTIFICATION' &&
    typeof record.title === 'string' &&
    typeof record.message === 'string' &&
    typeof record.at === 'string'
  )
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      window.clearTimeout(timer)
      resolve()
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Opens the SSE stream using `fetch` + `Authorization: Bearer ...` (never putting JWTs in URLs)
 * and automatically reconnects with backoff until the returned cleanup function is called.
 */
export function connectLive(): () => void {
  if (!tokenStorage.get()) {
    return () => {}
  }

  const controller = new AbortController()
  let stopped = false

  const run = async () => {
    let attempt = 0

    while (!stopped && !controller.signal.aborted) {
      const token = tokenStorage.get()
      if (!token) {
        break
      }

      let deliveredBytes = false

      try {
        const response = await fetch('/api/v1/live/stream', {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'text/event-stream',
          },
          signal: controller.signal,
        })

        if (response.status === 401) {
          // Logged out or token expired; normal API calls handle redirecting to /login.
          break
        }

        if (response.status === 409) {
          await sleep(LIMIT_RETRY_DELAY_MS, controller.signal)
          continue
        }

        if (!response.ok || !response.body) {
          const delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)]
          attempt += 1
          await sleep(delay, controller.signal)
          continue
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''

        while (!stopped && !controller.signal.aborted) {
          const { done, value } = await reader.read()
          if (done) {
            break
          }
          if (value && value.byteLength > 0) {
            deliveredBytes = true
            buffer += decoder.decode(value, { stream: true })
            const { events, rest } = parseSseChunk(buffer)
            buffer = rest
            for (const event of events) {
              notifyListeners(event)
            }
          }
        }
      } catch {
        if (stopped || controller.signal.aborted) {
          break
        }
      }

      if (stopped || controller.signal.aborted) {
        break
      }

      if (deliveredBytes) {
        attempt = 0
      }
      const delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)]
      attempt += 1
      await sleep(delay, controller.signal)
    }
  }

  void run()

  return () => {
    stopped = true
    controller.abort()
  }
}
