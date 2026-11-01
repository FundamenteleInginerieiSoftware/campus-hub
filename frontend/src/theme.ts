import { createTheme, type CSSVariablesResolver, type MantineColorsTuple } from '@mantine/core'

// One place for the look of the whole app, taken from the redesigned prototype
// (docs/prototype/campus-hub-redesign.html). Change colors and fonts here, not in single pages.

// Shades of the prototype's accent blue (#1f5fd0 is index 6, the one Mantine uses for buttons).
const campus: MantineColorsTuple = [
  '#e3ecfc',
  '#c9d9f8',
  '#a3bff2',
  '#7aa3ec',
  '#5487e2',
  '#3672d9',
  '#1f5fd0',
  '#1a51b3',
  '#163f8f',
  '#10306d',
]

export const theme = createTheme({
  primaryColor: 'campus',
  colors: { campus },
  defaultRadius: 'md',
  fontFamily: '"Google Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: '"Google Sans Code", ui-monospace, Menlo, Consolas, monospace',
  headings: { fontFamily: '"Google Sans", system-ui, -apple-system, "Segoe UI", sans-serif', fontWeight: '600' },
})

// Page background and surfaces from the prototype, for light and dark mode.
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-body': '#f4f6fa',
    '--app-surface': '#ffffff',
  },
  dark: {
    '--mantine-color-body': '#0d1320',
    '--app-surface': '#141c2b',
  },
})
