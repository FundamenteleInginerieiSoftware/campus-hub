import { Alert, Container, Loader, Text, Title } from '@mantine/core'
import { useEffect, useState } from 'react'

type BackendState = 'checking' | 'up' | 'down'

// Temporary start page: proves that the frontend reaches the backend through the Vite proxy.
export default function HomePage() {
  const [backend, setBackend] = useState<BackendState>('checking')

  useEffect(() => {
    fetch('/api/v1/auth/ping')
      .then((response) => setBackend(response.ok ? 'up' : 'down'))
      .catch(() => setBackend('down'))
  }, [])

  return (
    <Container size="sm" py="xl">
      <Title order={1}>Campus Hub</Title>
      <Text c="dimmed" mb="lg">
        Lab equipment booking and incident reporting
      </Text>

      {backend === 'checking' && <Loader size="sm" />}
      {backend === 'up' && (
        <Alert color="green" title="Backend connected">
          The API answered on /api/v1/auth/ping.
        </Alert>
      )}
      {backend === 'down' && (
        <Alert color="red" title="Backend not reachable">
          Start it with gradlew bootRun in the backend folder, then reload this page.
        </Alert>
      )}
    </Container>
  )
}
