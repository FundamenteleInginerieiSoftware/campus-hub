# Pickup pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A student shows a 6-digit code that changes every 30 s; the admin types (later scans) it on the Reservations desk and sees who the student is.

**Architecture:** Everything lives in Matei's `user` module. `PickupCodes` is a pure function (HMAC-SHA256 over user id + time step, cut to 6 digits); `PickupPassService` adds the shared `Clock`, the account check and the audit row; two thin controllers expose it. No table, no migration, no `SecurityConfig` change. The frontend gets one student page and one dialog on the desk page.

**Tech Stack:** Spring Boot 4.1.1, Java 25, `javax.crypto.Mac` (JDK), JUnit 5 + AssertJ + Mockito, `@WebMvcTest`, Gradle; React 19 + Mantine 9 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-10-pickup-pass-design.md`

## Global Constraints

- Packages: `com.campus.user.service`, `com.campus.user.web` only. Never edit a teammate's file (AGENTS.md §3): not `ReservationService`, not `AdminAuditLogPage.tsx`, not `package.json`. `App.tsx` and `layout/navigation.ts` change only in a PR Stefania reviews.
- Constants exactly: `STEP_SECONDS = 30`, `DIGITS = 6`, `PURPOSE = "pickup-v1"`; message `"pickup-v1|" + userId + "|" + timeStep`; key = UTF-8 bytes of `app.jwt.secret`, algorithm `HmacSHA256`.
- Accepted steps on verify: current and current − 1. Nothing else.
- QR text exactly `campus-pickup:1:<userId>:<code>`.
- Error: `BusinessRuleException("PICKUP_CODE_INVALID", "The code is wrong or has expired")` (409) for every failed verify. Audit action `PICKUP_PASS_VERIFIED`, entity type `USER`.
- "Now" only through the injected `Clock` (`clock.instant()`); never `Instant.now()`.
- DTOs are `record`s in `web/`. `@Transactional` on public service methods only. Constructor injection.
- Tests: one rule per test, every assertion `.as("<rule>; see <Class.method>")`, fixed `Clock`.
- Comments only for hidden behaviour, *why*, and links to teammates' code. Commits as `matei-necula`, Conventional Commits, **no AI trailer**. Each PR ships its personal doc (`documentation/Necula_Matei/NN-…md`, next free number) + rows in `02-files-explained.md` and the docs `README.md`.
- Before each push: `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL`; frontend `npm run lint && npm run build`.

## Review Focus

1. **A code with leading zeros** (`"004211"`) → must stay 6 characters end to end. If any layer treats it as a number it becomes `4211` and verify fails. Tests: `PickupCodesTest.alwaysSixDigits` (Task 1), `PickupPassControllerTest.codeIsAJsonString` (Task 3); frontend types `code` as `string` (Task 4).
2. **Typed exactly at the step change** (student saw the code at second 29, admin submits at second 31) → accepted. Test: `PickupPassServiceTest.acceptsPreviousStepCode` (Task 2).
3. **Phone screen went dark and came back** → the page must not show a stale code with a frozen ring. Refetch on `visibilitychange`; manual check in Task 4, Step 5.
4. **Admin types `493 027` or pastes with a space** → the dialog strips whitespace before sending; the backend stays strict (`\d{6}`). Test: `AdminPickupPassControllerTest.rejectsCodeWithSpace` (Task 3) + manual check (Task 4).
5. **Verify for a user id that does not exist, or a disabled student** → same 409 as a wrong code, no 404 and no 500. Tests: `PickupPassServiceTest.rejectsUnknownUser`, `rejectsDisabledUser` (Task 2).

---

### Task 1: `PickupCodes` (the formula)

**Files:**
- Create: `backend/src/main/java/com/campus/user/service/PickupCodes.java`
- Test: `backend/src/test/java/com/campus/user/service/PickupCodesTest.java`

**Interfaces:**
- Produces: `final class PickupCodes { PickupCodes(String secret); String code(long userId, long timeStep); static long timeStep(long epochSecond); static int validForSeconds(long epochSecond); static String qrText(long userId, String code); static final int STEP_SECONDS = 30; }`. Not a Spring bean; `PickupPassService` creates it from `app.jwt.secret`.

- [ ] **Step 1: Write the failing tests** (plain JUnit, `SECRET = "test-secret-test-secret-test-secret-0123"`):
  - `sameInputGivesSameCode`: `code(12, 100)` twice → equal.
  - `codeChangesWithTheStep`: over steps `100..119`, `code(12, step)` gives at least 19 different values.
  - `codeDiffersPerUser`: over users `1..20` at step 100 → at least 19 different values.
  - `codeDiffersPerSecret`: other secret, same user and step → different code.
  - `alwaysSixDigits`: for steps `0..1999`, user 7 → every code matches `\d{6}`; at least one starts with `0` (proves the padding is exercised).
  - `timeStepIsEpochSecondDividedByThirty`: `timeStep(0) == 0`, `timeStep(29) == 0`, `timeStep(30) == 1`.
  - `validForSecondsCountsDownToOne`: `validForSeconds(60) == 30`, `validForSeconds(70) == 20`, `validForSeconds(89) == 1`.
  - `qrTextFormat`: `qrText(12, "004211")` → `"campus-pickup:1:12:004211"`.
  - `pinnedValue`: `code(12, 100)` with `SECRET` equals the literal printed by the first green run (comment: guards the formula; if it changes, old screenshots in the docs are wrong too).
- [ ] **Step 2: Run** `./gradlew test --tests PickupCodesTest` → FAIL (class missing).
- [ ] **Step 3: Implement.** `Mac.getInstance("HmacSHA256")` with a `SecretKeySpec` built once in the constructor; a new `Mac` per call (`Mac` is not thread-safe; comment it). Number = `ByteBuffer.wrap(mac).getInt() & 0x7fffffff`; code = `String.format("%06d", number % 1_000_000)`. Comment at `PURPOSE`: why the label keeps a pickup code apart from a login token signed with the same secret (spec §2 row 4).
- [ ] **Step 4: Run** → PASS; copy the printed value into `pinnedValue`, run again.
- [ ] **Step 5: Commit** `feat(user): add rotating pickup code formula`.

### Task 2: `PickupPassService`

**Files:**
- Create: `backend/src/main/java/com/campus/user/service/PickupPassService.java`
- Create: `backend/src/main/java/com/campus/user/web/{PickupPassResponse,PickupPassVerifiedResponse}.java`
- Test: `backend/src/test/java/com/campus/user/service/PickupPassServiceTest.java`

**Interfaces:**
- Consumes: `PickupCodes` (Task 1); `UserRepository.findById`; `CurrentUser.id()`; `AuditService.record(Long actorId, String action, String entityType, Long entityId, String details)`; `Clock`.
- Produces:
  - `record PickupPassResponse(String code, String qrText, int validForSeconds)`
  - `record PickupPassVerifiedResponse(Long userId, String fullName, String email)`
  - `@Service class PickupPassService { PickupPassService(@Value("${app.jwt.secret}") String secret, UserRepository users, CurrentUser currentUser, AuditService audit, Clock clock); PickupPassResponse currentPass(); PickupPassVerifiedResponse verify(Long userId, String code); }`

- [ ] **Step 1: Write the failing tests** (Mockito; `Clock.fixed(Instant.ofEpochSecond(T), ZoneId.of("Europe/Bucharest"))` with `T = 3_000_010`, so second 10 of step `100_000`; a helper builds an enabled `User` and one disabled through `user.disable()`):
  - `currentPassIsForTheLoggedInUser`: `currentUser.id() == 12` → `code` equals `new PickupCodes(SECRET).code(12, 100_000)`, `qrText` ends with that code, `validForSeconds == 20`.
  - `secondsLeftAtStepStartIsThirty` (`T = 3_000_000`) and `secondsLeftAtLastSecondIsOne` (`T = 3_000_029`).
  - `acceptsCurrentCode`: returns the student's `fullName` and `email`.
  - `acceptsPreviousStepCode`: code of step `99_999` → accepted.
  - `rejectsCodeTwoStepsOld` (step `99_998`), `rejectsNextStepCode` (step `100_001`), `rejectsWrongCode` (`"000000"`, after checking it is not by chance the right one): `BusinessRuleException` with code `PICKUP_CODE_INVALID`.
  - `rejectsUnknownUser` (`findById` empty) and `rejectsDisabledUser`: same exception and code.
  - `recordsAuditOnSuccess`: `verify(audit).record(ADMIN_ID, "PICKUP_PASS_VERIFIED", "USER", 12L, <details containing the full name>)`.
  - `noAuditOnFailure`: `verifyNoInteractions(audit)` after a rejected code.
- [ ] **Step 2: Run** `./gradlew test --tests PickupPassServiceTest` → FAIL.
- [ ] **Step 3: Implement.** `currentPass()` `@Transactional(readOnly = true)` is not needed (no database call): leave it without. `verify` is `@Transactional` (reads the user, writes the audit row). Compare with `MessageDigest.isEqual` on the UTF-8 bytes (comment: same time for a right and a wrong code). Check the account first, then the code, but answer the same exception for both (comment: one answer, so the desk can't be used to list accounts).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(user): add pickup pass service with desk verification`.

