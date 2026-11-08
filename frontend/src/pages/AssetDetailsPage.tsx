import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Divider,
  Group,
  Loader,
  Modal,
  Paper,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import {
  IconAlertCircle,
  IconAlertTriangle,
  IconArrowLeft,
  IconCpu,
  IconDoor,
  IconFolder,
  IconHash,
  IconTrash,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useAuth } from '../auth/useAuth.ts'
import { getAsset, retireAsset, type Asset, type AssetStatus } from '../api/catalog.ts'
import CalendarBookingView from '../components/CalendarBookingView.tsx'
import ErrorAlert from '../components/ErrorAlert.tsx'

function statusBadgeColor(status: AssetStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'teal'
    case 'UNDER_MAINTENANCE':
      return 'yellow'
    case 'RETIRED':
      return 'gray'
    default:
      return 'blue'
  }
}

function statusLabel(status: AssetStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'Available for Booking'
    case 'UNDER_MAINTENANCE':
      return 'Under Maintenance'
    case 'RETIRED':
      return 'Retired / Decommissioned'
    default:
      return status
  }
}

export default function AssetDetailsPage() {
  const { id } = useParams<{ id: string }>()
  const assetId = id && !isNaN(Number(id)) ? Number(id) : null

  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'

  const [asset, setAsset] = useState<Asset | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown | null>(null)

  // Modals
  const [retireOpened, { open: openRetire, close: closeRetire }] = useDisclosure(false)
  const [retireLoading, setRetireLoading] = useState(false)
  const [retireError, setRetireError] = useState<unknown | null>(null)

  useEffect(() => {
    if (assetId === null) return

    let active = true
    getAsset(assetId)
      .then((data) => {
        if (active) {
          setAsset(data)
          setError(null)
          setLoading(false)
        }
      })
      .catch((err) => {
        if (active) {
          setError(err)
          setLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [assetId])

  const handleRetire = async () => {
    if (!asset) return
    setRetireLoading(true)
    setRetireError(null)
    try {
      const updated = await retireAsset(asset.id)
      setAsset(updated)
      closeRetire()
    } catch (err) {
      setRetireError(err)
    } finally {
      setRetireLoading(false)
    }
  }

  if (assetId === null) {
    return (
      <Stack gap="md">
        <Button component={Link} to="/catalog" variant="subtle" leftSection={<IconArrowLeft size={16} />}>
          Back to Catalog
        </Button>
        <ErrorAlert error={new Error('Invalid equipment ID provided in URL')} />
      </Stack>
    )
  }

  if (loading) {
    return (
      <Center py="xl">
        <Loader size="xl" />
      </Center>
    )
  }

  if (!asset) {
    return (
      <Stack gap="md">
        <Button component={Link} to="/catalog" variant="subtle" leftSection={<IconArrowLeft size={16} />}>
          Back to Catalog
        </Button>
        {error ? <ErrorAlert error={error} /> : null}
      </Stack>
    )
  }

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="center">
        <Button component={Link} to="/catalog" variant="subtle" leftSection={<IconArrowLeft size={16} />}>
          Back to Catalog
        </Button>

        {isAdmin && asset.status !== 'RETIRED' ? (
          <Button
            variant="light"
            color="red"
            leftSection={<IconTrash size={16} />}
            onClick={() => {
              setRetireError(null)
              openRetire()
            }}
          >
            Retire Equipment
          </Button>
        ) : null}
      </Group>

      {error ? <ErrorAlert error={error} /> : null}

      {/* Asset Hero & Metadata Card */}
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <Group justify="space-between" align="flex-start" wrap="wrap">
            <div>
              <Group gap="xs" mb={4}>
                <Badge variant="light" color="blue" leftSection={<IconFolder size={12} />}>
                  {asset.categoryName}
                </Badge>
                <Badge color={statusBadgeColor(asset.status)} variant="filled">
                  {statusLabel(asset.status)}
                </Badge>
              </Group>
              <Title order={2}>{asset.name}</Title>
              <Text size="md" c="dimmed" mt={2}>
                Model: <strong>{asset.model}</strong>
              </Text>
            </div>
          </Group>

          <Divider />

          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
            <Group gap="sm">
              <ThemeIcon size="md" radius="md" variant="light" color="blue">
                <IconHash size={18} />
              </ThemeIcon>
              <div>
                <Text size="xs" c="dimmed">
                  Serial Number
                </Text>
                <Text fw={600} size="sm">
                  {asset.serialNumber}
                </Text>
              </div>
            </Group>

            <Group gap="sm">
              <ThemeIcon size="md" radius="md" variant="light" color="teal">
                <IconDoor size={18} />
              </ThemeIcon>
              <div>
                <Text size="xs" c="dimmed">
                  Laboratory Room
                </Text>
                <Text fw={600} size="sm">
                  {asset.labRoomCode} – {asset.labRoomName}
                </Text>
              </div>
            </Group>

            <Group gap="sm">
              <ThemeIcon size="md" radius="md" variant="light" color="grape">
                <IconCpu size={18} />
              </ThemeIcon>
              <div>
                <Text size="xs" c="dimmed">
                  Equipment Category
                </Text>
                <Text fw={600} size="sm">
                  {asset.categoryName}
                </Text>
              </div>
            </Group>
          </SimpleGrid>

          {asset.description ? (
            <Box mt="xs">
              <Text size="sm" fw={500} mb={2}>
                Description & Technical Specs:
              </Text>
              <Text size="sm" c="dimmed" style={{ whiteSpace: 'pre-wrap' }}>
                {asset.description}
              </Text>
            </Box>
          ) : null}
        </Stack>
      </Paper>

      {/* Booking / Operational Status Section */}
      {asset.status === 'AVAILABLE' ? (
        <Card withBorder radius="md" p="lg">
          <Title order={3} mb="xs">
            Reserve this Equipment
          </Title>
          <Text size="sm" c="dimmed" mb="md">
            Select a convenient weekday time slot (08:00 – 20:00, max 4 hours duration) to reserve this equipment.
          </Text>

          {/* Calin's Calendar Booking View component */}
          <CalendarBookingView
            asset={{
              id: asset.id,
              name: asset.name,
              model: asset.model,
              serialNumber: asset.serialNumber,
              status: asset.status,
              categoryId: asset.categoryId,
              labRoomId: asset.labRoomId,
            }}
          />
        </Card>
      ) : asset.status === 'UNDER_MAINTENANCE' ? (
        <Alert
          color="yellow"
          title="Equipment Currently Under Maintenance"
          icon={<IconAlertTriangle size={20} />}
          radius="md"
        >
          <Text size="sm" mb="sm">
            This asset is currently being serviced or repaired and cannot accept new reservations.
          </Text>
          <Button
            component={Link}
            to="/catalog"
            variant="light"
            color="yellow"
            size="xs"
          >
            Find available units in Catalog
          </Button>
        </Alert>
      ) : (
        <Alert color="gray" title="Asset Retired" icon={<IconAlertCircle size={20} />} radius="md">
          <Text size="sm">
            This asset has been permanently retired and decommissioned. It remains in the system for historical audit
            and reporting purposes.
          </Text>
        </Alert>
      )}

      {/* Confirmation Modal for Retiring an Asset */}
      <Modal opened={retireOpened} onClose={closeRetire} title="Retire Equipment Asset" centered>
        <Stack gap="md">
          {retireError ? <ErrorAlert error={retireError} /> : null}
          <Text size="sm">
            Are you sure you want to retire <strong>{asset.name}</strong> (S/N: {asset.serialNumber})?
          </Text>
          <Text size="xs" c="dimmed">
            Retiring an asset permanently prevents any new bookings or modifications. Existing historical records
            will remain preserved for auditing.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeRetire} disabled={retireLoading}>
              Cancel
            </Button>
            <Button color="red" onClick={handleRetire} loading={retireLoading}>
              Confirm Retirement
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
