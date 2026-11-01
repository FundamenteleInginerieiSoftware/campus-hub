import { Anchor, Container, Text, Title } from '@mantine/core'
import { Link } from 'react-router'

export default function NotFoundPage() {
  return (
    <Container size="sm" py="xl">
      <Title order={2}>Page not found</Title>
      <Text mb="md">This address does not exist in Campus Hub.</Text>
      <Anchor component={Link} to="/">
        Back to the start page
      </Anchor>
    </Container>
  )
}