### Task 3: Controllers, contract, PR 1

**Files:**
- Create: `backend/src/main/java/com/campus/user/web/{PickupPassController,AdminPickupPassController,VerifyPickupPassRequest}.java`
- Test: `backend/src/test/java/com/campus/user/web/{PickupPassControllerTest,AdminPickupPassControllerTest}.java` (same setup as `UserControllerTest`: `@WebMvcTest` + `@Import(SecurityConfig.class)` + `@MockitoBean PickupPassService`)
- Modify: `docs/api-contract.md` (new section "Pickup pass (Matei)": both endpoints, JSON examples, `PICKUP_CODE_INVALID` 409)
- Create: `documentation/Necula_Matei/NN-pickup-pass-backend.md`; modify `02-files-explained.md`, docs `README.md`

**Interfaces:**
- Consumes: `PickupPassService` (Task 2).
- Produces: `GET /api/v1/users/me/pickup-pass` → `PickupPassResponse`; `POST /api/v1/admin/pickup-pass/verify` with `record VerifyPickupPassRequest(@NotNull Long userId, @NotNull @Pattern(regexp = "\\d{6}") String code)` → `PickupPassVerifiedResponse`.

- [ ] **Step 1: Write the failing tests:**
  - `PickupPassControllerTest`: `requiresLogin` (401, `$.code == "UNAUTHORIZED"`); `returnsThePass` (`@WithMockUser`, `$.code == "004211"`, `$.validForSeconds == 17`); `codeIsAJsonString` (`$.code` `isString()`).
  - `AdminPickupPassControllerTest`: `studentGets403` (`@WithMockUser(roles = "STUDENT")`); `verifiedReturnsTheStudent` (`roles = "ADMIN"`, `$.fullName`); `invalidCodeIs409` (service throws → `$.code == "PICKUP_CODE_INVALID"`, body in the assertion message); `rejectsFiveDigits`, `rejectsLetters`, `rejectsCodeWithSpace` (`"493 027"`), `rejectsMissingUserId` → 400 `VALIDATION_FAILED`, service never called.
