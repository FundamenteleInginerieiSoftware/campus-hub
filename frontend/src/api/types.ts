// Shared response shapes (api-contract §1). Module-specific types live in src/api/<module>.ts.

// Every paginated list from the backend (Liviu's PageResponse). "page" starts at 0.
export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
