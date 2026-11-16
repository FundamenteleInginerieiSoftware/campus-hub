import { Stack, Tabs } from '@mantine/core'
import { IconCpu, IconDoor, IconFolder } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { getCategories, getLabRooms, type Category, type LabRoom } from '../api/catalog.ts'
import ErrorAlert from '../components/ErrorAlert.tsx'
import PageHeader from '../components/PageHeader.tsx'
import InventoryAssetsTab from './catalog/InventoryAssetsTab.tsx'
import InventoryCategoriesTab from './catalog/InventoryCategoriesTab.tsx'
import InventoryLabRoomsTab from './catalog/InventoryLabRoomsTab.tsx'

export default function AdminInventoryPage() {
  const [activeTab, setActiveTab] = useState<string | null>('equipment')
  const [error, setError] = useState<unknown | null>(null)
  const [categories, setCategories] = useState<Category[]>([])
  const [labRooms, setLabRooms] = useState<LabRoom[]>([])
  const [assetsTotal, setAssetsTotal] = useState(0)
  const [reloadSignal, setReloadSignal] = useState(0)

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

  const handleTaxonomyChanged = () => {
    loadLookups()
    setReloadSignal((v) => v + 1)
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
            Equipment Assets ({assetsTotal})
          </Tabs.Tab>
          <Tabs.Tab value="categories" leftSection={<IconFolder size={16} />}>
            Categories ({categories.length})
          </Tabs.Tab>
          <Tabs.Tab value="rooms" leftSection={<IconDoor size={16} />}>
            Lab Rooms ({labRooms.length})
          </Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="equipment">
          <InventoryAssetsTab
            categories={categories}
            labRooms={labRooms}
            reloadSignal={reloadSignal}
            onTotalChange={setAssetsTotal}
            onError={setError}
          />
        </Tabs.Panel>

        <Tabs.Panel value="categories">
          <InventoryCategoriesTab categories={categories} onChanged={handleTaxonomyChanged} />
        </Tabs.Panel>

        <Tabs.Panel value="rooms">
          <InventoryLabRoomsTab labRooms={labRooms} onChanged={handleTaxonomyChanged} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  )
}
