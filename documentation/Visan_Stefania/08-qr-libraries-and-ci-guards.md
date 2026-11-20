# QR Libraries for the Pickup Pass and Honest CI Steps (Vișan Ștefania)

**Role:** Member 4 (frontend lead, CI)
**Date:** 2026-11-17
**Files:** `frontend/package.json`, `frontend/package-lock.json`, `.github/workflows/ci.yml`
**Issues:** Refs #291 (point 2: small frontend asks from teammates' features), Refs #269 (point 2: `ci.yml` guards)

---

## Part 1: In simple terms

**Two QR libraries for Matei's pickup pass.** At the lab desk a student shows a code on their phone. Matei's last pickup-pass step adds a QR code to that screen, which the admin scans with the laptop camera instead of typing six digits. The frontend needs two ready-made building blocks for this: one that **draws** a QR code and one that **reads** it from the camera. `package.json` is mine (frontend lead), so I add them. Matei will use them in his own pages. This PR only adds them and does not use them yet.

**CI that can't pass by doing nothing.** CI is the robot that builds and tests every PR. When the project started, the backend and frontend folders did not exist yet, so each CI step had a rule: "skip me if the project is missing". Both projects exist now, so that rule had become a trap: if a file such as `backend/gradlew` were deleted by mistake, CI would skip every test and still show **green**. I removed the rule. A missing project now makes CI **red**, which is what we want.

---

## Part 2: Technical details

### The libraries

| Package | Version | Draws or reads | React peer range | License | Pulls in |
|---|---|---|---|---|---|
| `qrcode.react` | `^4.2.0` | draws (`<QRCodeSVG value="campus-pickup:1:12:493027" />`) | `^16.8 … ^19.0.0` | ISC | nothing |
| `@yudiel/react-qr-scanner` | `^2.6.0` | reads (`<Scanner onScan={...} />`, handles the camera itself) | `^17 \|\| ^18 \|\| ^19` (+ `react-dom`) | MIT | `barcode-detector` → `zxing-wasm`, `webrtc-adapter` |

- Compatibility checked with `npm view <pkg> peerDependencies` before installing: our React is 19.3.1, and both ranges include it.
- Installed with `npm install` from `frontend/`, so `package-lock.json` pins the exact versions (+7 packages, `npm audit`: 0 vulnerabilities).
- **Why not `jsqr`** (the spec's suggestion, `docs/superpowers/specs/2026-11-10-pickup-pass-design.md` §3)? `jsqr` only decodes one image. Matei would have to open the camera, copy frames to a `<canvas>` and loop. The scanner component does all of that, so his PR 3 stays small.
- **Unused imports cost nothing:** Vite only bundles what is imported, so the production bundle size is unchanged until Matei imports them.
- **Note for Matei's PR 3:** `zxing-wasm` loads a `.wasm` file at runtime, by default (as far as I know) from a CDN. Check it in the browser network tab on the prod build.

### `ci.yml`: guards removed

| Before | After |
|---|---|
| 8 steps had `if: hashFiles('backend/gradlew') != ''` or `if: hashFiles('frontend/package.json') != ''` | no conditions: the steps always run |
| `gradlew` or `package.json` deleted → steps **skipped** → `ci` green, nothing tested | → the step **fails** → `ci` red |
| docs-only PRs skipped the build (back when the projects did not exist) | docs-only PRs run the full build too (a few minutes more, but `ci` is a required check, so it must test something) |

Only the `if:` lines and two stale comments changed. A new comment says why there are no guards, so nobody adds them back. `docs-check.yml` was not touched.

### Checks run

`npm ci`, `npm run lint`, `npm run build` in `frontend/`: all green.

### Defense questions

- **Why is a green CI that tested nothing worse than a red one?** Red stops the merge and someone looks at it. A false green lets a broken `main` through, and nobody notices until the demo.
- **Why commit `package-lock.json`?** `npm ci` in CI and in the Dockerfile installs exactly the versions in the lock file, so every laptop and CI build the same thing.
