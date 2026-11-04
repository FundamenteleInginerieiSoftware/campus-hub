# Local Disk Photo Storage & Security Hardening (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-11-02
**Branch / PR:** `feat/storage-local-disk`
**See also:** `01-shared-foundations.md` (W3 `StorageService` stub), Issue #71 (security checklist)

---

## Part 1: In simple terms

When a student reports broken lab equipment, they can attach a photo. Until now, the app only had a temporary placeholder that logged a message without saving any file.

1. **Real local disk storage:** `LocalStorageService` now saves uploaded photos inside the local `./uploads` folder (created automatically on startup) under a random UUID name (`<uuid>.jpg`, `<uuid>.png`, or `<uuid>.webp`), reads them back when displayed, and deletes them if needed.
2. **Security checks built in:**
   - Only real **JPEG, PNG, and WebP** images are accepted (both the declared type and the first binary bytes of the file are checked, blocking renamed HTML/JavaScript files).
   - Files over **5 MB** are rejected even if the caller reports a smaller size.
   - File names are never taken from the user, and reading or deleting checks both a strict UUID pattern and folder containment so `../../` tricks can never escape `./uploads`.
   - Missing photos now properly return **404 Not Found** (`PHOTO_NOT_FOUND`) instead of an empty 0-byte response.

---

## Part 2: Technical details

### Files

| File | Purpose |
|---|---|
| `common/error/BadRequestException.java`, `PayloadTooLargeException.java` | Shared `AppException` subclasses mapped to `400` and `413` RFC 9457 `ProblemDetail` responses |
| `storage/api/StorageService.java` | Keeps `store`, `load`, `delete` signatures stable and adds `StorageService.contentTypeForKey(key)` so photo controllers derive the exact `image/*` type without guessing |
| `storage/service/StorageValidation.java` | Shared allow-lists, 5 MB bounded stream reader, magic-byte inspection, and `<uuid>.<ext>` regex reusable by Matei's `S3StorageService` (`@Profile("prod")`) |
| `storage/service/LocalStorageService.java` | `@Profile("!prod")` local disk implementation rooted at `${app.storage.local-dir:./uploads}` (resolved via `toRealPath()`) |
| `storage/service/LocalStorageServiceTest.java` | JUnit 5 `@TempDir` tests covering JPEG/PNG/WebP roundtrips, spoofed HTML magic bytes, path traversal, >5 MB streams, missing keys (`404`), and idempotent delete |
| `notification/service/LoggingNotificationServiceTest.java` | Unit test for the `!prod` logging notification adapter |

### Security choices (#71)

| Threat / Issue | Mitigation |
|---|---|
| Path traversal in `store`, `load`, `delete` | Extension derived from verified `contentType`; `load`/`delete` enforce `^[0-9a-f-]{36}\.(jpg\|jpeg\|png\|webp)$` **and** `rootDir.resolve(key).normalize().startsWith(rootDir)` |
| Stored XSS (`text/html`, `image/svg+xml`, renamed `.html`) | Strict allow-list (`image/jpeg`, `image/png`, `image/webp`) + binary magic-byte check (`FF D8 FF`, `\x89PNG\r\n\x1a\n`, `RIFF....WEBP`) |
| Unbounded stream exhausting disk | `in.readNBytes(MAX_PHOTO_BYTES + 1)` rejects >5 MB with `413 PHOTO_TOO_LARGE` before writing to disk |
| Missing `app.storage.local-dir` in `test` profile | `@Value("${app.storage.local-dir:./uploads}")` provides a safe default |
| Content-Type on `GET /incidents/{id}/photo` | `StorageService.contentTypeForKey(key)` maps `.jpg`/`.png`/`.webp` back to `image/jpeg` / `image/png` / `image/webp` while keeping `Resource load(String key)` unchanged |
