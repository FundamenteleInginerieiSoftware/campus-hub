import { ApiError, apiFetch } from './client.ts'

// Calls for the Projects pages. The endpoints are Matei's ProjectController (api-contract "Projects"):
// the project library lives in backend/src/main/resources/projects/*.json, the availability numbers
// come from Liviu's CatalogApi and Calin's ReservationApi.

export type ProjectLang = 'ro' | 'en'

// A booking slot as the backend wants it: campus time, ISO without offset ("2026-11-10T14:00:00").
export type Slot = { start: string; end: string }

export type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'

export type ProjectListItem = {
  slug: string
  title: string
  summary: string
  difficulty: Difficulty
  estimatedHours: number
  componentCount: number
  score: number
}

export type ProjectSearchResponse = {
  // true when the two best hits score almost the same: the page asks "Did you mean ...?"
  ambiguous: boolean
  items: ProjectListItem[]
}

export type ComponentView = {
  model: string
  quantity: number
  why: string
  available: number
}

export type ProjectDetails = {
  slug: string
  title: string
  summary: string
  difficulty: Difficulty
  estimatedHours: number
  components: ComponentView[]
  bringYourself: string[]
  steps: { title: string; text: string }[]
  mistakes: string[]
  // false: "available" counts units that are not under maintenance or retired.
  // true: it counts only units that are also free in the start/end slot we sent.
  slotChecked: boolean
  // every component has available >= quantity
  reservable: boolean
}

/**
 * Lists or searches projects (GET /api/v1/projects). An empty query returns every project.
 * Search matches both languages; lang only picks the language of the texts that come back.
 */
export function searchProjects(q: string, lang: ProjectLang) {
  const params = new URLSearchParams({ lang })
  if (q.trim()) params.set('q', q.trim())
  return apiFetch<ProjectSearchResponse>(`/api/v1/projects?${params.toString()}`)
}

/**
 * One project with its steps and how many units of each model are free (GET /api/v1/projects/{slug}).
 * start/end ("YYYY-MM-DDTHH:mm:ss", campus time, no offset) must come together: then "available"
 * means free in that slot. 404 PROJECT_NOT_FOUND for an unknown slug.
 */
export function fetchProject(slug: string, lang: ProjectLang, slot?: Slot) {
  const params = new URLSearchParams({ lang })
  if (slot) {
    params.set('start', slot.start)
    params.set('end', slot.end)
  }
  return apiFetch<ProjectDetails>(`/api/v1/projects/${encodeURIComponent(slug)}?${params.toString()}`)
}

// What POST /api/v1/projects/{slug}/reserve returns: every booked unit shares one bundleId, so My
// reservations shows them as one "Bundle booking".
export type ReservedItem = { assetId: number; assetName: string; model: string }

export type ReserveProjectResponse = {
  bundleId: string
  start: string
  end: string
  items: ReservedItem[]
}

/**
 * "Reserve all": the backend picks the lowest-id free unit of every model the project needs and books
 * them together through Calin's bundle booking (all or nothing). 409 PROJECT_COMPONENT_UNAVAILABLE when
 * a model is short; booking-rule and limit codes (RESERVATION_LIMIT_REACHED, SLOT_HELD, ...) come
 * from Calin's ReservationApi.bookBundle unchanged.
 */
export function reserveProject(slug: string, slot: Slot) {
  return apiFetch<ReserveProjectResponse>(`/api/v1/projects/${encodeURIComponent(slug)}/reserve`, {
    method: 'POST',
    body: slot,
  })
}

// The pages' own RO/EN switch, remembered for the next visit. Stefania picks the whole-app approach
// later (#140 §4); until then only the project texts follow it. Default Romanian (most students).
// try/catch: storage throws in some private windows, like in client.ts's tokenStorage.
const LANG_KEY = 'campus.projects.lang'

export function loadProjectLang(): ProjectLang {
  try {
    return localStorage.getItem(LANG_KEY) === 'en' ? 'en' : 'ro'
  } catch {
    return 'ro'
  }
}

export function saveProjectLang(lang: ProjectLang) {
  try {
    localStorage.setItem(LANG_KEY, lang)
  } catch {
    // nothing to do: the choice lasts until the page is reloaded
  }
}

// Texts for our own error codes. Everything else (VALIDATION_FAILED, reservation codes, ...) falls
// back to the shared errorMessages.ts, which Stefania owns, so we don't add our codes there.
const PROJECT_MESSAGES: Record<string, string> = {
  PROJECT_NOT_FOUND: 'This project does not exist. Go back to the list and pick another one.',
  PROJECT_COMPONENT_UNAVAILABLE:
    'Not every item is free in this slot any more (someone booked one a moment ago), so nothing was booked. The numbers are updated: pick another slot.',
}

export function projectErrorMessage(error: unknown): string | null {
  return error instanceof ApiError ? (PROJECT_MESSAGES[error.code] ?? null) : null
}
