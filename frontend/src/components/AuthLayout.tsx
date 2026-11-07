import { Box, Group, SimpleGrid, Stack, Text, ThemeIcon, Title } from '@mantine/core'
import { IconFlask } from '@tabler/icons-react'
import type { ReactNode } from 'react'

type Props = {
  title: string
  subtitle: string
  children: ReactNode
}

// Split screen shared by the login and register pages, as in the prototype:
// the form on the left, the blue panel on the right (hidden on phones).
export default function AuthLayout({ title, subtitle, children }: Props) {
  return (
    <SimpleGrid cols={{ base: 1, md: 2 }} spacing={0} mih="100vh">
      <Stack justify="center" p={40} maw={440} w="100%" mx="auto">
        <Group gap="xs" mb="md">
          <ThemeIcon size={28} radius="md">
            <IconFlask size={16} />
          </ThemeIcon>
          <div>
            <Text fw={700} lh={1.2}>
              Campus Hub
            </Text>
            <Text size="xs" c="dimmed">
              Lab equipment desk
            </Text>
          </div>
        </Group>

        <Title order={2}>{title}</Title>
        <Text c="dimmed" mb="sm">
          {subtitle}
        </Text>

        {children}
      </Stack>

      <Box visibleFrom="md" bg="campus.6" c="white" p={48} style={{ display: 'grid', alignContent: 'end' }}>
        <Title order={2} c="white" mb="sm">
          Book the bench. Report what breaks.
        </Title>
        <Text maw={520} opacity={0.9}>
          Oscilloscopes, kits, headsets and cameras from three labs, in 30-minute slots up to 14 days ahead. If an item
          is reported broken, every upcoming booking moves to a matching item on its own.
        </Text>
      </Box>
    </SimpleGrid>
  )
}
