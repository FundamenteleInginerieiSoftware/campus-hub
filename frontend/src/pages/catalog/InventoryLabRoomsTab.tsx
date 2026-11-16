import { ActionIcon, Button, Group, Modal, Paper, Stack, Table, Text, TextInput, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconEdit, IconPlus, IconTrash } from '@tabler/icons-react'
import { useState } from 'react'
import { createLabRoom, deleteLabRoom, updateLabRoom, type LabRoom } from '../../api/catalog.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

interface InventoryLabRoomsTabProps {
  labRooms: LabRoom[]
  onChanged: () => void
}

export default function InventoryLabRoomsTab({ labRooms, onChanged }: InventoryLabRoomsTabProps) {
  const [roomModalOpened, { open: openRoomModal, close: closeRoomModal }] = useDisclosure(false)
  const [editingRoom, setEditingRoom] = useState<LabRoom | null>(null)
  const [roomFormCode, setRoomFormCode] = useState('')
  const [roomFormName, setRoomFormName] = useState('')
  const [roomFormBuilding, setRoomFormBuilding] = useState('')
  const [roomFormLoading, setRoomFormLoading] = useState(false)
  const [formModalError, setFormModalError] = useState<unknown | null>(null)

  const [deleteRoomModalOpened, { open: openDeleteRoomModal, close: closeDeleteRoomModal }] = useDisclosure(false)
  const [deletingRoom, setDeletingRoom] = useState<LabRoom | null>(null)
  const [deleteRoomLoading, setDeleteRoomLoading] = useState(false)
  const [deleteModalError, setDeleteModalError] = useState<unknown | null>(null)

  const handleOpenAddRoom = () => {
    setEditingRoom(null)
    setRoomFormCode('')
    setRoomFormName('')
    setRoomFormBuilding('')
    setFormModalError(null)
    openRoomModal()
  }

  const handleOpenEditRoom = (room: LabRoom) => {
    setEditingRoom(room)
    setRoomFormCode(room.code)
    setRoomFormName(room.name)
    setRoomFormBuilding(room.building || '')
    setFormModalError(null)
    openRoomModal()
  }

  const handleSaveRoom = async () => {
    if (!roomFormCode.trim() || !roomFormName.trim()) return
    setRoomFormLoading(true)
    setFormModalError(null)
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
      onChanged()
    } catch (err) {
      setFormModalError(err)
    } finally {
      setRoomFormLoading(false)
    }
  }

  const handleConfirmDeleteRoom = async () => {
    if (!deletingRoom) return
    setDeleteRoomLoading(true)
    setDeleteModalError(null)
    try {
      await deleteLabRoom(deletingRoom.id)
      closeDeleteRoomModal()
      onChanged()
    } catch (err) {
      setDeleteModalError(err)
    } finally {
      setDeleteRoomLoading(false)
    }
  }

  return (
    <>
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
                              setDeleteModalError(null)
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

      <Modal
        opened={roomModalOpened}
        onClose={closeRoomModal}
        title={editingRoom ? 'Edit Lab Room' : 'Add New Lab Room'}
        centered
      >
        <Stack gap="sm">
          {formModalError ? <ErrorAlert error={formModalError} /> : null}
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

      <Modal opened={deleteRoomModalOpened} onClose={closeDeleteRoomModal} title="Delete Lab Room" centered>
        <Stack gap="md">
          {deleteModalError ? <ErrorAlert error={deleteModalError} /> : null}
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
    </>
  )
}
