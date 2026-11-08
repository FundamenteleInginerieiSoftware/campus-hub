import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Modal,
  Paper,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  Textarea,
  TextInput,
  Tooltip,
} from '@mantine/core'
import { useDebouncedValue, useDisclosure } from '@mantine/hooks'
import {
  IconCpu,
  IconDoor,
  IconEdit,
  IconFolder,
  IconPlus,
  IconSearch,
  IconTrash,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import {
  createAsset,
  createCategory,
  createLabRoom,
  deleteCategory,
  deleteLabRoom,
  getCategories,
  getLabRooms,
  retireAsset,
  searchAssets,
  updateAsset,
  updateCategory,
  updateLabRoom,
  type Asset,
  type AssetStatus,
  type Category,
  type LabRoom,
} from '../api/catalog.ts'
import type { PageResponse } from '../api/types.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import PagedTable, { type Column } from '../components/PagedTable.tsx'

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

export default function AdminInventoryPage() {
  const [activeTab, setActiveTab] = useState<string | null>('equipment')
  const [error, setError] = useState<unknown | null>(null)

  // ------------------------------------------------------------------
  // Shared Options (Categories & Lab Rooms)
  // ------------------------------------------------------------------
  const [categories, setCategories] = useState<Category[]>([])
  const [labRooms, setLabRooms] = useState<LabRoom[]>([])

  const loadLookups = () => {
    Promise.all([getCategories(), getLabRooms()])
      .then(([cats, rooms]) => {
        setCategories(cats)
        setLabRooms(rooms)
      })
      .catch((err) => setError(err))
  }

  useEffect(() => {
    loadLookups()
  }, [])

  // ------------------------------------------------------------------
  // TAB 1: Assets Management
  // ------------------------------------------------------------------
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
  const [reloadTrigger, setReloadTrigger] = useState(0)
  const [modalError, setModalError] = useState<unknown | null>(null)

  // Asset Form Modal
  const [assetModalOpened, { open: openAssetModal, close: closeAssetModal }] = useDisclosure(false)
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null)
  const [assetFormName, setAssetFormName] = useState('')
  const [assetFormModel, setAssetFormModel] = useState('')
  const [assetFormSerial, setAssetFormSerial] = useState('')
  const [assetFormDescription, setAssetFormDescription] = useState('')
  const [assetFormCategoryId, setAssetFormCategoryId] = useState<string | null>(null)
  const [assetFormLabRoomId, setAssetFormLabRoomId] = useState<string | null>(null)
  const [assetFormLoading, setAssetFormLoading] = useState(false)

  // Retire Modal
  const [retireModalOpened, { open: openRetireModal, close: closeRetireModal }] = useDisclosure(false)
  const [retiringAsset, setRetiringAsset] = useState<Asset | null>(null)
  const [retireLoading, setRetireLoading] = useState(false)

  useEffect(() => {
    let active = true
    searchAssets({
      search: debouncedAssetSearch.trim() || undefined,
      page: assetCurrentPage,
      size: 15,
      sort: 'name,asc',
    })
      .then((data) => {
        if (active) {
          setAssetsPage(data)
          setError(null)
          setAssetsLoading(false)
        }
      })
      .catch((err) => {
        if (active) {
          setError(err)
          setAssetsLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [assetCurrentPage, debouncedAssetSearch, reloadTrigger])

  const handleOpenAddAsset = () => {
    setEditingAsset(null)
    setAssetFormName('')
    setAssetFormModel('')
    setAssetFormSerial('')
    setAssetFormDescription('')
    setAssetFormCategoryId(categories.length > 0 ? String(categories[0].id) : null)
    setAssetFormLabRoomId(labRooms.length > 0 ? String(labRooms[0].id) : null)
    setModalError(null)
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
    setModalError(null)
    openAssetModal()
  }

  const handleSaveAsset = async () => {
    if (!assetFormName.trim() || !assetFormModel.trim() || !assetFormCategoryId || !assetFormLabRoomId) {
      return
    }

    setAssetFormLoading(true)
    setModalError(null)
    try {
      if (editingAsset) {
        await updateAsset(editingAsset.id, {
          name: assetFormName.trim(),
          model: assetFormModel.trim(),
          description: assetFormDescription.trim() || undefined,
          categoryId: Number(assetFormCategoryId),
          labRoomId: Number(assetFormLabRoomId),
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
      setAssetsLoading(true)
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
    } finally {
      setAssetFormLoading(false)
    }
  }

  const handleConfirmRetire = async () => {
    if (!retiringAsset) return
    setRetireLoading(true)
    setModalError(null)
    try {
      await retireAsset(retiringAsset.id)
      closeRetireModal()
      setAssetsLoading(true)
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
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
                    setModalError(null)
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

  // ------------------------------------------------------------------
  // TAB 2: Categories Management
  // ------------------------------------------------------------------
  const [categoryModalOpened, { open: openCategoryModal, close: closeCategoryModal }] = useDisclosure(false)
  const [editingCategory, setEditingCategory] = useState<Category | null>(null)
  const [categoryFormName, setCategoryFormName] = useState('')
  const [categoryFormDescription, setCategoryFormDescription] = useState('')
  const [categoryFormLoading, setCategoryFormLoading] = useState(false)

  const [deleteCatModalOpened, { open: openDeleteCatModal, close: closeDeleteCatModal }] = useDisclosure(false)
  const [deletingCategory, setDeletingCategory] = useState<Category | null>(null)
  const [deleteCatLoading, setDeleteCatLoading] = useState(false)

  const handleOpenAddCategory = () => {
    setEditingCategory(null)
    setCategoryFormName('')
    setCategoryFormDescription('')
    setModalError(null)
    openCategoryModal()
  }

  const handleOpenEditCategory = (cat: Category) => {
    setEditingCategory(cat)
    setCategoryFormName(cat.name)
    setCategoryFormDescription(cat.description || '')
    setModalError(null)
    openCategoryModal()
  }

  const handleSaveCategory = async () => {
    if (!categoryFormName.trim()) return
    setCategoryFormLoading(true)
    setModalError(null)
    try {
      if (editingCategory) {
        await updateCategory(editingCategory.id, {
          name: categoryFormName.trim(),
          description: categoryFormDescription.trim() || undefined,
        })
      } else {
        await createCategory({
          name: categoryFormName.trim(),
          description: categoryFormDescription.trim() || undefined,
        })
      }
      closeCategoryModal()
      loadLookups()
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
    } finally {
      setCategoryFormLoading(false)
    }
  }

  const handleConfirmDeleteCategory = async () => {
    if (!deletingCategory) return
    setDeleteCatLoading(true)
    setModalError(null)
    try {
      await deleteCategory(deletingCategory.id)
      closeDeleteCatModal()
      loadLookups()
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
    } finally {
      setDeleteCatLoading(false)
    }
  }

  // ------------------------------------------------------------------
  // TAB 3: Lab Rooms Management
  // ------------------------------------------------------------------
  const [roomModalOpened, { open: openRoomModal, close: closeRoomModal }] = useDisclosure(false)
  const [editingRoom, setEditingRoom] = useState<LabRoom | null>(null)
  const [roomFormCode, setRoomFormCode] = useState('')
  const [roomFormName, setRoomFormName] = useState('')
  const [roomFormBuilding, setRoomFormBuilding] = useState('')
  const [roomFormLoading, setRoomFormLoading] = useState(false)

  const [deleteRoomModalOpened, { open: openDeleteRoomModal, close: closeDeleteRoomModal }] = useDisclosure(false)
  const [deletingRoom, setDeletingRoom] = useState<LabRoom | null>(null)
  const [deleteRoomLoading, setDeleteRoomLoading] = useState(false)

  const handleOpenAddRoom = () => {
    setEditingRoom(null)
    setRoomFormCode('')
    setRoomFormName('')
    setRoomFormBuilding('')
    setModalError(null)
    openRoomModal()
  }

  const handleOpenEditRoom = (room: LabRoom) => {
    setEditingRoom(room)
    setRoomFormCode(room.code)
    setRoomFormName(room.name)
    setRoomFormBuilding(room.building || '')
    setModalError(null)
    openRoomModal()
  }

  const handleSaveRoom = async () => {
    if (!roomFormCode.trim() || !roomFormName.trim()) return
    setRoomFormLoading(true)
    setModalError(null)
    try {
      if (editingRoom) {
        await updateLabRoom(editingRoom.id, {
          code: roomFormCode.trim(),
          name: roomFormName.trim(),
          building: roomFormBuilding.trim() || undefined,
        })
      } else {
        await createLabRoom({
          code: roomFormCode.trim(),
          name: roomFormName.trim(),
          building: roomFormBuilding.trim() || undefined,
        })
      }
      closeRoomModal()
      loadLookups()
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
    } finally {
      setRoomFormLoading(false)
    }
  }

  const handleConfirmDeleteRoom = async () => {
    if (!deletingRoom) return
    setDeleteRoomLoading(true)
    setModalError(null)
    try {
      await deleteLabRoom(deletingRoom.id)
      closeDeleteRoomModal()
      loadLookups()
      setReloadTrigger((v) => v + 1)
    } catch (err) {
      setModalError(err)
    } finally {
      setDeleteRoomLoading(false)
    }
  }

  return (
    <Stack gap="lg">
      <PageHeader
        title="Inventory Management"
        description="Admin dashboard for provisioning equipment assets, managing laboratory categories, and lab locations."
      />

      {error ? <ErrorAlert error={error} /> : null}

      <Tabs value={activeTab} onChange={setActiveTab} variant="outline" radius="md">
        <Tabs.List mb="md">
          <Tabs.Tab value="equipment" leftSection={<IconCpu size={16} />}>
            Equipment Assets ({assetsPage.totalElements})
          </Tabs.Tab>
          <Tabs.Tab value="categories" leftSection={<IconFolder size={16} />}>
            Categories ({categories.length})
          </Tabs.Tab>
          <Tabs.Tab value="rooms" leftSection={<IconDoor size={16} />}>
            Lab Rooms ({labRooms.length})
          </Tabs.Tab>
        </Tabs.List>

        {/* ------------------------------------------------------------- */}
        {/* PANEL 1: Equipment Assets                                     */}
        {/* ------------------------------------------------------------- */}
        <Tabs.Panel value="equipment">
          <Stack gap="md">
            <Group justify="space-between" align="center">
              <TextInput
                placeholder="Search equipment..."
                value={assetSearchQuery}
                onChange={(e) => {
                  setAssetsLoading(true)
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
              onPageChange={(p) => {
                setAssetsLoading(true)
                setAssetCurrentPage(p)
              }}
              loading={assetsLoading}
              emptyText="No equipment found in inventory."
            />
          </Stack>
        </Tabs.Panel>

        {/* ------------------------------------------------------------- */}
        {/* PANEL 2: Categories                                          */}
        {/* ------------------------------------------------------------- */}
        <Tabs.Panel value="categories">
          <Stack gap="md">
            <Group justify="flex-end">
              <Button leftSection={<IconPlus size={16} />} onClick={handleOpenAddCategory}>
                Add Category
              </Button>
            </Group>

            <Paper withBorder radius="md">
              <Table striped highlightOnHover verticalSpacing="sm">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th style={{ width: 60 }}>ID</Table.Th>
                    <Table.Th style={{ width: 220 }}>Name</Table.Th>
                    <Table.Th>Description</Table.Th>
                    <Table.Th style={{ width: 120 }}>Actions</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {categories.length === 0 ? (
                    <Table.Tr>
                      <Table.Td colSpan={4} style={{ textAlign: 'center' }}>
                        No categories found.
                      </Table.Td>
                    </Table.Tr>
                  ) : (
                    categories.map((c) => (
                      <Table.Tr key={c.id}>
                        <Table.Td>{c.id}</Table.Td>
                        <Table.Td fw={600}>{c.name}</Table.Td>
                        <Table.Td c="dimmed">{c.description || '—'}</Table.Td>
                        <Table.Td>
                          <Group gap="xs">
                            <Tooltip label="Edit category">
                              <ActionIcon variant="subtle" color="blue" onClick={() => handleOpenEditCategory(c)}>
                                <IconEdit size={16} />
                              </ActionIcon>
                            </Tooltip>
                            <Tooltip label="Delete category">
                              <ActionIcon
                                variant="subtle"
                                color="red"
                                onClick={() => {
                                  setDeletingCategory(c)
                                  setModalError(null)
                                  openDeleteCatModal()
                                }}
                              >
                                <IconTrash size={16} />
                              </ActionIcon>
                            </Tooltip>
                          </Group>
                        </Table.Td>
                      </Table.Tr>
                    ))
                  )}
                </Table.Tbody>
              </Table>
            </Paper>
          </Stack>
        </Tabs.Panel>

        {/* ------------------------------------------------------------- */}
        {/* PANEL 3: Lab Rooms                                           */}
        {/* ------------------------------------------------------------- */}
        <Tabs.Panel value="rooms">
          <Stack gap="md">
            <Group justify="flex-end">
              <Button leftSection={<IconPlus size={16} />} onClick={handleOpenAddRoom}>
                Add Lab Room
              </Button>
            </Group>

            <Paper withBorder radius="md">
              <Table striped highlightOnHover verticalSpacing="sm">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th style={{ width: 60 }}>ID</Table.Th>
                    <Table.Th style={{ width: 120 }}>Code</Table.Th>
                    <Table.Th style={{ width: 240 }}>Name</Table.Th>
                    <Table.Th>Building</Table.Th>
                    <Table.Th style={{ width: 120 }}>Actions</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {labRooms.length === 0 ? (
                    <Table.Tr>
                      <Table.Td colSpan={5} style={{ textAlign: 'center' }}>
                        No lab rooms found.
                      </Table.Td>
                    </Table.Tr>
                  ) : (
                    labRooms.map((r) => (
                      <Table.Tr key={r.id}>
                        <Table.Td>{r.id}</Table.Td>
                        <Table.Td fw={700} ff="monospace">
                          {r.code}
                        </Table.Td>
                        <Table.Td>{r.name}</Table.Td>
                        <Table.Td c="dimmed">{r.building || '—'}</Table.Td>
                        <Table.Td>
                          <Group gap="xs">
                            <Tooltip label="Edit lab room">
                              <ActionIcon variant="subtle" color="blue" onClick={() => handleOpenEditRoom(r)}>
                                <IconEdit size={16} />
                              </ActionIcon>
                            </Tooltip>
                            <Tooltip label="Delete lab room">
                              <ActionIcon
                                variant="subtle"
                                color="red"
                                onClick={() => {
                                  setDeletingRoom(r)
                                  setModalError(null)
                                  openDeleteRoomModal()
                                }}
                              >
                                <IconTrash size={16} />
                              </ActionIcon>
                            </Tooltip>
                          </Group>
                        </Table.Td>
                      </Table.Tr>
                    ))
                  )}
                </Table.Tbody>
              </Table>
            </Paper>
          </Stack>
        </Tabs.Panel>
      </Tabs>

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Create / Edit Asset                                    */}
      {/* ------------------------------------------------------------- */}
      <Modal
        opened={assetModalOpened}
        onClose={closeAssetModal}
        title={editingAsset ? 'Edit Equipment Asset' : 'Add New Equipment Asset'}
        size="lg"
        centered
      >
        <Stack gap="sm">
          {modalError ? <ErrorAlert error={modalError} /> : null}
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

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Retire Asset Confirmation                              */}
      {/* ------------------------------------------------------------- */}
      <Modal opened={retireModalOpened} onClose={closeRetireModal} title="Retire Equipment" centered>
        <Stack gap="md">
          {modalError ? <ErrorAlert error={modalError} /> : null}
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

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Create / Edit Category                                 */}
      {/* ------------------------------------------------------------- */}
      <Modal
        opened={categoryModalOpened}
        onClose={closeCategoryModal}
        title={editingCategory ? 'Edit Category' : 'Add New Category'}
        centered
      >
        <Stack gap="sm">
          {modalError ? <ErrorAlert error={modalError} /> : null}
          <TextInput
            label="Category Name"
            placeholder="e.g. Signal Generators"
            required
            value={categoryFormName}
            onChange={(e) => setCategoryFormName(e.currentTarget.value)}
          />
          <Textarea
            label="Description (Optional)"
            placeholder="Brief explanation of this equipment group..."
            minRows={2}
            value={categoryFormDescription}
            onChange={(e) => setCategoryFormDescription(e.currentTarget.value)}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeCategoryModal} disabled={categoryFormLoading}>
              Cancel
            </Button>
            <Button onClick={handleSaveCategory} loading={categoryFormLoading} disabled={!categoryFormName.trim()}>
              {editingCategory ? 'Save Changes' : 'Create Category'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Delete Category Confirmation                           */}
      {/* ------------------------------------------------------------- */}
      <Modal opened={deleteCatModalOpened} onClose={closeDeleteCatModal} title="Delete Category" centered>
        <Stack gap="md">
          {modalError ? <ErrorAlert error={modalError} /> : null}
          <Text size="sm">
            Are you sure you want to delete category <strong>{deletingCategory?.name}</strong>?
          </Text>
          <Text size="xs" c="dimmed">
            Note: Deletion will fail if any equipment is currently assigned to this category.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeDeleteCatModal} disabled={deleteCatLoading}>
              Cancel
            </Button>
            <Button color="red" onClick={handleConfirmDeleteCategory} loading={deleteCatLoading}>
              Delete Category
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Create / Edit Lab Room                                 */}
      {/* ------------------------------------------------------------- */}
      <Modal
        opened={roomModalOpened}
        onClose={closeRoomModal}
        title={editingRoom ? 'Edit Lab Room' : 'Add New Lab Room'}
        centered
      >
        <Stack gap="sm">
          {modalError ? <ErrorAlert error={modalError} /> : null}
          <TextInput
            label="Room Code"
            placeholder="e.g. C305"
            required
            value={roomFormCode}
            onChange={(e) => setRoomFormCode(e.currentTarget.value)}
          />
          <TextInput
            label="Room Name"
            placeholder="e.g. Prototyping & Fabrication Lab"
            required
            value={roomFormName}
            onChange={(e) => setRoomFormName(e.currentTarget.value)}
          />
          <TextInput
            label="Building / Floor (Optional)"
            placeholder="e.g. Building C, 3rd Floor"
            value={roomFormBuilding}
            onChange={(e) => setRoomFormBuilding(e.currentTarget.value)}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeRoomModal} disabled={roomFormLoading}>
              Cancel
            </Button>
            <Button
              onClick={handleSaveRoom}
              loading={roomFormLoading}
              disabled={!roomFormCode.trim() || !roomFormName.trim()}
            >
              {editingRoom ? 'Save Changes' : 'Create Lab Room'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* ------------------------------------------------------------- */}
      {/* MODAL: Delete Lab Room Confirmation                           */}
      {/* ------------------------------------------------------------- */}
      <Modal opened={deleteRoomModalOpened} onClose={closeDeleteRoomModal} title="Delete Lab Room" centered>
        <Stack gap="md">
          {modalError ? <ErrorAlert error={modalError} /> : null}
          <Text size="sm">
            Are you sure you want to delete lab room <strong>{deletingRoom?.code}</strong> ({deletingRoom?.name})?
          </Text>
          <Text size="xs" c="dimmed">
            Note: Deletion will fail if any equipment is currently placed in this room.
          </Text>
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={closeDeleteRoomModal} disabled={deleteRoomLoading}>
              Cancel
            </Button>
            <Button color="red" onClick={handleConfirmDeleteRoom} loading={deleteRoomLoading}>
              Delete Lab Room
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
