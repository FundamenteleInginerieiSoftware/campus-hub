import { Alert, Badge, SegmentedControl } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import { projectErrorMessage, type Difficulty, type ProjectLang } from '../api/projects.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'

// Small pieces both Projects pages use.

const DIFFICULTY: Record<Difficulty, { label: string; color: string }> = {
  BEGINNER: { label: 'Beginner', color: 'teal' },
  INTERMEDIATE: { label: 'Intermediate', color: 'yellow' },
  ADVANCED: { label: 'Advanced', color: 'red' },
}

export function DifficultyBadge({ difficulty }: { difficulty: Difficulty }) {
  const { label, color } = DIFFICULTY[difficulty]
  return (
    // Mantine's badge label hides its overflow, so a table column could shrink it to "INTERMEDI...".
    // A visible overflow makes the browser size the column to the whole word.
    <Badge color={color} variant="light" styles={{ label: { overflow: 'visible' } }}>
      {label}
    </Badge>
  )
}

export function LangSwitch({ value, onChange }: { value: ProjectLang; onChange: (lang: ProjectLang) => void }) {
  return (
    <SegmentedControl
      aria-label="Project language"
      value={value}
      onChange={(next) => onChange(next as ProjectLang)}
      data={[
        { label: 'RO', value: 'ro' },
        { label: 'EN', value: 'en' },
      ]}
    />
  )
}

// Our PROJECT_* codes get the text from projects.ts; any other error goes to Stefania's shared ErrorAlert.
export function ProjectErrorAlert({ error }: { error: unknown }) {
  const text = projectErrorMessage(error)
  if (!text) {
    return <ErrorAlert error={error} />
  }
  return (
    <Alert color="red" icon={<IconAlertTriangle size={18} />} mb="md">
      {text}
    </Alert>
  )
}
