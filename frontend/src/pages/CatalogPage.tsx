import {
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Pagination,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconCpu,
  IconDoor,
  IconFolder,
  IconSearch,
  IconX,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/useAuth.ts'
import {
  getCategories,
  getLabRooms,
  searchAssets,
  type Asset,
  type AssetStatus,
  type Category,
  type LabRoom,
} from '../api/catalog.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'

function statusBadgeColor(status: AssetStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'teal'
    case 'UNDER_MAINTENANCE':
      return 'yellow'
    case 'RETIRED':
      return 'gray'
    default:
      return 'blue'
  }
}

function statusLabel(status: AssetStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'Available'
    case 'UNDER_MAINTENANCE':
      return 'Under Maintenance'
    case 'RETIRED':
      return 'Retired'
    default:
      return status
  }
}

export default function CatalogPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'

  // Data states
  const [categories, setCategories] = useState<Category[]>([])
  const [labRooms, setLabRooms] = useState<LabRoom[]>([])
  const [pageData, setPageData] = useState<PageResponse<Asset>>({
    content: [],
    page: 0,
    size: 12,
    totalElements: 0,
    totalPages: 0,
  })

  // Filter states
  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState<string | null>(null)
  const [labRoomId, setLabRoomId] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  // Loading & error states
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown | null>(null)

  // Load dropdown options once on mount
  useEffect(() => {
    Promise.all([getCategories(), getLabRooms()])
      .then(([cats, rooms]) => {
        setCategories(cats)
        setLabRooms(rooms)
      })
      .catch((err) => {
        setError(err)
      })
  }, [])

  // Fetch paginated equipment whenever filters or page changes
  useEffect(() => {
    let active = true

    searchAssets({
      search: search.trim() || undefined,
      categoryId: categoryId ? Number(categoryId) : undefined,
      labRoomId: labRoomId ? Number(labRoomId) : undefined,
      status: (status as AssetStatus) || undefined,
      page,
      size: 12,
      sort: 'name,asc',
    })
      .then((data) => {
        if (active) {
          setPageData(data)
          setLoading(false)
        }
      })
      .catch((err) => {
        if (active) {
          setError(err)
          setLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [search, categoryId, labRoomId, status, page])

  const handleResetFilters = () => {
    setLoading(true)
    setSearch('')
    setCategoryId(null)
    setLabRoomId(null)
    setStatus(null)
    setPage(0)
  }

  const categoryOptions = categories.map((c) => ({
    value: String(c.id),
    label: c.name,
  }))

  const labRoomOptions = labRooms.map((r) => ({
    value: String(r.id),
    label: `${r.code} - ${r.name}`,
  }))

  const statusOptions = [
    { value: 'AVAILABLE', label: 'Available' },
    { value: 'UNDER_MAINTENANCE', label: 'Under Maintenance' },
    ...(isAdmin ? [{ value: 'RETIRED', label: 'Retired' }] : []),
  ]

  return (
    <Stack gap="lg">
      <PageHeader
        title="Equipment Catalog"
        description="Browse lab equipment, check real-time availability, and reserve time slots for your practical sessions."
        actions={
          isAdmin ? (
            <Button component={Link} to="/admin/inventory" variant="light" color="blue">
              Manage Inventory
            </Button>
          ) : undefined
        }
      />

      {error ? <ErrorAlert error={error} /> : null}

      {/* Filter and Search Bar */}
      <Paper withBorder p="md" radius="md">
        <SimpleGrid cols={{ base: 1, sm: 2, md: isAdmin ? 5 : 4 }} spacing="sm">
          <TextInput
            placeholder="Search name, model, serial..."
            value={search}
            onChange={(e) => {
              setLoading(true)
              setSearch(e.currentTarget.value)
              setPage(0)
            }}
            leftSection={<IconSearch size={16} />}
          />

          <Select
            placeholder="All Categories"
            data={categoryOptions}
            value={categoryId}
            onChange={(val) => {
              setLoading(true)
              setCategoryId(val)
              setPage(0)
            }}
            clearable
            searchable
          />

          <Select
            placeholder="All Labs"
            data={labRoomOptions}
            value={labRoomId}
            onChange={(val) => {
              setLoading(true)
              setLabRoomId(val)
              setPage(0)
            }}
            clearable
            searchable
          />

          {isAdmin ? (
            <Select
              placeholder="All Statuses"
              data={statusOptions}
              value={status}
              onChange={(val) => {
                setLoading(true)
                setStatus(val)
                setPage(0)
              }}
              clearable
            />
          ) : null}

          <Button
            variant="subtle"
            color="gray"
            onClick={handleResetFilters}
            leftSection={<IconX size={16} />}
            disabled={!search && !categoryId && !labRoomId && !status}
          >
            Clear Filters
          </Button>
        </SimpleGrid>
      </Paper>

      {/* Content Grid */}
      {loading ? (
        <Center py="xl">
          <Loader size="lg" />
        </Center>
      ) : pageData.content.length === 0 ? (
        <Paper withBorder p="xl" ta="center" radius="md">
          <ThemeIcon size={48} radius="xl" color="gray" variant="light" mb="sm">
            <IconSearch size={24} />
          </ThemeIcon>
          <Text fw={600} size="lg">
            No equipment found
          </Text>
          <Text c="dimmed" size="sm" mt={4} mb="md">
            No assets match your search criteria. Try clearing or relaxing the filters.
          </Text>
          <Button variant="light" onClick={handleResetFilters}>
            Reset Filters
          </Button>
        </Paper>
      ) : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
            {pageData.content.map((asset) => {
              const isMaintenance = asset.status === 'UNDER_MAINTENANCE'
              const isRetired = asset.status === 'RETIRED'

              return (
                <Card
                  key={asset.id}
                  withBorder
                  shadow="xs"
                  radius="md"
                  p="md"
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'space-between',
                    opacity: isRetired ? 0.6 : isMaintenance ? 0.85 : 1,
                  }}
                >
                  <Stack gap="xs">
                    <Group justify="space-between" align="center" wrap="nowrap">
                      <Badge variant="light" color="blue" size="sm" leftSection={<IconFolder size={12} />}>
                        {asset.categoryName}
                      </Badge>
                      <Badge color={statusBadgeColor(asset.status)} variant="filled" size="sm">
                        {statusLabel(asset.status)}
                      </Badge>
                    </Group>

                    <div>
                      <Text fw={600} size="md" lineClamp={1} title={asset.name}>
                        {asset.name}
                      </Text>
                      <Group gap={6} mt={2}>
                        <ThemeIcon size="xs" color="gray" variant="transparent">
                          <IconCpu size={12} />
                        </ThemeIcon>
                        <Text size="xs" c="dimmed">
                          Model: {asset.model}
                        </Text>
                      </Group>
                    </div>

                    <Group gap={6}>
                      <ThemeIcon size="xs" color="gray" variant="transparent">
                        <IconDoor size={12} />
                      </ThemeIcon>
                      <Text size="xs" c="dimmed">
                        {asset.labRoomCode} ({asset.labRoomName})
                      </Text>
                    </Group>

                    {asset.description ? (
                      <Text size="xs" c="dimmed" lineClamp={2} mt={4}>
                        {asset.description}
                      </Text>
                    ) : null}
                  </Stack>

                  <Group mt="md" justify="space-between" align="center">
                    <Text size="xs" c="dimmed">
                      S/N: {asset.serialNumber}
                    </Text>

                    {isMaintenance ? (
                      <Tooltip label="Currently undergoing repair, but details are viewable">
                        <Button
                          component={Link}
                          to={`/catalog/${asset.id}`}
                          variant="light"
                          color="yellow"
                          size="xs"
                          leftSection={<IconAlertTriangle size={14} />}
                        >
                          Under Repair
                        </Button>
                      </Tooltip>
                    ) : isRetired ? (
                      <Button component={Link} to={`/catalog/${asset.id}`} variant="subtle" color="gray" size="xs">
                        Retired
                      </Button>
                    ) : (
                      <Button component={Link} to={`/catalog/${asset.id}`} variant="light" color="teal" size="xs">
                        View & Book
                      </Button>
                    )}
                  </Group>
                </Card>
              )
            })}
          </SimpleGrid>

          {pageData.totalPages > 1 ? (
            <Group justify="space-between" align="center" mt="md">
              <Text size="sm" c="dimmed">
                Showing {pageData.content.length} of {pageData.totalElements} assets
              </Text>
              <Pagination
                total={pageData.totalPages}
                value={pageData.page + 1}
                onChange={(val) => {
                  setLoading(true)
                  setPage(val - 1)
                }}
              />
            </Group>
          ) : null}
        </>
      )}
    </Stack>
  )
}
