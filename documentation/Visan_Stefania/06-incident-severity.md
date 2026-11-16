# Only Serious Incidents Take Equipment Out of Use (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-11-16
**Package:** `com.campus.incident`
**PR:** #263 (part 1 of #229; agreed with Matei and Calin in the issue)

---

## Part 1: In simple terms

**The problem (found by Matei):** every report took the item out of use at once, even a **Low** one ("a scratch, it still works"). Everyone's bookings on it were moved or cancelled. One student could do this to every item in the lab, one report after another.

**The new rule:**

| Report | What happens to the item |
|---|---|
| **High / Critical** | Out of use at once, bookings are moved (same as before) |
| **Low / Medium** | Stays in use. It is a ticket for the admin |
| Admin presses **Start repair** on a Low/Medium one | Now it goes out of use and the bookings are moved: the admin confirmed it is really broken |

**A limit:** a student can have at most **3 reports waiting** (`OPEN`) at a time. Admins have no limit.

**One rule for "is the item still blocked?":** an incident blocks its item while it is **in repair**, or while it is **open and High/Critical**. When the admin closes the last such incident, the item is usable again, even if a Low ticket is still open on it.

---

## Part 2: Technical details

### Where the rule lives

| Piece | What it does |
|---|---|
| `IncidentSeverity.takesAssetOutOfUseAtOnce()` | `true` for `HIGH`, `CRITICAL` |
| `IncidentReport.keepsAssetOutOfUse()` | `IN_REPAIR`, or `OPEN` with a severity above |
| `IncidentReportRepository.existsIncidentKeepingAssetOutOfUse(assetId)` | same rule as a query: is any incident still blocking the asset? |
| `IncidentReportRepository.countByReportedByIdAndStatus` | open reports of one student, for the limit |

### What each action does now

| Action | Before #263 | After #263 |
|---|---|---|
| `reportIncident` HIGH/CRITICAL | lock + `markUnderMaintenance` + `AssetDamagedEvent` | unchanged |
| `reportIncident` LOW/MEDIUM | same as HIGH | `lockForBooking` + refuse `RETIRED` (409 `ASSET_RETIRED`), no status change, no event |
| any report by a student with 3 `OPEN` | allowed | 409 `TOO_MANY_OPEN_INCIDENTS`, checked before the asset is touched |
| `startRepair` on LOW/MEDIUM | nothing on the asset | lock; an `AVAILABLE` asset → `UNDER_MAINTENANCE` + `AssetDamagedEvent` (reporter's id) |
| resolve / reject | release the asset if no `OPEN`/`IN_REPAIR` incident left | release only if this incident blocked it, and only if no other incident still blocks it |

The asset lock from #164 stays for LOW/MEDIUM too, so a report and a resolve on the same asset still run one after the other.

### Tests

- `IncidentReportTest`: every status × severity combination for `keepsAssetOutOfUse`, and `takesAssetOutOfUseAtOnce` per severity.
- `IncidentServiceTest` (`OnlyHighAndCriticalTakeTheAssetOutOfUse`): LOW/MEDIUM keep the asset, CRITICAL takes it out, retired and missing assets, the limit at 2 and 3, admin without limit, start repair on LOW (available / already under maintenance / retired), closing an open LOW does not touch the asset, closing a started LOW releases it.
- `IncidentControllerTest`: 409 `TOO_MANY_OPEN_INCIDENTS`, admin flag passed to the service.
- `IncidentFlowIT` (real PostgreSQL): LOW stays available until start repair; rejecting a MEDIUM leaves it available; an open LOW ticket does not block the asset after the HIGH one is resolved; the limit of 3 per student.

### Defense questions

- **Why not just trust the student's severity?** A student can still lie and pick HIGH. The limit of 3 open reports bounds the damage, every report is in the audit log, and the admin can reject it at once.
- **Why does Start repair publish the same event?** For the reassignment engine nothing is different: the asset just became unusable. One event, one listener, no new code in Calin's module.
- **Part 2 of #229 (disabled users):** Calin added `ReservationApi.cancelUpcomingReservationsForUser` (#266). The call from user admin goes through an event (`UserDisabledEvent`), because `reservation` already depends on `user` and a direct call would make a cycle.
