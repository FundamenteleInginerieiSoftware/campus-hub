import { Paper, Text } from '@mantine/core'
import PageHeader from '../components/PageHeader.tsx'

type Props = {
  title: string
  description: string
  owner: string
}

// Stand-in until the owner builds the real page. Replace it in App.tsx with your page component.
export default function PlaceholderPage({ title, description, owner }: Props) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <Paper withBorder p="xl" bg="var(--app-surface)">
        <Text c="dimmed">
          This page is not built yet. Owner: <b>{owner}</b>. See docs/modules/frontend.md and the redesigned
          prototype.
        </Text>
      </Paper>
    </>
  )
}
