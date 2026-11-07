import { Alert, Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { errorMessage } from '../api/errorMessages.ts'
import { useAuth } from '../auth/useAuth.ts'
import AuthLayout from '../components/AuthLayout.tsx'

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

  return (
    <AuthLayout title="Sign in" subtitle="Book lab equipment by the half hour and report anything broken.">
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

      <Text size="sm" ta="center" mt="xs">
        New here?{' '}
        <Anchor component={Link} to="/register">
          Create an account
        </Anchor>
      </Text>

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
    </AuthLayout>
  )
}
