import { Badge, Button, Container, Group, Text, Title } from '@mantine/core'
import { useAuth } from '../auth/useAuth.ts'

// Temporary start page for logged-in users. The real layout with a side menu comes in the next PR.
export default function HomePage() {
  const { user, logout } = useAuth()

  return (
    <Container size="sm" py="xl">
      <Title order={1}>Campus Hub</Title>
      <Text c="dimmed" mb="lg">
        Lab equipment booking and incident reporting
      </Text>

      <Group>
        <Text>
          Logged in as <b>{user?.fullName}</b> ({user?.email})
        </Text>
        <Badge>{user?.role}</Badge>
      </Group>

      <Button mt="lg" variant="light" onClick={logout}>
        Log out
      </Button>
    </Container>
  )
}
