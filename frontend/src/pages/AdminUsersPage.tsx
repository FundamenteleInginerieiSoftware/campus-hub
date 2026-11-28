import { Alert, Badge, Button, Group, Modal, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core'
import { useDebouncedValue } from '@mantine/hooks'
import { IconCheck, IconSearch, IconShieldCheck, IconShieldOff, IconUserCheck, IconUserOff } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { changeUserRole, disableUser, enableUser, searchAdminUsers, type AdminUser } from '../api/adminUsers.ts'
import type { Role } from '../api/auth.ts'
import { formatCampusDateTime } from '../api/incidents.ts'
import type { PageResponse } from '../api/types.ts'
import { useAuth } from '../auth/useAuth.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

const PAGE_SIZE = 20

// One pending change that needs a confirmation: disabling, or a role change.
type Pending = { user: AdminUser; action: 'disable' } | { user: AdminUser; action: 'role'; role: Role }

/**
 * Users (admin, #305): search by email or name, filter by role and status, enable/disable accounts and change
 * roles. The backend refuses changing yourself (CANNOT_MODIFY_SELF) and removing the last enabled admin
 * (LAST_ADMIN_REQUIRED); the buttons for your own row are already disabled, the second rule shows as an error.
 * On AWS this page is the only way to promote an admin or disable an account.
 */
export default function AdminUsersPage() {
  const { user: me } = useAuth()

  const [q, setQ] = useState('')
  const [debouncedQ] = useDebouncedValue(q, 300)
  const [role, setRole] = useState<Role | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const [pageData, setPageData] = useState<PageResponse<AdminUser> | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [error, setError] = useState<unknown>(null)
  const [reloadToken, setReloadToken] = useState(0)
  // Bug fixed (#364): loading was a flag set on every keystroke and cleared when a search came back. Typing a
  // letter and deleting it within 300 ms left the debounced text unchanged, so no search ran and the table
  // spun forever. Now loading is worked out: the table waits while the text is still settling, or while the
  // answer on screen is for other filters than the current ones.
  const requestKey = `${debouncedQ}|${role}|${status}|${pageNumber}|${reloadToken}`
  const [loadedKey, setLoadedKey] = useState<string | null>(null)
  const loading = q !== debouncedQ || loadedKey !== requestKey
  const [notice, setNotice] = useState<string | null>(null)

  const [pending, setPending] = useState<Pending | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  useEffect(() => {
    let ignore = false
    searchAdminUsers({
      q: debouncedQ,
      role,
      enabled: status === null ? null : status === 'ENABLED',
      page: pageNumber,
      size: PAGE_SIZE,
    })
      .then((response) => {
        if (ignore) return
        setError(null)
        setPageData(response)
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoadedKey(requestKey)
      })
    return () => {
      ignore = true
    }
  }, [debouncedQ, role, status, pageNumber, reloadToken, requestKey])

  // A new filter starts again from the first page.
  function changeFilter(apply: () => void) {
    apply()
    setPageNumber(0)
    setNotice(null)
  }

  async function run(user: AdminUser, action: () => Promise<AdminUser>, done: (updated: AdminUser) => string) {
    setBusyId(user.id)
    setError(null)
    setNotice(null)
    try {
      const updated = await action()
      setNotice(done(updated))
      setReloadToken((t) => t + 1)
    } catch (err) {
      setError(err)
    } finally {
      setBusyId(null)
      setPending(null)
    }
  }

  function confirmPending() {
    if (!pending) return
    if (pending.action === 'disable') {
      void run(
        pending.user,
        () => disableUser(pending.user.id),
        (u) => `${u.fullName} is disabled. Their token stops working now and their upcoming bookings are cancelled.`,
      )
    } else {
      const newRole = pending.role
      void run(
        pending.user,
        () => changeUserRole(pending.user.id, newRole),
        (u) => `${u.fullName} is now ${newRole === 'ADMIN' ? 'an admin' : 'a student'}, from their next request.`,
      )
    }
  }

  const columns: Column<AdminUser>[] = [
    {
      header: 'User',
      render: (u) => (
        <div>
          <Text size="sm" fw={500}>
            {u.fullName}
            {u.id === me?.id && (
              <Text span size="xs" c="dimmed">
                {' '}
                (you)
              </Text>
            )}
          </Text>
          <Text size="xs" c="dimmed">
            {u.email}
          </Text>
        </div>
      ),
    },
    {
      header: 'Role',
      render: (u) => (
        <Badge color={u.role === 'ADMIN' ? 'grape' : 'blue'} variant="light">
          {u.role === 'ADMIN' ? 'Admin' : 'Student'}
        </Badge>
      ),
    },
    {
      header: 'Status',
      render: (u) => (
        <Badge color={u.enabled ? 'green' : 'gray'}>{u.enabled ? 'Active' : 'Disabled'}</Badge>
      ),
    },
    { header: 'Joined', render: (u) => formatCampusDateTime(u.createdAt) },
    {
      header: 'Actions',
      render: (u) => {
        const isMe = u.id === me?.id
        const busy = busyId === u.id
        const selfHint = 'You cannot change your own account'
        return (
          <Group gap="xs" wrap="nowrap">
            <Tooltip label={selfHint} disabled={!isMe}>
              <Button
                size="xs"
                variant="light"
                color="grape"
                leftSection={u.role === 'ADMIN' ? <IconShieldOff size={14} /> : <IconShieldCheck size={14} />}
                disabled={isMe || busy}
                onClick={() =>
                  setPending({ user: u, action: 'role', role: u.role === 'ADMIN' ? 'STUDENT' : 'ADMIN' })
                }
              >
                {u.role === 'ADMIN' ? 'Make student' : 'Make admin'}
              </Button>
            </Tooltip>
            {u.enabled ? (
              <Tooltip label={selfHint} disabled={!isMe}>
                <Button
                  size="xs"
                  variant="subtle"
                  color="red"
                  leftSection={<IconUserOff size={14} />}
                  disabled={isMe || busy}
                  onClick={() => setPending({ user: u, action: 'disable' })}
                >
                  Disable
                </Button>
              </Tooltip>
            ) : (
              <Button
                size="xs"
                variant="light"
                color="green"
                leftSection={<IconUserCheck size={14} />}
                loading={busy}
                onClick={() => void run(u, () => enableUser(u.id), (x) => `${x.fullName} can log in again.`)}
              >
                Enable
              </Button>
            )}
          </Group>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        title="Users"
        description="Find an account, enable or disable it, and give or remove the admin role. You cannot change your own account, and at least one enabled admin always remains."
      />

      <Group mb="md" align="flex-end" wrap="wrap">
        <TextInput
          label="Search"
          placeholder="Email or name"
          leftSection={<IconSearch size={16} />}
          value={q}
          onChange={(event) => {
            const value = event.currentTarget.value
            changeFilter(() => setQ(value))
          }}
          maxLength={100}
          w={260}
        />
        <Select
          label="Role"
          placeholder="Any role"
          clearable
          data={[
            { value: 'STUDENT', label: 'Student' },
            { value: 'ADMIN', label: 'Admin' },
          ]}
          value={role}
          onChange={(value) => changeFilter(() => setRole(value as Role | null))}
          w={160}
        />
        <Select
          label="Status"
          placeholder="Any status"
          clearable
          data={[
            { value: 'ENABLED', label: 'Active' },
            { value: 'DISABLED', label: 'Disabled' },
          ]}
          value={status}
          onChange={(value) => changeFilter(() => setStatus(value))}
          w={160}
        />
      </Group>

      {notice && (
        <Alert color="green" icon={<IconCheck size={18} />} withCloseButton onClose={() => setNotice(null)} mb="md">
          {notice}
        </Alert>
      )}
      <ErrorAlert error={error} />

      <PagedTable
        page={pageData}
        columns={columns}
        rowKey={(u) => u.id}
        onPageChange={(next) => {
          if (next === pageNumber) return
          setPageNumber(next)
        }}
        loading={loading}
        emptyText="No users match these filters."
      />

      {pending && (
        <Modal
          opened
          onClose={() => busyId === null && setPending(null)}
          title={
            pending.action === 'disable'
              ? `Disable ${pending.user.fullName}?`
              : `Make ${pending.user.fullName} ${pending.role === 'ADMIN' ? 'an admin' : 'a student'}?`
          }
          centered
        >
          <Stack>
            <Text size="sm">
              {pending.action === 'disable'
                ? 'They can no longer log in, the session they have now stops working at once, and their upcoming bookings are cancelled. Equipment they already picked up stays with them until it is returned. You can enable the account again later.'
                : pending.role === 'ADMIN'
                  ? 'They get full admin rights (inventory, incidents, users) from their next request.'
                  : 'They lose admin rights from their next request.'}
            </Text>
            <Text size="xs" c="dimmed">
              {pending.user.email}
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setPending(null)} disabled={busyId !== null}>
                Cancel
              </Button>
              <Button
                color={pending.action === 'disable' ? 'red' : 'grape'}
                onClick={confirmPending}
                loading={busyId !== null}
                data-autofocus
              >
                {pending.action === 'disable' ? 'Disable' : 'Change role'}
              </Button>
            </Group>
          </Stack>
        </Modal>
      )}
    </>
  )
}
