import {
  Anchor,
  Badge,
  Button,
  Center,
  Grid,
  Group,
  List,
  Loader,
  Paper,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core'
import { IconArrowLeft, IconCheck, IconX } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  fetchProject,
  loadProjectLang,
  saveProjectLang,
  type ComponentView,
  type ProjectDetails,
  type ProjectLang,
  type Slot,
} from '../api/projects.ts'
import { nowInBucharest } from '../api/reservations.ts'
import PageHeader from '../components/PageHeader.tsx'
import { DifficultyBadge, LangSwitch, ProjectErrorAlert } from './ProjectsParts.tsx'

// ---- Slot choices that follow Calin's booking rules (ReservationService) --------------------------
// Mon-Fri, 08:00-20:00, :00/:30 marks, 30 min to 4 h, start in the future and at most 14 days ahead.
// GET /projects/{slug} doesn't check these rules (spec §7), so the selects only offer valid slots:
// that way the availability a student sees is for a slot they can really book.

const OPEN_MINUTES = 8 * 60
const CLOSE_MINUTES = 20 * 60
const STEP = 30
const MAX_LENGTH = 4 * 60
const MAX_DAYS_AHEAD = 14

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function dayKey(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function hhmm(minutes: number) {
  return `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`
}

function minutesOf(time: string) {
  const [h, m] = time.split(':').map(Number)
  return h * 60 + m
}

// "now" is the campus wall clock as a browser-local Date (Calin's nowInBucharest), so comparing it
// with new Date(y, m, d, h, min) compares campus times, whatever the browser's time zone is.
function startTimesFor(day: string, now: Date): string[] {
  const [y, m, d] = day.split('-').map(Number)
  const latest = new Date(now.getTime() + MAX_DAYS_AHEAD * 24 * 60 * 60 * 1000)
  const times: string[] = []
  for (let t = OPEN_MINUTES; t + STEP <= CLOSE_MINUTES; t += STEP) {
    const start = new Date(y, m - 1, d, Math.floor(t / 60), t % 60)
    if (start > now && start <= latest) times.push(hhmm(t))
  }
  return times
}

function endTimesFor(start: string): string[] {
  const times: string[] = []
  const from = minutesOf(start)
  for (let t = from + STEP; t <= Math.min(from + MAX_LENGTH, CLOSE_MINUTES); t += STEP) times.push(hhmm(t))
  return times
}

// Weekdays from today to 14 days ahead that still have at least one start time.
function bookableDays(now: Date): string[] {
  const days: string[] = []
  for (let i = 0; i <= MAX_DAYS_AHEAD; i++) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i)
    const weekday = d.getDay()
    if (weekday !== 0 && weekday !== 6 && startTimesFor(dayKey(d), now).length > 0) days.push(dayKey(d))
  }
  return days
}

function dayLabel(day: string) {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
}

// One hour by default, or the longest that fits before closing time.
function defaultEnd(start: string) {
  const ends = endTimesFor(start)
  return ends[Math.min(1, ends.length - 1)]
}

// ---------------------------------------------------------------------------------------------------

function ComponentsTable({ components, checkedSlot }: { components: ComponentView[]; checkedSlot: Slot | null }) {
  return (
    <>
      {/* Without a slot the backend counts units that are not broken or retired; with one, units free then. */}
      <Text size="sm" c="dimmed">
        {checkedSlot
          ? `"Free": units free on ${dayLabel(checkedSlot.start.slice(0, 10))}, ` +
            `${checkedSlot.start.slice(11, 16)}–${checkedSlot.end.slice(11, 16)}.`
          : '"In service": units not under repair right now. Pick a slot to see what is free then.'}
      </Text>
      <Table verticalSpacing="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Equipment</Table.Th>
            <Table.Th>Needed</Table.Th>
            <Table.Th>{checkedSlot ? 'Free' : 'In service'}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {components.map((c) => {
            const short = c.available < c.quantity
            return (
              <Table.Tr key={c.model}>
                <Table.Td>
                  <Text fw={500}>{c.model}</Text>
                  <Text size="sm" c="dimmed">
                    {c.why}
                  </Text>
                </Table.Td>
                <Table.Td>{c.quantity}</Table.Td>
                <Table.Td>
                  <Text c={short ? 'red' : 'teal'} fw={600}>
                    {c.available}
                  </Text>
                </Table.Td>
              </Table.Tr>
            )
          })}
        </Table.Tbody>
      </Table>
    </>
  )
}

