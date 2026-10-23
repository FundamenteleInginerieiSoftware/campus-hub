# ADR-004: Spring events for the reassignment trigger, SQS only as a stretch goal

**Status:** Accepted · **Date:** 2026-10-23 (decided 2026-10-21, see `docs/decisions.md` §4) · **Owner:** Matei (SQS bridge), Calin (local listener)

## Context
When an incident marks an asset as damaged, Stefania's incident module publishes an `AssetDamagedEvent`, and Calin's reassignment engine moves or cancels the future bookings of that asset (Observer pattern). The question is how the event travels from one module to the other.

## Decision
- **Default (dev and prod):** a plain Spring event. Calin's listener uses `@TransactionalEventListener(phase = AFTER_COMMIT)`, so it runs only after the incident is really saved.
- **Stretch goal, only if M2 is done early:** an SQS queue (+ dead-letter queue). Matei's bridge sends the event to SQS after commit, and a consumer calls the same reassignment code.
- A single property picks the path: `app.messaging.sqs.enabled` (default `false`). **Exactly one path runs, never both.**
- Handlers are **idempotent** (running one twice changes nothing), because SQS can deliver a message more than once.

## Why
| | Spring event | SQS |
|---|---|---|
| Extra AWS pieces | none | queue, DLQ, IAM permissions, alarm |
| Works locally | yes | needs AWS or an emulator |
| Survives a crash after commit | no (the event is lost if the app dies at that moment) | yes (the message waits in the queue) |
| Fits one EC2 instance (ADR-002) | yes | possible, but the main benefit (several consumers) doesn't apply |

With one server, the event and the engine run in the same process, so a queue mostly adds parts. The property keeps the door open without changing Calin's or Stefania's code.

## Consequences
- If the app crashes right after an incident is saved, that reassignment does not run. Accepted for the course (the window is a few milliseconds). SQS is the fix, which is why it stays as the stretch goal.
- `AssetDamagedEvent` stays a JSON-serializable record (Stefania), so it can travel through SQS later.
