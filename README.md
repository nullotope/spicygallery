# SpicyGallery 🌶

A local, self-hosted, booru-inspired media gallery. Point it at one or more
**read-only library folders** and it indexes your images and videos — hashing,
probing dimensions/duration, generating 512px thumbnails — into a Postgres
database, then serves a fast masonry grid, a detail viewer with keyboard
navigation, and Range-capable streaming playback. Files are never touched:
libraries are mounted read-only and the index is fully rebuildable.

Built as a modular monolith: a pure-Java core (`spicygallery-core`: scanners,
kind handlers, import pipeline), a Spring Boot server (`spicygallery-server`:
REST + JDBC + the SPA in the jar), and a Quasar/Vite SPA
(`spicygallery-webclient`).

## Run (development)

```bash
MEDIA_DIR=/path/to/your/library ./run.sh
# → http://127.0.0.1:8080
```

`run.sh` starts Postgres 17 in Docker (localhost-only), builds the full reactor
(`mvn install`), and runs the fat jar. Shortcuts: `SKIP_TESTS=1` skips tests,
`SKIP_FRONTEND=1` builds a backend-only jar.

Then open http://127.0.0.1:8080, press **Import / update index** in the sidebar
(or Settings), and browse. Re-running the import only picks up added, changed
or deleted files.

## Run (Docker)

```bash
MEDIA_DIR=/path/to/your/library docker compose up --build
# → http://localhost:8080
```

`docker compose down -v` removes the database and thumbnail volumes (the media
itself is never touched).

## Configuration

Everything is settable via `spicygallery.*` properties and their environment
equivalents (Boot relaxed binding — e.g. `spicygallery.app-data` is
`SPICYGALLERY_APPDATA`):

| Property | Env | Default | Meaning |
|---|---|---|---|
| `spicygallery.libraries[0].name` | `SPICYGALLERY_LIBRARIES_0_NAME` | — | library identity key (stable! renaming orphans rows) |
| `spicygallery.libraries[0].root` | `SPICYGALLERY_LIBRARIES_0_ROOT` | — | absolute path of the read-only library folder |
| `spicygallery.libraries[1].…` | `SPICYGALLERY_LIBRARIES_1_…` | — | additional libraries |
| `spicygallery.app-data` | `SPICYGALLERY_APPDATA` | `./data` | writable folder for thumbnails |
| `spicygallery.ffmpeg-path` | `SPICYGALLERY_FFMPEGPATH` | `ffmpeg` | configured ffmpeg (else PATH) |
| `spicygallery.ffprobe-path` | `SPICYGALLERY_FFPROBEPATH` | `ffprobe` | configured ffprobe (else PATH) |
| `spicygallery.workers` | `SPICYGALLERY_WORKERS` | `4` | import worker threads |
| `spicygallery.image-exts` | `SPICYGALLERY_IMAGEEXTS` | jpg,jpeg,jpe,png,gif,webp,bmp | indexed image extensions |
| `spicygallery.video-exts` | `SPICYGALLERY_VIDEOEXTS` | mp4,webm,mkv,mov | indexed video extensions |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/spicygallery` | Postgres 17 |

Dot-files and dot-directories are skipped at every depth; extensions are
matched case-insensitively; files that fail to hash/probe become `error` rows
instead of aborting the run.

## API sketch

- `GET /api/entries?q=&kind=&cursor=&limit=` — keyset-paginated grid (250-row
  walks stay O(page) — no OFFSET)
- `GET /api/entries/{id}` · `/neighbors?q=&kind=` · `/file` (Range + ETag) ·
  `/thumb` (immutable, `?v=` versioned)
- `POST /api/imports` → 202; `GET /api/imports/last` (reconcile — polled by the
  client at a fast cadence while a run is active; carries live progress)
- `GET /api/system/info` — libraries, ffmpeg/ffprobe detection, workers

## Tests

```bash
mvn -q install                          # everything (needs Docker: testcontainers)
cd spicygallery-webclient && npx vitest run && npx vue-tsc --noEmit
```

The tiny binary fixtures used by the core tests are committed under
`spicygallery-core/src/test/resources/fixtures/` and can be regenerated with
`python3 scripts/dev/make-fixtures.py` (needs Pillow + ffmpeg).
