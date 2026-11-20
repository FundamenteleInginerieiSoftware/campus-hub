# Pickup pass, QR code and camera scan (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-20
**Branch / PR:** `feat/user-pickup-pass-qr` (#318), PR 3 of 3
**See also:** `51-pickup-pass-backend.md` (#283), `52-pickup-pass-pages.md` (#284), `docs/superpowers/specs/2026-11-10-pickup-pass-design.md`

---

## Part 1: In simple terms

1. **The student's Pickup pass page now shows a QR code** above the big digits. It changes together with the digits every 30 seconds. The digits stay, for a desk without a working camera.
2. **At the desk**, the Verify window has two tabs: **Type code** (as before, the default) and **Scan QR**. In Scan QR the laptop or phone camera looks at the student's screen.
3. **A right QR** verifies the student at once, like a right typed code: the name and the green check appear.
4. **A QR of another student, or any other QR** (for example a web link) is refused with a short message and the camera keeps looking.
5. **No camera, camera blocked, camera used by another app, or a page that is not https:** the window says so in plain words and goes back to typing the 6 digits.

**Result:** the admin no longer has to read digits aloud, and nothing breaks when the camera does not work.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `pages/PickupPassPage.tsx` | Adds `QRCodeSVG` (`qrcode.react`, from #303) of `pass.qrText`, 220 px, always dark on white. `key={pass.qrText}` redraws it when the code rotates; the existing refresh logic is unchanged |
| `pages/ScanPassPanel.tsx` (new) | `Scanner` of `@yudiel/react-qr-scanner` with `formats={['qr_code']}`, `paused` while the backend checks, error kinds turned into text, and the decoder setup (see below) |
| `pages/ReservationsDeskParts.tsx` | `VerifyStudentDialog` gets a `SegmentedControl` (Type code default / Scan QR); a good scan calls the same `submit` as typing |

No backend, `package.json`, `client.ts` or shared-component change.

### Why it is built this way

| Decision | Why |
|---|---|
| QR payload is `qrText` (`campus-pickup:1:<userId>:<code>`) as the spec says | `parseQrText` (PR 2) accepts only this exact format, so a link or any other QR is ignored |
| The scanned user id must equal the student of the dialog | The dialog verifies one booking's student. A QR of someone else is refused with a message instead of sending a doomed request |
| `paused={busy}` | While the backend checks, the scanner is frozen, so one QR is not sent twice. After a wrong or expired code `busy` ends and scanning resumes |
| Dark on white in the dark theme too | Cameras read inverted QR codes badly |
| Camera needs HTTPS | Browsers give `getUserMedia` only to secure pages (or `localhost`): CloudFront is https, so the deployed app works; plain `http://<ip>` does not, and the dialog says so (`insecure-context`) |
| Typing stays the default | The camera is an extra, the typed flow is the tested one |

### The decoder file and the CDN (found while reading `node_modules`)

`zxing-wasm` 3.1.7 (used by the scanner through `barcode-detector`) builds the address of `zxing_reader.wasm` in `locateFile`: `https://fastly.jsdelivr.net/npm/zxing-wasm@3.1.7/dist/reader/zxing_reader.wasm`. So by default the first scan downloads about 950 KB from jsDelivr. That fails with no internet at the desk (demo) and under a strict Content Security Policy.

Fix, in `ScanPassPanel.tsx`: `prepareZXingModule({ overrides: { locateFile } })` returns our own address of the file, got with `import url from 'zxing-wasm/reader/zxing_reader.wasm?url'`. Vite copies it to `dist/assets/zxing_reader-<hash>.wasm`, served by Spring Boot like the other assets. `zxing-wasm` is not in our `package.json` (it comes with the scanner, same locked version 3.1.7); the import works because the package exports that path. If someone upgrades the scanner to a version that no longer uses `zxing-wasm`, the build fails loudly on this import.

### Tested

| Check | Result |
|---|---|
| `npm run lint && npm run build` | clean; `dist/assets` holds the `.wasm` |
| Manual (to do before merge) | phone shows the QR, laptop reads it in under 3 s; `https://example.com` QR ignored; another student's QR refused; camera blocked shows the message; closing the dialog turns the camera light off; DevTools Network shows no request to jsdelivr |

### Problems and fixes

| Problem | Fix |
|---|---|
| `tsc`: `locateFile` parameters implicitly `any` (the override type is generic) | Typed them `(path: string, prefix: string)` |
| The scanner reads the same QR several times a second, so a refused code (e.g. expired a moment ago) was sent to the backend again and again while the phone stayed in front of the camera | `ScanPassPanel` remembers the last code it sent and skips it; the next rotation (30 s) brings a new code |
| No unit test | The repo has no frontend test runner and the only logic (`parseQrText`) is from PR 2 |

### Left

Calin's "Show pickup pass" shortcut in My reservations (his page). Spring should answer `.wasm` with `application/wasm`; if not, the browser falls back to a slower load (still works).
