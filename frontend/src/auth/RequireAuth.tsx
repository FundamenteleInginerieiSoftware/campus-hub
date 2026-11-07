import { Alert, Button, Center, Group, Loader, Stack, Text } from '@mantine/core'
import { IconPlugConnectedX } from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from './useAuth.ts'

// Wrap a page in <RequireAuth> to show it only to logged-in users; everyone else goes to /login.
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading, startupError, retry, logout } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    )
  }
  if (startupError) {
    // The saved token could not be checked because the backend did not answer. It is kept, so
    // "Try again" logs the user straight back in once the backend is up (a deploy takes ~30 s).
    return (
      <Center h="100vh" p="md">
        <Stack maw={420}>
          <Alert color="red" icon={<IconPlugConnectedX size={18} />} title="Cannot reach the server">
            <Text size="sm">The server may be restarting. You are still signed in; try again in a few seconds.</Text>
          </Alert>
          <Group justify="flex-end">
            <Button variant="default" onClick={logout}>
              Log out
            </Button>
            <Button onClick={retry}>Try again</Button>
          </Group>
        </Stack>
      </Center>
    )
  }
  if (!user) {
    // Remember where the user wanted to go, so the login page can send them back there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}