- [ ] **Step 2: Run** both test classes → FAIL.
- [ ] **Step 3: Implement** two `@RestController`s (`@Tag(name = "Pickup pass")`, `@Operation` + `@ApiResponse` for 200/400/409 like `ProjectController`). Class comment on the admin one: the `/api/v1/admin/**` rule in `SecurityConfig` is what keeps students out.
- [ ] **Step 4: Run** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL` (ArchUnit admin-prefix rules included).
- [ ] **Step 5: Real app check:** `bootRun`, log in as `student1@campus.test`, `GET /api/v1/users/me/pickup-pass`; log in as admin, `POST /api/v1/admin/pickup-pass/verify` with that code → 200 with the name; wait 70 s, same code → 409; `GET /api/v1/admin/audit-logs?action=PICKUP_PASS_VERIFIED` shows one row.
- [ ] **Step 6:** Write the api-contract section and the personal doc (Part 1: what the pass is, like a bank authenticator; Part 2: files table, the formula and each constant's *why*, the security table from spec §8, tests + results, the real-app check). Commit `feat(user): expose pickup pass endpoints`, push `feat/user-pickup-pass`, `gh pr create --base main --title "feat(user): add rotating pickup pass for the lab desk"`.

### Task 4: Student page + Verify dialog on the desk (PR 2)

**Files:**
- Create: `frontend/src/api/pickupPass.ts`, `frontend/src/pages/PickupPassPage.tsx`
- Modify: `frontend/src/pages/ReservationsDeskPage.tsx` (Verify button in the "To pick up" rows, verified mark in the Student column), new `frontend/src/pages/ReservationsDeskParts.tsx` (the dialog, so the page file doesn't grow)
- Modify (Stefania reviews): `frontend/src/App.tsx` (route `pickup-pass`), `frontend/src/layout/navigation.ts` (entry `{ label: 'Pickup pass', path: '/pickup-pass', icon: IconQrcode }` under "Mine")
- Create: `documentation/Necula_Matei/NN-pickup-pass-pages.md`, screenshots `docs/screenshots/pickup-0*.png`

**Interfaces:**
- Consumes: the two endpoints (Task 3); `apiFetch`, `ApiError` from `client.ts`; `Reservation.userId` from `reservations.ts`.
- Produces: `type PickupPass = { code: string; qrText: string; validForSeconds: number }`; `type VerifiedStudent = { userId: number; fullName: string; email: string }`; `fetchPickupPass(): Promise<PickupPass>`; `verifyPickupPass(userId: number, code: string): Promise<VerifiedStudent>`; `parseQrText(text: string): { userId: number; code: string } | null` (accepts only `^campus-pickup:1:(\d+):(\d{6})$`).

- [ ] **Step 1:** Write `pickupPass.ts` with the three functions and JSDoc naming the endpoints.
- [ ] **Step 2:** `PickupPassPage.tsx`: load on mount; a 1-second interval lowers the local seconds; at 0 fetch again; `document.addEventListener('visibilitychange', …)` fetches when the tab is visible again; clean both up on unmount. Digits shown as `493 027`, Mantine `RingProgress` for the seconds, `ErrorAlert` + "Try again" on failure, spinner stopped in `finally`.
- [ ] **Step 3:** Desk: `VerifyStudentDialog` in `ReservationsDeskParts.tsx` (Mantine `Modal` + `PinInput length={6} type="number"`); strips whitespace; on success calls `onVerified(student)`; on `PICKUP_CODE_INVALID` shows "The code is wrong or has expired. Ask the student for the new one." and clears the input. The page keeps `verified: Record<number, VerifiedStudent>` and shows `fullName` + a green check in the Student column for every row of that user.
- [ ] **Step 4:** Add the route and the menu entry.
- [ ] **Step 5: Manual check** (backend + `npm run dev`, phone on the same network or a second browser window): the code refreshes at 0; switching tabs for 40 s and back shows a fresh code; typing it on the desk shows the name; an old code shows the error; pasting `493 027` works.
- [ ] **Step 6:** `npm run lint && npm run build` → clean. Screenshots, personal doc, commit `feat(frontend): add pickup pass page and desk verification`, push `feat/frontend-pickup-pass`, open the PR and ask Stefania for the review.

### Task 5: QR code + camera scan (PR 3, after Stefania adds the two libraries)

**Files:**
- Modify: `frontend/src/pages/PickupPassPage.tsx` (QR above the digits), `frontend/src/pages/ReservationsDeskPage.tsx` + `ReservationsDeskParts.tsx` (Scan pass), `frontend/src/api/reservationDesk.ts` (`DeskSearch.userId?: number` → `params.set('userId', …)`)
- Create: `documentation/Necula_Matei/NN-pickup-pass-qr.md`

**Interfaces:**
- Consumes: `parseQrText`, `verifyPickupPass` (Task 4); the QR drawing component and the QR decode function of the libraries Stefania added; `GET /api/v1/admin/reservations?userId=` (Calin's existing filter).

- [ ] **Step 1:** Pass page: render `qrText` as a QR code (at least 220 px, quiet zone on, dark-on-white also in the dark theme: cameras read inverted codes badly).
- [ ] **Step 2:** `ScanPassDialog`: `navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })` into a `<video>`, a frame every 250 ms drawn on a canvas and given to the decoder; the first text that `parseQrText` accepts → `verifyPickupPass`; stop every camera track on close and on unmount. No camera or permission denied → message "Camera not available. Use the 6-digit code." (the dialog from Task 4 stays the fallback).
- [ ] **Step 3:** After a successful scan the desk sets the `userId` filter and shows the bar "Showing <fullName>'s bookings · Clear".
- [ ] **Step 4: Manual check:** phone shows the QR, laptop camera reads it in under 3 s in normal room light; a QR that is not ours ("https://example.com") is ignored; closing the dialog turns the camera light off.
- [ ] **Step 5:** `npm run lint && npm run build`; screenshots; doc; commit `feat(frontend): add QR pickup pass and camera scan`; PR.
