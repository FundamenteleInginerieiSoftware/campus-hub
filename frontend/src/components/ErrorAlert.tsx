import { Alert, List } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import { ApiError } from '../api/client.ts'
import { errorMessage } from '../api/errorMessages.ts'

// Shows any error from apiFetch the same way everywhere: the friendly message for its code,
// plus the field list for VALIDATION_FAILED. Renders nothing when there is no error.
export default function ErrorAlert({ error }: { error: unknown }) {
  if (!error) {
    return null
  }
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : []

  return (
    <Alert color="red" icon={<IconAlertTriangle size={18} />} mb="md">
      {errorMessage(error)}
      {fieldErrors.length > 0 && (
        <List size="sm" mt="xs">
          {/* One entry per broken rule, so a field can appear twice: the index keeps the keys unique. */}
          {fieldErrors.map((fieldError, index) => (
            <List.Item key={`${fieldError.field}-${index}`}>
              {fieldError.field}: {fieldError.message}
            </List.Item>
          ))}
        </List>
      )}
    </Alert>
  )
}
