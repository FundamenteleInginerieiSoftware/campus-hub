# Fix: missing photos on S3, slow or failing e-mails (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `fix/aws-s3-ses-hardening` (issue #148 part 1, issue #171 point 3)
**See also:** `23-s3-ses-adapters.md` (the adapters this fixes), `06-async-scheduling.md` (`@Async`)

---

## Part 1: In simple terms

1. **A missing photo looked like a crash on AWS.** S3 only says "this photo doesn't exist" to a server that is allowed to list the bucket. Without that permission it says "access denied", and the student got a generic error.
2. **The real fix is one more permission** (`s3:ListBucket`) for the AWS server. It is now written in the permission list that Terraform will create.
3. **Until then, the log says exactly what is wrong,** so whoever reads it knows which permission to add. We don't pretend the photo is missing: "access denied" can also mean a wrong bucket, and hiding that would make it invisible.
4. **An e-mail to a user that no longer exists stopped the work around it.** Now it is just one more e-mail that couldn't be sent: a warning in the log, and the booking change goes on.
5. **A slow e-mail service made the reassignment wait.** Each e-mail could wait up to 20 seconds, so 10 moved bookings could take minutes. Now e-mails are handed to a background worker and the reassignment continues at once.

**Result:** a missing photo on AWS has a clear cause in the log, and e-mails can never slow down or break a booking change.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `storage/service/S3StorageService.java` | `load`: `S3Exception` with status 403 → `log.error` naming `s3:ListBucket`, then rethrown (still 500). `NoSuchKeyException` → 404 `PHOTO_NOT_FOUND` as before |
| `notification/service/SesNotificationService.java` | `@Async` on `notifyUser`; `userApi.getById` moved inside the `try`; catch `RuntimeException` instead of only `SdkException` |
| `documentation/Necula_Matei/23-s3-ses-adapters.md` | Planned instance-role permissions: `s3:ListBucket` on the bucket ARN added |
| `docs/adr/002-single-ec2-behind-cloudfront.md` | Same permission in the "Permissions" row |
| `test/.../storage/service/S3StorageServiceTest.java` | +1 test: 403 is rethrown and the log names `s3:ListBucket` |
| `test/.../notification/service/SesNotificationServiceTest.java` | +2 tests: unknown user doesn't throw and sends nothing; `notifyUser` is `@Async` |
| `test/.../config/ProdProfileStartupTest.java` | The prod `NotificationService` bean is a Spring proxy (so `@Async` is really applied) |

### Key choices

| Choice | Why |
|---|---|
| IAM fix, not a code workaround | With `s3:ListBucket` on `arn:aws:s3:::<bucket>` (the bucket, not `/*`), S3 answers `404 NoSuchKey` and the existing 404 mapping works. Terraform part 2 must include it |
| 403 rethrown, not mapped to 404 | A 403 can also mean a wrong bucket name, a missing `GetObject` or a broken role. Turning it into "photo not found" would hide those. One `log.error` with the likely cause is the simplest useful thing |
| `@Async` instead of shorter SES timeouts | Shorter timeouts only make the wait smaller; `@Async` removes it from the caller. `@EnableAsync` and the pool already exist (`AsyncConfig`, `spring.task.execution`), so it is one annotation |
| Pool: 2 core threads, unbounded queue (Spring Boot default) | An e-mail is never refused, only sent later. Calin's listener uses one thread, e-mails the other. E-mails still in the queue when the app stops are lost; the booking change is saved, so "My reservations" still shows it |
| Catch `RuntimeException` in `notifyUser` | An unknown user (`NotFoundException`), an SES error or anything else is one failed e-mail with a clear warning, not an uncaught error in the async thread |

```java
@Async
@Override
public void notifyUser(Long userId, String subject, String body) {
    try {
        String to = userApi.getById(userId).email();   // was outside the try (#148)
        ...
        ses.sendEmail(request);
    } catch (RuntimeException e) {
        log.warn("E-mail not sent: userId={}, subject={}: {}", userId, subject, e.getMessage());
    }
}
```

**`@Async` rules that matter here:** it only works through Spring's proxy, so a call from inside the same class would run synchronously (nobody does that: Calin calls it through the `NotificationService` bean). On the background thread there is no caller transaction; `UserApi.getById` opens its own read-only one, so that is fine. In tests (`test` profile) `AsyncConfig` uses `SyncTaskExecutor`, so `@Async` methods run at once.

### How it connects

| Who | What changes for them |
|---|---|
| Calin's `ReassignmentService`, `ReservationLifecycleService` | `notifyUser` returns at once in prod; their `sendNotificationSafely` / `notifySafely` wrappers stay as a second safety net |
| Stefania's incident photos (`StorageService.load`) | Same 404 `PHOTO_NOT_FOUND` for a missing photo once `s3:ListBucket` is granted; a clear log line if it isn't |
| Liviu's `StorageService` / `NotificationService` interfaces and dev versions | Unchanged |
| Terraform part 2 (me) | Instance role: `s3:PutObject/GetObject/DeleteObject` on `<bucket-arn>/*`, `s3:ListBucket` on `<bucket-arn>`, `ses:SendEmail` |

### Tests

| Check | Result |
|---|---|
| The 3 new tests before the fix | All 3 fail (no `s3:ListBucket` in the log, `NotFoundException` thrown, no `@Async`) |
| `./gradlew spotlessApply build --rerun` (PostgreSQL 18) | BUILD SUCCESSFUL, 390 tests, 0 failures |

### Bugs and fixes

| Bug | Fix |
|---|---|
| Missing key without `s3:ListBucket` → 403 → 500 with no hint (#148) | Permission added to the planned IAM policy; 403 logged with the likely cause and rethrown |
| `userApi.getById` outside the `try` → unknown user threw out of `notifyUser` (#148) | Lookup moved inside the `try` |
| Synchronous SES: up to 20 s per e-mail on the caller's thread (#171 point 3) | `@Async` on `notifyUser` |
