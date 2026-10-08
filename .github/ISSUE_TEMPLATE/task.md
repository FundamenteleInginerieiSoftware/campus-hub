---
name: Task
about: A concrete piece of work (code, docs, diagram, setup)
title: "[Task] "
labels: task
assignees: ""
---

## Goal

<!-- What should exist when this is done? One or two sentences. -->

## Module

<!-- catalog / reservation / incident / reassignment / user / platform / frontend / docs -->

## Depends on

<!-- Issues or people this waits for, e.g. "#12 (CatalogApi stub)" or "@matei-necula: library X in pom.xml". Write "nothing" if none. -->

## Definition of Done

- [ ] Code on `main` via a reviewed PR
- [ ] CI green
- [ ] Tests for the logic
- [ ] Endpoint visible and documented in Swagger (if any)
- [ ] Works from a fresh DB (delete `backend/data/`)
- [ ] API contract / docs updated
- [ ] Issue closed
