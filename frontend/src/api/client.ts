// The one way every page talks to the backend. It adds the login token to each request and turns
// the backend's error body (ProblemDetail with a "code", api-contract §4) into an ApiError.

export type FieldError = { field: string; message: string }

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

const TOKEN_KEY = 'campus.token'

// localStorage keeps the user logged in after closing the browser; the token itself expires after 8 h.
// try/catch: some browsers (private mode, blocked site data) throw when storage is used.
export const tokenStorage = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token)
    } catch {
      // nothing to do: the user stays logged in until the page is reloaded
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      // nothing to do
    }
  },
}

// AuthProvider registers what happens when the backend says the token is no longer valid.
let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  // A plain object is sent as JSON. FormData (a file upload) is sent as multipart/form-data:
  // the browser then sets the Content-Type itself, with the boundary between the parts.
  body?: unknown
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = tokenStorage.get()
  const headers: Record<string, string> = { Accept: 'application/json' }
  const isForm = options.body instanceof FormData
  if (options.body !== undefined && !isForm) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: isForm ? (options.body as FormData) : options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  if (!response.ok) {
    throw await toApiError(response, token)
  }

  // 204 No Content has no body to read.
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

// For files (an incident photo): the body comes back as a Blob, to show with URL.createObjectURL.
// A plain <img src="/api/..."> would send no token, so the image has to be fetched like this.
export async function apiFetchBlob(path: string): Promise<Blob> {
  const token = tokenStorage.get()
  const response = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  if (!response.ok) {
    throw await toApiError(response, token)
  }
  return response.blob()
}

// The same error handling for JSON and file requests.
async function toApiError(response: Response, token: string | null): Promise<ApiError> {
  const problem = await response.json().catch(() => null)
  // An expired or invalid token: forget it and send the user back to the login page.
  // Login itself also answers 401 (wrong password), but then no token was sent, so we skip this.
  if (response.status === 401 && token) {
    tokenStorage.clear()
    onUnauthorized()
  }
  return new ApiError(
    response.status,
    problem?.code ?? 'UNKNOWN_ERROR',
    problem?.detail ?? `Request failed with status ${response.status}`,
    problem?.errors ?? [],
  )
}
