import { Anchor, Badge, Button, Group, Paper, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core'
import { useDebouncedValue } from '@mantine/hooks'
import { IconFilterOff, IconRefresh } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { searchAuditLogs, type AuditLogEntry } from '../api/audit.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

const PAGE_SIZE = 20

const ENTITY_TYPE_OPTIONS = [
  { value: 'ASSET', label: 'Asset (ASSET)' },
  { value: 'CATEGORY', label: 'Category (CATEGORY)' },
  { value: 'LAB_ROOM', label: 'Lab Room (LAB_ROOM)' },
  { value: 'Reservation', label: 'Reservation' },
  { value: 'INCIDENT', label: 'Incident (INCIDENT)' },
  { value: 'USER', label: 'User (USER)' },
]

const ACTION_OPTIONS = [
  {
    group: 'Catalog & Inventory',
    items: [
      'ASSET_CREATED',
      'ASSET_UPDATED',
      'ASSET_RETIRED',
      'CATEGORY_CREATED',
      'CATEGORY_UPDATED',
      'CATEGORY_DELETED',
      'LAB_ROOM_CREATED',
      'LAB_ROOM_UPDATED',
      'LAB_ROOM_DELETED',
    ],
  },
  {
    group: 'Reservations & Reassignment',
    items: [
      'CREATE_RESERVATION',
      'CANCEL_RESERVATION',
      'CHECK_OUT_RESERVATION',
      'RETURN_RESERVATION',
      'REASSIGN_RESERVATION',
      'FORCE_CANCEL_RESERVATION',
      'AUTO_NO_SHOW',
      'MARK_OVERDUE',
    ],
  },
  {
    group: 'Incidents',
    items: [
      'INCIDENT_REPORTED',
      'INCIDENT_REPAIR_STARTED',
      'INCIDENT_RESOLVED',
      'INCIDENT_REJECTED',
      'INCIDENT_PHOTO_ADDED',
      'INCIDENT_PHOTO_REPLACED',
    ],
  },
  {
    group: 'Users',
    items: ['USER_DISABLED', 'USER_ENABLED', 'USER_ROLE_CHANGED'],
  },
]

// Backend sends local campus times without offset ("2026-11-08T14:05:19").
// Cut the string directly so the browser's local timezone doesn't shift the hour.
function formatTimestamp(iso: string): string {
  const [datePart, timePart] = iso.split('T')
  if (!datePart) return iso
  const [year, month, day] = datePart.split('-')
  const time = timePart ? timePart.slice(0, 8) : ''
  return `${day}.${month}.${year} ${time}`.trim()
}

function actionBadgeColor(action: string): string {
  if (
    action.endsWith('_CREATED') ||
    action.startsWith('CREATE_') ||
    action.endsWith('_RESOLVED') ||
    action.endsWith('_ENABLED') ||
    action.startsWith('RETURN_')
  ) {
    return 'green'
  }
  if (
    action.endsWith('_RETIRED') ||
    action.endsWith('_DELETED') ||
    action.includes('CANCEL') ||
    action.endsWith('_OVERDUE') ||
    action.endsWith('_NO_SHOW') ||
    action.endsWith('_DISABLED') ||
    action.endsWith('_REJECTED')
  ) {
    return 'red'
  }
  if (
    action.endsWith('_UPDATED') ||
    action.startsWith('REASSIGN_') ||
    action.endsWith('_STARTED') ||
    action.endsWith('_CHANGED')
  ) {
    return 'orange'
  }
  return 'campus'
}

function parsePositiveId(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const num = Number(trimmed)
  return Number.isInteger(num) && num > 0 ? num : undefined
}

export default function AdminAuditLogPage() {
  const [entityType, setEntityType] = useState<string | null>(null)
  const [action, setAction] = useState<string | null>(null)
  const [actorIdInput, setActorIdInput] = useState('')
  const [entityIdInput, setEntityIdInput] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  // Debounce text inputs so typing an ID doesn't fire a query on every keystroke.
  const [debouncedActorId] = useDebouncedValue(actorIdInput, 300)
  const [debouncedEntityId] = useDebouncedValue(entityIdInput, 300)

  const [pageNumber, setPageNumber] = useState(0)
  const [pageData, setPageData] = useState<PageResponse<AuditLogEntry> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const hasActiveFilters =
    Boolean(entityType) ||
    Boolean(action) ||
    actorIdInput.trim() !== '' ||
    entityIdInput.trim() !== '' ||
    fromDate !== '' ||
    toDate !== ''

  useEffect(() => {
    let ignore = false

    const actorId = parsePositiveId(debouncedActorId)
    const entityId = parsePositiveId(debouncedEntityId)
    // Convert YYYY-MM-DD date inputs to local ISO-8601 bounds without timezone offset.
    const fromIso = fromDate ? `${fromDate}T00:00:00` : undefined
    const toIso = toDate ? `${toDate}T23:59:59` : undefined

    searchAuditLogs({
      actorId,
      action: action ?? undefined,
      entityType: entityType ?? undefined,
      entityId,
      from: fromIso,
      to: toIso,
      page: pageNumber,
      size: PAGE_SIZE,
      sort: 'createdAt,desc',
    })
      .then((response) => {
        if (ignore) return
        setPageData(response)
        setError(null)
      })
      .catch((err: unknown) => {
        if (ignore) return
        setError(err)
      })
      .finally(() => {
        if (!ignore) {
          setLoading(false)
        }
      })

    return () => {
      ignore = true
    }
  }, [entityType, action, debouncedActorId, debouncedEntityId, fromDate, toDate, pageNumber, reloadToken])

  function handleResetFilters() {
    if (!hasActiveFilters && pageNumber === 0) return
    setLoading(true)
    setEntityType(null)
    setAction(null)
    setActorIdInput('')
    setEntityIdInput('')
    setFromDate('')
    setToDate('')
    setPageNumber(0)
  }

  function handleRefresh() {
    setLoading(true)
    setReloadToken((value) => value + 1)
  }

  const columns: Column<AuditLogEntry>[] = [
    {
      header: 'Time',
      render: (row) => (
        <Text size="sm" ff="monospace">
          {formatTimestamp(row.createdAt)}
        </Text>
      ),
    },
    {
      header: 'Action',
      render: (row) => (
        <Badge variant="light" color={actionBadgeColor(row.action)}>
          {row.action}
        </Badge>
      ),
    },
    {
      header: 'Entity',
      render: (row) => {
        const label = row.entityId !== null ? `${row.entityType} #${row.entityId}` : row.entityType
        if (row.entityType === 'ASSET' && row.entityId !== null) {
          return (
            <Anchor component={Link} to={`/catalog/${row.entityId}`} size="sm" fw={500}>
              {label}
            </Anchor>
          )
        }
        return (
          <Text size="sm" fw={500}>
            {label}
          </Text>
        )
      },
    },
    {
      header: 'Actor',
      render: (row) =>
        row.actorId !== null ? (
          <Text size="sm">User #{row.actorId}</Text>
        ) : (
          <Badge variant="outline" color="gray" size="sm">
            System job
          </Badge>
        ),
    },
    {
      header: 'Details',
      render: (row) => (
        <Text size="sm" c={row.details ? undefined : 'dimmed'}>
          {row.details ?? '—'}
        </Text>
      ),
    },
  ]

  return (
    <Stack gap="md">
      <PageHeader
        title="Audit log"
        description="System-wide trail of inventory changes, reservations, incident triage and user administration."
        actions={
          <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={handleRefresh} loading={loading}>
            Refresh
          </Button>
        }
      />

      <ErrorAlert error={error} />

      <Paper withBorder p="md" bg="var(--app-surface)">
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="sm">
            <Select
              label="Entity type"
              placeholder="All entity types"
              data={ENTITY_TYPE_OPTIONS}
              value={entityType}
              onChange={(val) => {
                if (val === entityType) return
                setLoading(true)
                setEntityType(val)
                setPageNumber(0)
              }}
              clearable
              searchable
            />

            <Select
              label="Action"
              placeholder="All actions"
              data={ACTION_OPTIONS}
              value={action}
              onChange={(val) => {
                if (val === action) return
                setLoading(true)
                setAction(val)
                setPageNumber(0)
              }}
              clearable
              searchable
            />

            <Group grow gap="xs">
              <TextInput
                label="Actor ID"
                placeholder="e.g. 3"
                value={actorIdInput}
                onChange={(event) => {
                  setActorIdInput(event.currentTarget.value)
                  setPageNumber(0)
                }}
              />
              <TextInput
                label="Entity ID"
                placeholder="e.g. 1"
                value={entityIdInput}
                onChange={(event) => {
                  setEntityIdInput(event.currentTarget.value)
                  setPageNumber(0)
                }}
              />
            </Group>

            <TextInput
              label="From date"
              type="date"
              value={fromDate}
              onChange={(event) => {
                const next = event.currentTarget.value
                if (next === fromDate) return
                setLoading(true)
                setFromDate(next)
                setPageNumber(0)
              }}
            />

            <TextInput
              label="To date"
              type="date"
              value={toDate}
              onChange={(event) => {
                const next = event.currentTarget.value
                if (next === toDate) return
                setLoading(true)
                setToDate(next)
                setPageNumber(0)
              }}
            />

            <Group align="flex-end">
              <Button
                variant="subtle"
                color="gray"
                leftSection={<IconFilterOff size={16} />}
                onClick={handleResetFilters}
                disabled={!hasActiveFilters && pageNumber === 0}
              >
                Reset filters
              </Button>
            </Group>
          </SimpleGrid>
        </Stack>
      </Paper>

      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(row) => row.id}
        loading={loading}
        emptyText="No audit log entries match the selected filters."
        onPageChange={(nextPage) => {
          // Mantine Pagination also fires onChange when clicking the current page.
          if (nextPage === pageNumber) return
          setLoading(true)
          setPageNumber(nextPage)
        }}
      />
    </Stack>
  )
}
