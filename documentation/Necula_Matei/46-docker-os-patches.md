# First image in ECR + OS security updates in the Docker image (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-09
**Branch / PR:** `fix/docker-os-patches`
**See also:** `19-dockerfile.md` (the Dockerfile), `45-terraform-data.md` (the ECR repository)

---

## Part 1: In simple terms

1. **The first image went to AWS.** The app's Docker image (website + backend in one box) was built from `main` and uploaded to our private image registry on AWS (ECR), named after its commit, so a deploy always knows exactly which code it runs.
2. **AWS checked it.** ECR scans every uploaded image for known security holes for free. It found **one critical problem**: an old version of `zlib`, a compression library that came with the base image.
3. **The fix already existed.** Alpine Linux (the small operating system inside the image) had released the patched version, but the base image we start from hadn't picked it up yet.
4. **Fix:** the Dockerfile now installs the operating system's security updates every time the image is built. The rebuilt image has the patched `zlib` and still runs as a normal (non-root) user.

**Result:** every future image gets the latest OS security fixes automatically, and ECR keeps scanning each push.

---

## Part 2: Technical details

### Files changed

| File | Change |
|---|---|
| `Dockerfile` | Runtime stage: `RUN apk upgrade --no-cache && addgroup ... && adduser ...` (one layer), with a bug-fix comment |
| `documentation/Necula_Matei/46-docker-os-patches.md` | This doc |
| `documentation/Necula_Matei/02-files-explained.md`, `README.md` | Doc 46 in the header and the index |

### The first push to ECR (9 Nov)

```bash
cd infra && REPO=$(terraform output -raw ecr_repository_url)
SHA=$(git rev-parse HEAD)                       # b1100c5..., main after #250
docker build --platform linux/amd64 -t campus-hub:$SHA .
aws ecr get-login-password | docker login --username AWS --password-stdin ${REPO%%/*}
docker tag campus-hub:$SHA $REPO:$SHA && docker push $REPO:$SHA
```

| Check | Result |
|---|---|
| `aws ecr describe-images --repository-name campus-hub` | 1 image, tag = full commit SHA, ~153 MB compressed (312 MB locally) |
| `aws ecr describe-image-scan-findings ...` | Scan complete: **1 CRITICAL**, `CVE-2026-4738` in `zlib` 1.3.2-r1 |
| `apk list -u` inside the image (as root) | `zlib-1.4-r0 ... [upgradable from: zlib-1.3.2-r1]`: Alpine 3.24 already has the fix |

`--platform linux/amd64`: the EC2 server is x86 (`t3.small`, ADR-002); an ARM build would fail there with `exec format error`.
The scan takes a few minutes; `describe-images` shows no status until it is done, `describe-image-scan-findings` does.

### Why `apk upgrade` and not "wait for a new base image"

| Option | Why (not) |
|---|---|
| Wait for `eclipse-temurin:25-jre-alpine` to be rebuilt | Out of our hands; can take weeks, and the next CVE starts the wait again |
| Pin `zlib=1.4-r0` | Fixes this one package only, and breaks the build once Alpine moves to the next version |
| **`apk upgrade --no-cache`** | Every build gets all current OS security fixes. `--no-cache` keeps the package index out of the image. It runs before `USER app`, because only root can install packages |

Trade-off: two builds of the same commit on different days can contain different OS packages. Fine for us: the image in ECR is the one we deploy, and its tag (commit SHA) is immutable.

### How it was tested

| Command | Result |
|---|---|
| `docker build --platform linux/amd64 -t campus-hub:patched .` | Builds |
| `docker run --rm --entrypoint sh campus-hub:patched -c 'apk info -v \| grep zlib; whoami'` | `zlib-1.4-r0`, `app` (still not root) |

After merge: push the image built from the new `main` commit and check that ECR's scan shows no CRITICAL finding.
