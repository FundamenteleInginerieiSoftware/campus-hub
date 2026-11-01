import {
  ActionIcon,
  AppShell,
  Avatar,
  Burger,
  Group,
  NavLink,
  Stack,
  Text,
  ThemeIcon,
  Tooltip,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconFlask, IconLogout, IconMoon, IconSun } from '@tabler/icons-react'
import { Link, Outlet, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth.ts'
import { menuFor, titleFor } from './navigation.ts'

function initials(fullName: string): string {
  return fullName
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0].toUpperCase())
    .join('')
}

// The frame around every page after login: side menu on the left, top bar, page in the middle
// (<Outlet /> is where React Router puts the current page). Same structure as the prototype.
export default function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const [menuOpen, { toggle: toggleMenu, close: closeMenu }] = useDisclosure(false)
  const { setColorScheme } = useMantineColorScheme()
  const colorScheme = useComputedColorScheme('light')

  if (!user) {
    return null
  }

  return (
    // layout="alt": the side menu runs the full height and the top bar sits to its right, as in the prototype.
    <AppShell
      layout="alt"
      header={{ height: 52 }}
      navbar={{ width: 236, breakpoint: 'sm', collapsed: { mobile: !menuOpen } }}
      padding="lg"
    >
      <AppShell.Header px="lg">
        <Group h="100%" gap="sm">
          <Burger opened={menuOpen} onClick={toggleMenu} hiddenFrom="sm" size="sm" aria-label="Menu" />
          <Text fw={600}>{titleFor(location.pathname)}</Text>
          <Tooltip label={colorScheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
            <ActionIcon
              ml="auto"
              variant="default"
              size="lg"
              aria-label="Toggle color theme"
              onClick={() => setColorScheme(colorScheme === 'dark' ? 'light' : 'dark')}
            >
              {colorScheme === 'dark' ? <IconSun size={18} /> : <IconMoon size={18} />}
            </ActionIcon>
          </Tooltip>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" bg="var(--app-surface)">
        <AppShell.Section>
          <Group gap="xs" px="xs" pb="md" wrap="nowrap">
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
        </AppShell.Section>

        <AppShell.Section grow>
          {menuFor(user.role).map((group) => (
            <Stack key={group.title} gap={2} mb="sm">
              <Text size="xs" fw={600} c="dimmed" px="xs" pt="xs">
                {group.title}
              </Text>
              {group.items.map((item) => (
                <NavLink
                  key={item.path}
                  component={Link}
                  to={item.path}
                  label={item.label}
                  leftSection={<item.icon size={16} />}
                  active={location.pathname.startsWith(item.path)}
                  onClick={closeMenu}
                  style={{ borderRadius: 'var(--mantine-radius-md)' }}
                />
              ))}
            </Stack>
          ))}
        </AppShell.Section>

        <AppShell.Section>
          <Group
            gap="sm"
            p="xs"
            wrap="nowrap"
            style={{ border: '1px solid var(--mantine-color-default-border)', borderRadius: 'var(--mantine-radius-md)' }}
          >
            <Avatar radius="xl" size={32} color="campus">
              {initials(user.fullName)}
            </Avatar>
            <div style={{ minWidth: 0, flex: 1 }}>
              <Text size="sm" fw={600} truncate>
                {user.fullName}
              </Text>
              <Text size="xs" c="dimmed">
                {user.role === 'ADMIN' ? 'Administrator' : 'Student'}
              </Text>
            </div>
            <Tooltip label="Log out">
              <ActionIcon variant="subtle" color="gray" onClick={logout} aria-label="Log out">
                <IconLogout size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
