# Photos in S3, e-mails through SES (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `feat/aws-s3-ses-adapters` (issue #115)
**See also:** `09-aws-architecture.md` (ADR-002/003), `19-dockerfile.md`

---

## Part 1: In simple terms

1. **On a laptop,** photos are saved in a folder and e-mails are only written to the log (Liviu's versions).
2. **On AWS there was no version at all,** so the app refused to start in "prod" mode.
3. **Now there are AWS versions:** photos go to a private S3 bucket, e-mails go out through Amazon SES. The rest of the app doesn't notice the difference: it asks "store this photo" or "tell this user", and the right version answers.
4. **No passwords or keys are stored for this.** On the AWS server the app gets temporary permissions from the server's role.
5. **If an e-mail can't be sent,** the app writes a warning and carries on. A lost e-mail must not undo a booking change; the student still sees it in "My reservations".
6. **A test starts the whole app in prod mode,** so this mistake can't come back unnoticed.

**Result:** the app starts with `SPRING_PROFILES_ACTIVE=prod`, ready for the first AWS deploy.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `build.gradle` | `platform('software.amazon.awssdk:bom:2.57.0')`, `software.amazon.awssdk:s3`, `software.amazon.awssdk:sesv2` |
| `config/AwsConfig.java` | `@Profile("prod")`: `S3Client` and `SesV2Client` beans, region from `app.aws-region` |
| `storage/service/S3StorageService.java` | `@Profile("prod") StorageService`: put / get / delete in `app.storage.photo-bucket` |
| `notification/service/SesNotificationService.java` | `@Profile("prod") NotificationService`: e-mail to `UserApi.getById(id).email()` from `app.mail.from` |
| `test/.../storage/service/S3StorageServiceTest.java` | 6 Mockito tests |
| `test/.../notification/service/SesNotificationServiceTest.java` | 2 Mockito tests |
| `test/.../config/ProdProfileStartupTest.java` | `@ActiveProfiles("prod")`: the context starts and picks the AWS adapters |

`application-prod.yml` already had `AWS_REGION`, `PHOTO_BUCKET` and `MAIL_FROM` since #13.

### Key choices

| Choice | Why |
|---|---|
| Plain AWS SDK v2 + BOM, not Spring Cloud AWS | Fewer hidden parts to explain; the BOM keeps `s3` and `sesv2` on one version |
| SDK 2.57.0 | Released 19 Oct 2026, so it has been out for a couple of weeks |
| No credentials in code or config | `DefaultCredentialsProvider` (the SDK default) finds the EC2 instance role (ADR-002). It only looks on the first call, so the prod context also starts in tests and on a laptop |
| Liviu's `StorageValidation` reused | Same type, size, magic-byte and key checks as `LocalStorageService`, so dev and prod accept and refuse the same files |
| Explicit request objects (`PutObjectRequest.builder()...`) | Easier to read, and Mockito can capture and check them |
| `load` reads the object into memory | Photos are at most 5 MB |
| `delete` without an existence check | S3 already answers OK for a missing key, which is the contract's "no-op" |
| SES errors caught and logged | Calin's engine calls `notifyUser` inside its transaction; an exception would roll back the reassignment it reports on |
| `notification` → `user.api` | New dependency, allowed (only `api`, no cycle: `user` doesn't use `notification`). The e-mail address has to come from somewhere |

### How it connects

| Who | Uses |
|---|---|
| Calin's `ReassignmentService`, `ReservationLifecycleService` | `NotificationService` (gets SES in prod) |
| Stefania's photo upload (W5) | `StorageService` (gets S3 in prod) |
| Terraform part 1 / 2 | The bucket, the SES identity for `MAIL_FROM`, and the instance-role permissions `s3:PutObject/GetObject/DeleteObject` + `ses:SendEmail` |

**SES sandbox:** a new AWS account can only send to verified addresses until AWS lifts the sandbox. For the demo: verify the team's addresses in SES, or request production access.

### Tests

| Check | Result |
|---|---|
| `./gradlew spotlessApply build --rerun` | BUILD SUCCESSFUL, 357 tests |
| `java -jar` with `SPRING_PROFILES_ACTIVE=prod` and fake env values | `Started CampusHubApplication`, `/actuator/health` UP (before: "required a bean of type NotificationService") |
| Jar size | ~82 MB |
