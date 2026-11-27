import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Tooltip,
} from '@mantine/core'
import { useDebouncedValue, useDisclosure } from '@mantine/hooks'
import { IconEdit, IconPlus, IconSearch, IconTrash } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import {
  createAsset,
  retireAsset,
  searchAssets,
  updateAsset,
  type Asset,
  type Category,
  type LabRoom,
} from '../../api/catalog.ts'
import type { PageResponse } from '../../api/types.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'
import PagedTable, { type Column } from '../../components/PagedTable.tsx'
import { statusBadgeColor } from './assetStatus.ts'

interface InventoryAssetsTabProps {
  categories: Category[]
  labRooms: LabRoom[]
  reloadSignal: number
  onTotalChange: (total: number) => void
  onError: (err: unknown | null) => void
}

export default function InventoryAssetsTab({
  categories,
  labRooms,
  reloadSignal,
  onTotalChange,
  onError,
}: InventoryAssetsTabProps) {
  const [assetsPage, setAssetsPage] = useState<PageResponse<Asset>>({
    content: [],
    page: 0,
    size: 15,
    totalElements: 0,
    totalPages: 0,
  })
  const [assetsLoading, setAssetsLoading] = useState(true)
  const [assetSearchQuery, setAssetSearchQuery] = useState('')
  const [debouncedAssetSearch] = useDebouncedValue(assetSearchQuery, 300)
  const [assetCurrentPage, setAssetCurrentPage] = useState(0)
  const [localReload, setLocalReload] = useState(0)

  const [assetModalOpened, { open: openAssetModal, close: closeAssetModal }] = useDisclosure(false)
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null)
  const [assetFormName, setAssetFormName] = useState('')
  const [assetFormModel, setAssetFormModel] = useState('')
  const [assetFormSerial, setAssetFormSerial] = useState('')
  const [assetFormDescription, setAssetFormDescription] = useState('')
  const [assetFormCategoryId, setAssetFormCategoryId] = useState<string | null>(null)
  const [assetFormLabRoomId, setAssetFormLabRoomId] = useState<string | null>(null)
  const [assetFormLoading, setAssetFormLoading] = useState(false)
  const [assetModalError, setAssetModalError] = useState<unknown | null>(null)

  const [retireModalOpened, { open: openRetireModal, close: closeRetireModal }] = useDisclosure(false)
  const [retiringAsset, setRetiringAsset] = useState<Asset | null>(null)
  const [retireLoading, setRetireLoading] = useState(false)
  const [retireModalError, setRetireModalError] = useState<unknown | null>(null)

  useEffect(() => {
    let active = true

    void Promise.resolve().then(() => {
      if (active) setAssetsLoading(true)
    })
    searchAssets({
      search: debouncedAssetSearch.trim() || undefined,
      page: assetCurrentPage,
      size: 15,
      sort: 'name,asc',
    })
      .then((data) => {
        if (active) {
          setAssetsPage(data)
          onTotalChange(data.totalElements)
          onError(null)
        }
      })
      .catch((err) => {
        if (active) {
          onError(err)
        }
      })
      .finally(() => {
        if (active) {
          setAssetsLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [assetCurrentPage, debouncedAssetSearch, localReload, reloadSignal, onError, onTotalChange])

  const handleOpenAddAsset = () => {
    setEditingAsset(null)
    setAssetFormName('')
    setAssetFormModel('')
    setAssetFormSerial('')
    setAssetFormDescription('')
    setAssetFormCategoryId(categories.length > 0 ? String(categories[0].id) : null)
    setAssetFormLabRoomId(labRooms.length > 0 ? String(labRooms[0].id) : null)
    setAssetModalError(null)
    openAssetModal()
  }

  const handleOpenEditAsset = (asset: Asset) => {
    setEditingAsset(asset)
    setAssetFormName(asset.name)
    setAssetFormModel(asset.model)
    setAssetFormSerial(asset.serialNumber)
    setAssetFormDescription(asset.description || '')
    setAssetFormCategoryId(String(asset.categoryId))
    setAssetFormLabRoomId(String(asset.labRoomId))
    setAssetModalError(null)
    openAssetModal()
  }

  const handleSaveAsset = async () => {
    if (!assetFormName.trim() || !assetFormModel.trim() || !assetFormCategoryId || !assetFormLabRoomId) {
      return
    }

    setAssetFormLoading(true)
    setAssetModalError(null)
    try {
      if (editingAsset) {
        await updateAsset(editingAsset.id, {
          name: assetFormName.trim(),
          model: assetFormModel.trim(),
          description: assetFormDescription.trim() || undefined,
          categoryId: Number(assetFormCategoryId),
          labRoomId: Number(assetFormLabRoomId),
          version: editingAsset.version,
        })
      } else {
        await createAsset({
          name: assetFormName.trim(),
          model: assetFormModel.trim(),
          serialNumber: assetFormSerial.trim(),
          description: assetFormDescription.trim() || undefined,
          categoryId: Number(assetFormCategoryId),
          labRoomId: Number(assetFormLabRoomId),
        })
      }
      closeAssetModal()
      setLocalReload((v) => v + 1)
    } catch (err) {
      setAssetModalError(err)
    } finally {
      setAssetFormLoading(false)
    }
  }

  const handleConfirmRetire = async () => {
    if (!retiringAsset) return
    setRetireLoading(true)
    setRetireModalError(null)
    try {
      await retireAsset(retiringAsset.id)
      closeRetireModal()
      setLocalReload((v) => v + 1)
    } catch (err) {
      setRetireModalError(err)
    } finally {
      setRetireLoading(false)
    }
  }

  const assetColumns: Column<Asset>[] = [
    {
      header: 'Equipment',
      render: (a) => (
        <div>
          <Text fw={600} size="sm">
            {a.name}
          </Text>
          <Text size="xs" c="dimmed">
            Model: {a.model}
          </Text>
        </div>
      ),
    },
    {
      header: 'Serial Number',
      render: (a) => (
        <Text size="sm" ff="monospace">
          {a.serialNumber}
        </Text>
      ),
    },
    {
      header: 'Category',
      render: (a) => (
        <Badge variant="light" color="blue" size="sm">
          {a.categoryName}
        </Badge>
      ),
    },
    {
      header: 'Lab Room',
      render: (a) => (
        <Text size="sm">
          {a.labRoomCode} ({a.labRoomName})
        </Text>
      ),
    },
    {
      header: 'Status',
      render: (a) => (
        <Badge color={statusBadgeColor(a.status)} variant="filled" size="sm">
          {a.status}
        </Badge>
      ),
    },
    {
      header: 'Actions',
      render: (a) => (
        <Group gap="xs" wrap="nowrap">
          {a.status !== 'RETIRED' ? (
            <>
              <Tooltip label="Edit equipment details">
                <ActionIcon variant="subtle" color="blue" onClick={() => handleOpenEditAsset(a)}>
                  <IconEdit size={16} />
                </ActionIcon>
              </Tooltip>
              <Tooltip label="Retire equipment permanently">
                <ActionIcon
                  variant="subtle"
                  color="red"
                  onClick={() => {
                    setRetiringAsset(a)
                    setRetireModalError(null)
                    openRetireModal()
                  }}
                >
                  <IconTrash size={16} />
                </ActionIcon>
              </Tooltip>
            </>
          ) : (
            <Text size="xs" c="dimmed">
              Retired
            </Text>
          )}
        </Group>
      ),
    },
  ]

  return (
    <>
      <Stack gap="md">
        <Group justify="space-between" align="center">
          <TextInput
            placeholder="Search equipment..."
            value={assetSearchQuery}
            onChange={(e) => {
              setAssetSearchQuery(e.currentTarget.value)
              setAssetCurrentPage(0)
            }}
            leftSection={<IconSearch size={16} />}
            style={{ width: 320 }}
          />

          <Button leftSection={<IconPlus size={16} />} onClick={handleOpenAddAsset}>
            Add Equipment
          </Button>
        </Group>

        <PagedTable
          page={assetsPage}
          columns={assetColumns}
          rowKey={(a) => a.id}
          onPageChange={(p) => setAssetCurrentPage(p)}
          loading={assetsLoading}
          emptyText="No equipment found in inventory."
        />
      </Stack>

      <Modal
        opened={assetModalOpened}
        onClose={() => {
          if (!assetFormLoading) closeAssetModal()
        }}
        closeOnClickOutside={!assetFormLoading}
        closeOnEscape={!assetFormLoading}
        withCloseButton={!assetFormLoading}
        title={editingAsset ? 'Edit Equipment Asset' : 'Add New Equipment Asset'}
        size="lg"
        centered
      >
        <Stack gap="sm">
          {assetModalError ? <ErrorAlert error={assetModalError} /> : null}
          <TextInput
            label="Equipment Name"
            placeholder="e.g. Rigol DS1054Z Oscilloscope no. 4"
            required
            value={assetFormName}
            onChange={(e) => setAssetFormName(e.currentTarget.value)}
          />

          <TextInput
            label="Model (Required for auto-reassignment)"
            placeholder="e.g. DS1054Z"
            required
            value={assetFormModel}
            onChange={(e) => setAssetFormModel(e.currentTarget.value)}
          />

          {!editingAsset ? (
            <TextInput
              label="Serial Number"
              placeholder="e.g. RIGOL-DS1054Z-004"
              required
              value={assetFormSerial}
              onChange={(e) => setAssetFormSerial(e.currentTarget.value)}
            />
          ) : null}

          <Select
            label="Category"
            placeholder="Select equipment category"
            required
            data={categories.map((c) => ({ value: String(c.id), label: c.name }))}
            value={assetFormCategoryId}
            onChange={setAssetFormCategoryId}
            searchable
          />

          <Select
            label="Laboratory Room"
            placeholder="Select laboratory location"
            required
            data={labRooms.map((r) => ({ value: String(r.id), label: `${r.code} - ${r.name}` }))}
            value={assetFormLabRoomId}
            onChange={setAssetFormLabRoomId}
            searchable
          />

          <Textarea
            label="Description & Specifications (Optional)"
            placeholder="Technical details, bandwidth, accessories included..."
            minRows={3}
            value={assetFormDescription}
            onChange={(e) => setAssetFormDescription(e.currentTarget.value)}
          />

          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeAssetModal} disabled={assetFormLoading}>
              Cancel
            </Button>
            <Button
              onClick={handleSaveAsset}
              loading={assetFormLoading}
              disabled={
                !assetFormName.trim() ||
                !assetFormModel.trim() ||
                (!editingAsset && !assetFormSerial.trim()) ||
                !assetFormCategoryId ||
                !assetFormLabRoomId
              }
            >
              {editingAsset ? 'Save Changes' : 'Create Equipment'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={retireModalOpened}
        onClose={() => {
          if (!retireLoading) closeRetireModal()
        }}
        closeOnClickOutside={!retireLoading}
        closeOnEscape={!retireLoading}
        withCloseButton={!retireLoading}
        title="Retire Equipment"
        centered
      >
        <Stack gap="md">
          {retireModalError ? <ErrorAlert error={retireModalError} /> : null}
          <Text size="sm">
            Are you sure you want to retire <strong>{retiringAsset?.name}</strong> (S/N:{' '}
            {retiringAsset?.serialNumber})?
          </Text>
          <Text size="xs" c="dimmed">
            Retiring an asset permanently prevents new bookings. It remains archived in the database for auditing
            purposes.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeRetireModal} disabled={retireLoading}>
              Cancel
            </Button>
            <Button color="red" onClick={handleConfirmRetire} loading={retireLoading}>
              Confirm Retirement
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  )
}
