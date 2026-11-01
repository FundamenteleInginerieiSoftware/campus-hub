import {
  Alert,
  Box,
  Button,
  Group,
  PasswordInput,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Title,
} from '@mantine/core'
import { IconFlask } from '@tabler/icons-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { errorMessage } from '../api/errorMessages.ts'
import { useAuth } from '../auth/useAuth.ts'

// Dev seed accounts (api-contract §3). The quick-fill buttons only exist in `npm run dev`, never in the build.
const DEMO_ACCOUNTS = ['student1@campus.test', 'student2@campus.test', 'admin@campus.test']

export default function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // The page the user tried to open before being sent here (set by RequireAuth), or the start page.
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (user) {
    return <Navigate to={from} replace />
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(email, password)
      navigate(from, { replace: true })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  // Split screen as in the prototype: the form on the left, the blue panel on the right (hidden on phones).
  return (
    <SimpleGrid cols={{ base: 1, md: 2 }} spacing={0} mih="100vh">
      <Stack justify="center" p={40} maw={440} w="100%" mx="auto">
        <Group gap="xs" mb="md">
          <ThemeIcon size={28} radius="md">
            <IconFlask size={16} />
          </ThemeIcon>
          <div>
            <Text fw={700} lh={1.2}>
              Campus Hub
            </Text>
            <Text size="xs" c="dimmed">
              Lab equipment desk
            </Text>
          </div>
        </Group>

        <Title order={2}>Sign in</Title>
        <Text c="dimmed" mb="sm">
          Book lab equipment by the half hour and report anything broken.
        </Text>

        <form onSubmit={handleSubmit}>
          <Stack>
            {error && <Alert color="red">{error}</Alert>}
            <TextInput
              label="University email"
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.currentTarget.value)}
            />
            <PasswordInput
              label="Password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.currentTarget.value)}
            />
            <Button type="submit" loading={submitting} fullWidth>
              Sign in
            </Button>
          </Stack>
        </form>

        {import.meta.env.DEV && (
          <Stack gap={4} mt="md">
            <Text size="xs" c="dimmed">
              Dev accounts, password <code>password</code>
            </Text>
            {DEMO_ACCOUNTS.map((account) => (
              <Button
                key={account}
                variant="default"
                size="xs"
                justify="space-between"
                onClick={() => {
                  setEmail(account)
                  setPassword('password')
                }}
              >
                {account}
              </Button>
            ))}
          </Stack>
        )}
      </Stack>

      <Box visibleFrom="md" bg="campus.6" c="white" p={48} style={{ display: 'grid', alignContent: 'end' }}>
        <Title order={2} c="white" mb="sm">
          Book the bench. Report what breaks.
        </Title>
        <Text maw={520} opacity={0.9}>
          Oscilloscopes, kits, headsets and cameras from three labs, in 30-minute slots up to 14 days ahead. If an item
          is reported broken, every upcoming booking moves to a matching item on its own.
        </Text>
      </Box>
    </SimpleGrid>
  )
}