export default function ProjectDetailsPage() {
  const { slug = '' } = useParams()
  const [lang, setLang] = useState<ProjectLang>(loadProjectLang)
  const [project, setProject] = useState<ProjectDetails | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  // ---- Slot state (PR 5's "Reserve all" reads selectedSlot, checkedSlot and kitReservable) ----
  // The campus clock when the page opened; the backend checks the real time again when booking.
  const [now] = useState(nowInBucharest)
  const days = useMemo(() => bookableDays(now), [now])
  const [day, setDay] = useState<string | null>(() => days[0] ?? null)
  const [startTime, setStartTime] = useState<string | null>(() => (days[0] ? startTimesFor(days[0], now)[0] : null))
  const [endTime, setEndTime] = useState<string | null>(() => (startTime ? defaultEnd(startTime) : null))
  // The slot to send with the next load (null = no slot). Setting it, even to an equal slot, reloads.
  const [slotToCheck, setSlotToCheck] = useState<Slot | null>(null)
  // The slot the availability on screen was counted for (null = none, "In service" numbers).
  const [checkedSlot, setCheckedSlot] = useState<Slot | null>(null)

  const selectedSlot: Slot | null =
    day && startTime && endTime ? { start: `${day}T${startTime}:00`, end: `${day}T${endTime}:00` } : null
  // The numbers on screen are for the slot in the selects (the student may have changed them since).
  const slotIsCurrent =
    selectedSlot !== null &&
    checkedSlot !== null &&
    selectedSlot.start === checkedSlot.start &&
    selectedSlot.end === checkedSlot.end
  const kitReservable = slotIsCurrent && project !== null && project.slotChecked && project.reservable

  useEffect(() => {
    let ignore = false
    fetchProject(slug, lang, slotToCheck ?? undefined)
      .then((response) => {
        if (ignore) return
        setProject(response)
        setCheckedSlot(response.slotChecked ? slotToCheck : null)
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
  }, [slug, lang, slotToCheck])

  // Like on the list page: only start loading when the effect will really run again.
  function changeLang(next: ProjectLang) {
    if (next === lang) return
    saveProjectLang(next)
    setLoading(true)
    setError(null)
    setLang(next)
  }

  function checkAvailability() {
    if (!selectedSlot) return
    setLoading(true)
    setError(null)
    // A new object every time, so a second click on the same slot loads fresh numbers.
    setSlotToCheck({ ...selectedSlot })
  }

  function changeDay(next: string | null) {
    if (!next) return
    setDay(next)
    const start = startTimesFor(next, now)[0] ?? null
    setStartTime(start)
    setEndTime(start ? defaultEnd(start) : null)
  }

  function changeStart(next: string | null) {
    if (!next) return
    setStartTime(next)
    // Keep the end the student picked if it still fits (after the new start, at most 4 h).
    if (!endTime || !endTimesFor(next).includes(endTime)) setEndTime(defaultEnd(next))
  }

  if (!project) {
    return (
      <Stack gap="lg">
        <Anchor component={Link} to="/projects" size="sm">
          <Group gap={4}>
            <IconArrowLeft size={14} /> All projects
          </Group>
        </Anchor>
        {error ? (
          <ProjectErrorAlert error={error} />
        ) : (
          <Center py="xl">
            <Loader />
          </Center>
        )}
      </Stack>
    )
  }

  return (
    <Stack gap="lg">
      <Anchor component={Link} to="/projects" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} /> All projects
        </Group>
      </Anchor>

      <PageHeader
        title={project.title}
        description={project.summary}
        actions={
          <Group gap="xs">
            <DifficultyBadge difficulty={project.difficulty} />
            <Badge variant="light" color="gray" tt="none">
              ~{project.estimatedHours} h
            </Badge>
            <LangSwitch value={lang} onChange={changeLang} />
          </Group>
        }
      />

      {error ? <ProjectErrorAlert error={error} /> : null}

      {/* Equipment and the slot card side by side; on a phone the slot card comes right after the
          equipment (not after the steps), so the student sees the numbers change when checking. */}
      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Paper withBorder p="md" radius="md" style={{ opacity: loading ? 0.6 : 1 }}>
            <Title order={4} mb="sm">
              Equipment from the lab
            </Title>
            <ComponentsTable components={project.components} checkedSlot={checkedSlot} />
          </Paper>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 4 }}>
          <Paper withBorder p="md" radius="md">
            <Title order={4} mb="xs">
              Book the kit
            </Title>
            <Text size="sm" c="dimmed" mb="md">
              Pick a slot and check that every item is free then. Mon–Fri, 08:00–20:00, up to 4 h, at most 14 days
              ahead.
            </Text>
            {days.length === 0 ? (
              <Text size="sm">No bookable slot in the next 14 days.</Text>
            ) : (
              <Stack gap="sm">
                <Select
                  label="Day"
                  data={days.map((d) => ({ value: d, label: dayLabel(d) }))}
                  value={day}
                  onChange={changeDay}
                  allowDeselect={false}
                />
                <Group grow>
                  <Select
                    label="From"
                    data={day ? startTimesFor(day, now) : []}
                    value={startTime}
                    onChange={changeStart}
                    allowDeselect={false}
                  />
                  <Select
                    label="To"
                    data={startTime ? endTimesFor(startTime) : []}
                    value={endTime}
                    onChange={(next) => next && setEndTime(next)}
                    allowDeselect={false}
                  />
                </Group>
                <Button
                  variant="light"
                  onClick={checkAvailability}
                  loading={loading && slotToCheck !== null}
                  disabled={!selectedSlot}
                >
                  Check availability
                </Button>
                {/* Reserve all (PR 5) goes here: enabled only when kitReservable, books selectedSlot. */}

                {checkedSlot !== null &&
                  (slotIsCurrent ? (
                    <Group gap={6} wrap="nowrap">
                      {kitReservable ? (
                        <IconCheck size={18} color="var(--mantine-color-teal-6)" />
                      ) : (
                        <IconX size={18} color="var(--mantine-color-red-6)" />
                      )}
                      <Text size="sm" c={kitReservable ? 'teal' : 'red'}>
                        {kitReservable
                          ? 'Every item is free in this slot.'
                          : 'Some items are not free in this slot (in red).'}
                      </Text>
                    </Group>
                  ) : (
                    <Text size="sm" c="dimmed">
                      You changed the slot: check availability again.
                    </Text>
                  ))}
              </Stack>
            )}
          </Paper>
        </Grid.Col>
      </Grid>

      <Stack gap="lg" maw={900}>
        {project.bringYourself.length > 0 && (
          <Paper withBorder p="md" radius="md">
            <Title order={4} mb="sm">
              Bring it yourself
            </Title>
            <List spacing="xs">
              {project.bringYourself.map((item) => (
                <List.Item key={item}>{item}</List.Item>
              ))}
            </List>
          </Paper>
        )}

        <Paper withBorder p="md" radius="md">
          <Title order={4} mb="sm">
            Steps
          </Title>
          <List type="ordered" spacing="md">
            {project.steps.map((step) => (
              <List.Item key={step.title}>
                <Text fw={500}>{step.title}</Text>
                <Text size="sm" c="dimmed">
                  {step.text}
                </Text>
              </List.Item>
            ))}
          </List>
        </Paper>

        {project.mistakes.length > 0 && (
          <Paper withBorder p="md" radius="md">
            <Title order={4} mb="sm">
              Common mistakes
            </Title>
            <List spacing="xs">
              {project.mistakes.map((mistake) => (
                <List.Item key={mistake}>{mistake}</List.Item>
              ))}
            </List>
          </Paper>
        )}
      </Stack>
    </Stack>
  )
}
