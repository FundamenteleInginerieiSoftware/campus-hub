import { Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { register } from '../api/auth.ts'
import { useAuth } from '../auth/useAuth.ts'
import AuthLayout from '../components/AuthLayout.tsx'
import ErrorAlert from '../components/ErrorAlert.tsx'

// Same limits as the backend (api-contract §3): BCrypt reads at most 72 bytes, and letters like "ș"
// take 2 bytes, so the byte count is checked too. The backend checks again; this only saves a round trip.
function passwordProblem(password: string): string | null {
  if (password.length < 8) {
    return 'At least 8 characters.'
  }
  if (new TextEncoder().encode(password).length > 72) {
    return 'Too long: at most 72 bytes (letters like "ș" count as 2).'
  }
  return null
}

export default function RegisterPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  if (user) {
    return <Navigate to="/" replace />
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const problem = passwordProblem(password)
    setPasswordError(problem)
    if (problem) {
      return
    }

    setSubmitting(true)
    try {
      // The backend always creates a STUDENT; admins are only added by an admin.
      await register(email, password, fullName)
      // Log straight in with the same credentials, so the new student lands on the start page.
      await login(email, password)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Use your university email. New accounts are students; admins are added by an admin."
    >
      <form onSubmit={handleSubmit}>
        <Stack>
          <ErrorAlert error={error} />
          <TextInput
            label="Full name"
            autoComplete="name"
            placeholder="Maria Ionescu"
            required
            maxLength={100}
            value={fullName}
            onChange={(event) => setFullName(event.currentTarget.value)}
          />
          <TextInput
            label="University email"
            type="email"
            autoComplete="username"
            required
            maxLength={255}
            value={email}
            onChange={(event) => setEmail(event.currentTarget.value)}
          />
          <PasswordInput
            label="Password"
            description="8 to 72 characters"
            autoComplete="new-password"
            required
            value={password}
            error={passwordError}
            onChange={(event) => {
              setPassword(event.currentTarget.value)
              setPasswordError(null)
            }}
          />
          <Button type="submit" loading={submitting} fullWidth>
            Create account
          </Button>
        </Stack>
      </form>

      <Text size="sm" ta="center" mt="xs">
        Already have an account?{' '}
        <Anchor component={Link} to="/login">
          Sign in
        </Anchor>
      </Text>
    </AuthLayout>
  )
}
