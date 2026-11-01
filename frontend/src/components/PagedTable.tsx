import { Center, Group, Loader, Pagination, Paper, Table, Text } from '@mantine/core'
import type { ReactNode } from 'react'
import type { PageResponse } from '../api/types.ts'

export type Column<T> = {
  header: string
  render: (row: T) => ReactNode
}

type Props<T> = {
  page: PageResponse<T> | null
  columns: Column<T>[]
  rowKey: (row: T) => string | number
  onPageChange: (page: number) => void
  loading?: boolean
  emptyText?: string
}

// A table for any PageResponse from the backend, with paging under it.
// The backend counts pages from 0, Mantine's Pagination from 1: the +1 / -1 below converts.
export default function PagedTable<T>({ page, columns, rowKey, onPageChange, loading, emptyText }: Props<T>) {
  if (loading && !page) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }
  if (!page || page.content.length === 0) {
    return (
      <Text c="dimmed" ta="center" py="xl">
        {emptyText ?? 'Nothing to show yet.'}
      </Text>
    )
  }

  return (
    <Paper withBorder bg="var(--app-surface)">
      <Table.ScrollContainer minWidth={600}>
        <Table striped highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              {columns.map((column) => (
                <Table.Th key={column.header}>{column.header}</Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {page.content.map((row) => (
              <Table.Tr key={rowKey(row)}>
                {columns.map((column) => (
                  <Table.Td key={column.header}>{column.render(row)}</Table.Td>
                ))}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      {page.totalPages > 1 && (
        <Group justify="space-between" p="sm">
          <Text size="sm" c="dimmed">
            {page.totalElements} in total
          </Text>
          <Pagination total={page.totalPages} value={page.page + 1} onChange={(value) => onPageChange(value - 1)} />
        </Group>
      )}
    </Paper>
  )
}
