import { Anchor, Button, Center, Chip, Group, Loader, Paper, Stack, Table, Text, TextInput } from '@mantine/core'
import { useDebouncedCallback } from '@mantine/hooks'
import { IconSearch } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  loadProjectLang,
  saveProjectLang,
  searchProjects,
  type ProjectLang,
  type ProjectSearchResponse,
} from '../api/projects.ts'
import PageHeader from '../components/PageHeader.tsx'
import { DifficultyBadge, LangSwitch, ProjectErrorAlert } from './ProjectsParts.tsx'

// Projects list + search (spec §8). The search itself runs on the backend (ProjectSearch.java):
// it matches both languages, so "termometre" finds the thermometer whatever the switch says.
export default function ProjectsPage() {
  const navigate = useNavigate()
  const [input, setInput] = useState('') // what is in the box
  const [query, setQuery] = useState('') // what we last searched for (trimmed)
  const [lang, setLang] = useState<ProjectLang>(loadProjectLang)
  const [result, setResult] = useState<ProjectSearchResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    // "ignore" drops the answer of a search the user has already replaced with a newer one,
    // so a slow old answer can't overwrite the new list.
    let ignore = false
    searchProjects(query, lang)
      .then((response) => {
        if (!ignore) setResult(response)
      })
      .catch((err) => {
        if (!ignore) setError(err)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [query, lang])

  // Each change below re-runs the effect and sets loading = true, but only if something really
  // changed: if the effect didn't run, nothing would set loading back to false (the #133/#196 bug).
  function changeQuery(next: string) {
    const trimmed = next.trim()
    if (trimmed === query) return
    setLoading(true)
    setError(null)
    setQuery(trimmed)
  }

  function changeLang(next: ProjectLang) {
    if (next === lang) return
    saveProjectLang(next)
    setLoading(true)
    setError(null)
    setLang(next)
  }

  // Search 300 ms after the last key press; Enter searches at once.
  const searchSoon = useDebouncedCallback(changeQuery, 300)

  function showAll() {
    searchSoon.cancel()
    setInput('')
    changeQuery('')
  }

  const items = result?.items ?? []

  return (
    <Stack gap="lg">
      <PageHeader
        title="Projects"
        description={
          'Describe what you want to build. We list the projects that fit, the equipment they need ' +
          'from our catalog and the steps to follow.'
        }
        actions={<LangSwitch value={lang} onChange={changeLang} />}
      />

      <TextInput
        placeholder="Describe your project… (e.g. termometru, measure a PWM signal)"
        aria-label="Search projects"
        leftSection={<IconSearch size={16} />}
        rightSection={loading ? <Loader size="xs" /> : null}
        value={input}
        onChange={(event) => {
          setInput(event.currentTarget.value)
          searchSoon(event.currentTarget.value)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            searchSoon.cancel()
            changeQuery(input)
          }
        }}
      />

      {error ? <ProjectErrorAlert error={error} /> : null}

      {/* The backend says "ambiguous" when the two best hits score almost the same (AMBIGUITY_GAP). */}
      {result?.ambiguous && items.length >= 2 && (
        <Group gap="xs">
          <Text size="sm" c="dimmed">
            Did you mean:
          </Text>
          {items.slice(0, 2).map((item) => (
            <Chip key={item.slug} checked={false} variant="outline" onChange={() => navigate(`/projects/${item.slug}`)}>
              {item.title}
            </Chip>
          ))}
        </Group>
      )}

      {result === null && loading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : items.length === 0 && !error ? (
        <Paper withBorder p="xl" radius="md">
          <Stack align="center" gap="sm">
            <Text fw={500}>{query ? `No project matches “${query}”.` : 'No projects yet.'}</Text>
            {query && (
              <>
                <Text size="sm" c="dimmed">
                  Try other words, in Romanian or English (e.g. “termometru”, “oscilloscope”).
                </Text>
                <Button variant="light" onClick={showAll}>
                  Show all projects
                </Button>
              </>
            )}
          </Stack>
        </Paper>
      ) : (
        items.length > 0 && (
          <Paper withBorder radius="md" style={{ opacity: loading ? 0.6 : 1 }}>
            <Table highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Project</Table.Th>
                  <Table.Th>Difficulty</Table.Th>
                  {/* Below "sm" (phone) only the title and difficulty fit. */}
                  <Table.Th visibleFrom="sm">Hours</Table.Th>
                  <Table.Th visibleFrom="sm">Equipment</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {items.map((item) => (
                  <Table.Tr key={item.slug}>
                    <Table.Td>
                      <Anchor component={Link} to={`/projects/${item.slug}`} fw={500}>
                        {item.title}
                      </Anchor>
                      <Text size="sm" c="dimmed" lineClamp={2}>
                        {item.summary}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <DifficultyBadge difficulty={item.difficulty} />
                    </Table.Td>
                    <Table.Td visibleFrom="sm" style={{ whiteSpace: 'nowrap' }}>
                      ~{item.estimatedHours} h
                    </Table.Td>
                    <Table.Td visibleFrom="sm" style={{ whiteSpace: 'nowrap' }}>
                      {item.componentCount} {item.componentCount === 1 ? 'item' : 'items'}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Paper>
        )
      )}
    </Stack>
  )
}
