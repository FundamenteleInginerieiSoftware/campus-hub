import { apiFetch } from './client.ts'
import type { PageResponse } from './types.ts'

// Types representing Catalog domain objects (docs/api-contract.md §6).

export type AssetStatus = 'AVAILABLE' | 'UNDER_MAINTENANCE' | 'RETIRED'

export type Category = {
  id: number
  name: string
  description: string | null
}

export type CategoryRequest = {
  name: string
  description?: string
}

export type LabRoom = {
  id: number
  code: string
  name: string
  building: string | null
}

export type LabRoomRequest = {
  code: string
  name: string
  building?: string
}

export type Asset = {
  id: number
  name: string
  model: string
  serialNumber: string
  description: string | null
  status: AssetStatus
  categoryId: number
  categoryName: string
  labRoomId: number
  labRoomCode: string
  labRoomName: string
  version?: number
  createdAt: string
  updatedAt: string
}

export type AssetSearch = {
  search?: string
  categoryId?: number
  labRoomId?: number
  status?: AssetStatus
  page?: number
  size?: number
  sort?: string
}

export type CreateAssetRequest = {
  name: string
  model: string
  serialNumber: string
  description?: string
  categoryId: number
  labRoomId: number
}

export type UpdateAssetRequest = {
  name: string
  model: string
  description?: string
  categoryId: number
  labRoomId: number
  version?: number
}

// ----------------------------------------------------------------------
// Category API endpoints
// ----------------------------------------------------------------------

/**
 * Retrieves all categories sorted by name (GET /api/v1/categories).
 */
export function getCategories(): Promise<Category[]> {
  return apiFetch<Category[]>('/api/v1/categories')
}

/**
 * Creates a new category (POST /api/v1/admin/categories). Requires ADMIN.
 */
export function createCategory(data: CategoryRequest): Promise<Category> {
  return apiFetch<Category>('/api/v1/admin/categories', {
    method: 'POST',
    body: data,
  })
}

/**
 * Updates an existing category (PUT /api/v1/admin/categories/{id}). Requires ADMIN.
 */
export function updateCategory(id: number, data: CategoryRequest): Promise<Category> {
  return apiFetch<Category>(`/api/v1/admin/categories/${id}`, {
    method: 'PUT',
    body: data,
  })
}

/**
 * Deletes an empty category (DELETE /api/v1/admin/categories/{id}). Requires ADMIN.
 * Fails with 409 CATEGORY_IN_USE if equipment is assigned to it.
 */
export function deleteCategory(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/categories/${id}`, {
    method: 'DELETE',
  })
}

// ----------------------------------------------------------------------
// Lab Room API endpoints
// ----------------------------------------------------------------------

/**
 * Retrieves all lab rooms sorted by code (GET /api/v1/lab-rooms).
 */
export function getLabRooms(): Promise<LabRoom[]> {
  return apiFetch<LabRoom[]>('/api/v1/lab-rooms')
}

/**
 * Creates a new lab room (POST /api/v1/admin/lab-rooms). Requires ADMIN.
 * Code is normalized to uppercase on the backend.
 */
export function createLabRoom(data: LabRoomRequest): Promise<LabRoom> {
  return apiFetch<LabRoom>('/api/v1/admin/lab-rooms', {
    method: 'POST',
    body: data,
  })
}

/**
 * Updates an existing lab room (PUT /api/v1/admin/lab-rooms/{id}). Requires ADMIN.
 */
export function updateLabRoom(id: number, data: LabRoomRequest): Promise<LabRoom> {
  return apiFetch<LabRoom>(`/api/v1/admin/lab-rooms/${id}`, {
    method: 'PUT',
    body: data,
  })
}

/**
 * Deletes an empty lab room (DELETE /api/v1/admin/lab-rooms/{id}). Requires ADMIN.
 * Fails with 409 LAB_ROOM_IN_USE if equipment is located in it.
 */
export function deleteLabRoom(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/lab-rooms/${id}`, {
    method: 'DELETE',
  })
}

// ----------------------------------------------------------------------
// Asset API endpoints
// ----------------------------------------------------------------------

/**
 * Searches and filters equipment catalog with pagination (GET /api/v1/assets).
 * Students see AVAILABLE and UNDER_MAINTENANCE assets; admins see all including RETIRED.
 */
export function searchAssets(query: AssetSearch = {}): Promise<PageResponse<Asset>> {
  const params = new URLSearchParams()
  if (query.search) params.set('search', query.search.trim())
  if (query.categoryId !== undefined) params.set('categoryId', String(query.categoryId))
  if (query.labRoomId !== undefined) params.set('labRoomId', String(query.labRoomId))
  if (query.status) params.set('status', query.status)
  if (query.page !== undefined) params.set('page', String(query.page))
  if (query.size !== undefined) params.set('size', String(query.size))
  if (query.sort) params.set('sort', query.sort)

  const queryString = params.toString()
  return apiFetch<PageResponse<Asset>>(`/api/v1/assets${queryString ? `?${queryString}` : ''}`)
}

/**
 * Retrieves full details for a single equipment asset (GET /api/v1/assets/{id}).
 */
export function getAsset(id: number): Promise<Asset> {
  return apiFetch<Asset>(`/api/v1/assets/${id}`)
}

/**
 * Creates a new equipment asset (POST /api/v1/admin/assets). Requires ADMIN.
 */
export function createAsset(data: CreateAssetRequest): Promise<Asset> {
  return apiFetch<Asset>('/api/v1/admin/assets', {
    method: 'POST',
    body: data,
  })
}

/**
 * Updates an asset's details (PUT /api/v1/admin/assets/{id}). Requires ADMIN.
 * Fails with 409 ASSET_RETIRED if the asset is retired.
 */
export function updateAsset(id: number, data: UpdateAssetRequest): Promise<Asset> {
  return apiFetch<Asset>(`/api/v1/admin/assets/${id}`, {
    method: 'PUT',
    body: data,
  })
}

/**
 * Retires an asset permanently (POST /api/v1/admin/assets/{id}/retire). Requires ADMIN.
 * Assets are never hard-deleted; their status transitions to RETIRED.
 */
export function retireAsset(id: number): Promise<Asset> {
  return apiFetch<Asset>(`/api/v1/admin/assets/${id}/retire`, {
    method: 'POST',
  })
}
