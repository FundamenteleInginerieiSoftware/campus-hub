import { Group, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'

type Props = {
  title: string
  description?: string
  // Buttons on the right, e.g. "Add item"
  actions?: ReactNode
}

// The title block at the top of every page, as in the prototype. Use it so all pages line up.
export default function PageHeader({ title, description, actions }: Props) {
  return (
    <Group justify="space-between" align="flex-end" mb="lg" wrap="wrap">
      <div>
        <Title order={2}>{title}</Title>
        {description && (
          <Text c="dimmed" mt={4} maw="60ch">
            {description}
          </Text>
        )}
      </div>
      {actions}
    </Group>
  )
}
