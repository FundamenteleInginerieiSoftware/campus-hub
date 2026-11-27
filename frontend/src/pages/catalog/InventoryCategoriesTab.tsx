import { ActionIcon, Button, Group, Modal, Paper, Stack, Table, Text, Textarea, TextInput, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconEdit, IconPlus, IconTrash } from '@tabler/icons-react'
import { useState } from 'react'
import { createCategory, deleteCategory, updateCategory, type Category } from '../../api/catalog.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

interface InventoryCategoriesTabProps {
  categories: Category[]
  onChanged: () => void
}

export default function InventoryCategoriesTab({ categories, onChanged }: InventoryCategoriesTabProps) {
  const [categoryModalOpened, { open: openCategoryModal, close: closeCategoryModal }] = useDisclosure(false)
  const [editingCategory, setEditingCategory] = useState<Category | null>(null)
  const [categoryFormName, setCategoryFormName] = useState('')
  const [categoryFormDescription, setCategoryFormDescription] = useState('')
  const [categoryFormLoading, setCategoryFormLoading] = useState(false)
  const [formModalError, setFormModalError] = useState<unknown | null>(null)

  const [deleteCatModalOpened, { open: openDeleteCatModal, close: closeDeleteCatModal }] = useDisclosure(false)
  const [deletingCategory, setDeletingCategory] = useState<Category | null>(null)
  const [deleteCatLoading, setDeleteCatLoading] = useState(false)
  const [deleteModalError, setDeleteModalError] = useState<unknown | null>(null)

  const handleOpenAddCategory = () => {
    setEditingCategory(null)
    setCategoryFormName('')
    setCategoryFormDescription('')
    setFormModalError(null)
    openCategoryModal()
  }

  const handleOpenEditCategory = (cat: Category) => {
    setEditingCategory(cat)
    setCategoryFormName(cat.name)
    setCategoryFormDescription(cat.description || '')
    setFormModalError(null)
    openCategoryModal()
  }

  const handleSaveCategory = async () => {
    if (!categoryFormName.trim()) return
    setCategoryFormLoading(true)
    setFormModalError(null)
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
      onChanged()
    } catch (err) {
      setFormModalError(err)
    } finally {
      setCategoryFormLoading(false)
    }
  }

  const handleConfirmDeleteCategory = async () => {
    if (!deletingCategory) return
    setDeleteCatLoading(true)
    setDeleteModalError(null)
    try {
      await deleteCategory(deletingCategory.id)
      closeDeleteCatModal()
      onChanged()
    } catch (err) {
      setDeleteModalError(err)
    } finally {
      setDeleteCatLoading(false)
    }
  }

  return (
    <>
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
                              setDeleteModalError(null)
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

      <Modal
        opened={categoryModalOpened}
        onClose={() => {
          if (!categoryFormLoading) closeCategoryModal()
        }}
        closeOnClickOutside={!categoryFormLoading}
        closeOnEscape={!categoryFormLoading}
        withCloseButton={!categoryFormLoading}
        title={editingCategory ? 'Edit Category' : 'Add New Category'}
        centered
      >
        <Stack gap="sm">
          {formModalError ? <ErrorAlert error={formModalError} /> : null}
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

      <Modal
        opened={deleteCatModalOpened}
        onClose={() => {
          if (!deleteCatLoading) closeDeleteCatModal()
        }}
        closeOnClickOutside={!deleteCatLoading}
        closeOnEscape={!deleteCatLoading}
        withCloseButton={!deleteCatLoading}
        title="Delete Category"
        centered
      >
        <Stack gap="md">
          {deleteModalError ? <ErrorAlert error={deleteModalError} /> : null}
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
    </>
  )
}
