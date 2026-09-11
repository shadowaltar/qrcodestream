# Requirements — Optical QR Capture System

Status: Draft v0.1
Date: 2026-09-11
Owner: TBD

---

## 1. Purpose

Build a two-part system that transfers an arbitrary file from a desktop/console machine
to an Android device using nothing but **visually animated QR codes** shown on a display
and captured by the Android device's camera.

The system is inspired by, and should follow the encoding approach of, existing
projects:

- **TXQR** — `divan/txqr` (MIT). Protocol and reference implementation for animated QR
  transfer using **fountain codes** (Luby Transform / LT codes).
- **Decimen Optical Transfer** — fountain-code based high-density optical transfer.
- **PixelBeam** — `ahmmedrejowan/PixelBeam` (GPL-3.0). Android sender/receiver using
  chunked base64 QR frames. **Reference only — do not copy code (copyleft).**

The end product is:

1. An **Android application** delivered as an installable `.apk`.
2. A **Java desktop/console emitter** that generates and displays the QR frame stream.

---

## 2. Scope

### In scope

- Animated QR generation (emitter) and camera-based capture + decode (receiver).
- Fountain/erasure-coded frame protocol so dropped frames do not break the transfer.
- Android runtime permission handling (camera + file writing).
- Writing the reconstructed payload to a fixed, predictable path on Android.

### Out of scope (v1)

- Bidirectional transfer / acknowledgement channel.
- Encryption or authentication of the payload.
- Transfer of very large files (> a few MB) or real-time streaming.
- iOS, web, or other receivers.
- Multiple concurrent transfers or batch mode.

---

## 3. Deliverables

| # | Deliverable | Language / Tech | Format |
|---|-------------|-----------------|--------|
| D1 | Receiver Android app | **Java**; CameraX + ZXing | Debug + release `.apk` |
| D2 | Emitter desktop/console program | **Java** (JDK 17+) | Runnable `.jar` / console app |
| D3 | Protocol specification | Markdown | `PROTOCOL.md` (derived from this doc) |
| D4 | Build instructions | Markdown | `BUILD.md` |
| D5 | This requirements document | Markdown | `REQUIREMENTS.md` |

---

## 4. Receiver — Android Application (D1)

App name / label: **QRCodeStreamClient** (display name).
Application ID (package): **`com.mu.qrcodestreamclient`** (confirmed).
The application ID is Android's unique app identifier (reverse-DNS); it is what the OS
uses to identify the installed app. It may differ from the display name and can stay fixed
across versions.

### 4.1 Landing screen

A single screen containing:

- A **Start** button.
- A **file path** display below the button, composed of:
  - **Folder**: the Android OS default Download folder
    (e.g. `/storage/emulated/0/Download/`). Must be resolved at runtime
    (it can differ per device/user profile), not hard-coded.
  - **File name**: a UTC timestamp in the format `yyyyMMddHHmmss`
    (e.g. `20260911142305`) with the suffix `.capture`
    (e.g. `20260911142305.capture`).

Full example path:

```
/storage/emulated/0/Download/20260911142305.capture
```

**Filename semantics**

- The timestamp is the **task start timestamp** — i.e. the moment the user presses
  **Start**, not app launch and not scan completion.
- Before Start is pressed, the screen may show a live-updating preview of the name
  (refreshed once per second). On Start, the value is frozen and used as the output
  filename for that session.
- Only one capture session is written per Start press. A new Start creates a new file.

### 4.2 Start behavior / permissions

When **Start** is pressed:

1. Verify/request the **Camera** runtime permission (`android.permission.CAMERA`).
   If absent and not yet granted, show the system permission dialog.
2. Verify/request the **file writing** capability (see 4.5 for API-specific details).
   If absent/denied, request or surface a clear message and offer a settings shortcut.
3. If permissions are granted, open the device's **main (rear) camera**.
4. Begin scanning the animated QR stream.

Permission denial must not crash the app; show a rationale and a way to retry.

### 4.3 Camera and scanning

- Use the **back/main camera** by default.
- Continuous frame analysis must sustain at least **10–15 FPS** of QR decode attempts on
  the target hardware (vivo X100 and BlueStacks webcam).
- Use CameraX with **ZXing** (`com.google.zxing:core`) for QR detection.
- Multiple, **continuously changing** QR codes are expected; the receiver must:
  - Decode frames as fast as they arrive.
  - Tolerate arbitrary frame order and dropped frames.
  - Correctly ignore duplicate frames.
  - Detect completion when the full payload is reconstructed.
- Live progress feedback overlay (see below).
- QR error-correction level should match the emitter's level (default **L**) for maximum
  data density.

**Progress overlay on the camera view** (feasible):

- While scanning, overlay the current progress in the **top area** of the camera preview.
- Implement as a layered layout (e.g. `FrameLayout`/`ConstraintLayout`) with CameraX
  `PreviewView` underneath and a `TextView` (with semi-transparent background) pinned to
  the top, updated on the main thread from decode callbacks.
- Overlay content (at minimum): frames received / estimated percentage. Optionally also
  show source/total or a small progress bar.
- The overlay must not intercept camera touch/focus events unless needed.

### 4.4 Completion UI

- When the transmission is **finished** — i.e. all missing frames have been resolved and
  the payload is fully reconstructed (and, if implemented, integrity-verified) — the
  screen enters a completion state that:
  1. **Closes/stops the camera** and the frame-analysis pipeline.
  2. Shows a visible **"Done"** text label.
  3. Shows a **new button** that returns the user to the **start screen** (the landing
     screen of 4.1), ready for a new session.
- The "Done" label and button must only appear on successful completion, not on partial
  progress or error.
- The completion state persists until the user taps the back-to-start button (or otherwise
  leaves the screen).

### 4.5 Output file and storage permissions

- Output directory: Android default **Download** folder.
- Output filename: as defined in 4.1.
- The captured bytes must be the exact reconstructed payload (no added headers), so the
  `.capture` file is byte-identical to the emitter's source file.

API-specific storage behavior (must be handled):

- **API ≤ 28 (Android ≤ 9)**: request `WRITE_EXTERNAL_STORAGE`, write directly to
  `/storage/emulated/0/Download/`.
- **API 29+ (Android ≥ 10)**: use `MediaStore.Downloads` (scoped storage; no broad
  storage permission required). The row is inserted with the `.capture` display name and
  written via the returned `ContentResolver` URI. `MANAGE_EXTERNAL_STORAGE` is **not**
  used.
- All file I/O must happen off the main thread.

### 4.6 Error handling / UX

- Insufficient light or no QR detected → show hints (lighting, distance, steadiness).
- Permission permanently denied → deep-link to app settings.
- Write failure → surface the reason and keep decoded data available for retry.

### 4.7 Android compatibility targets

- `minSdk`: **26** (Android 8.0) — required because the shared `core` uses
  `java.util.Base64`, which is unavailable below API 26.
- `targetSdk` / `compileSdk`: **35**.
- Must run on **vivo X100** (physical device) and **BlueStacks 5.22** (emulator, host
  webcam as camera source, unless config changed).

---

## 5. Emitter — Java Desktop/Console Program (D2)

### 5.1 Function

- Read an input file (chosen with the native OS open-file dialog, or supplied via
  `--input`).
- Encode it into a stream of QR frames using the fountain-code protocol (Section 6).
- Display the QR frames in a window at a configurable frame rate, with **Start** and
  **Stop** buttons.
- Interactive controls: a **QR version** slider (10–40) and a **frame-rate** slider
  (10–25 FPS). The chosen version determines the frame's data capacity, hence `chunkLen`.
- Show a rough transmission rate (kbps) in the bottom status area.
- Headless mode (**confirmed**): write the frames as **static image files** (PNG
  sequence, numbered in display order) to an output directory instead of showing them.
  Enables deterministic automated tests that replay frames to the decoder without a second
  screen or a human. Optional for the interactive use case.

### 5.2 Required configuration

Defaults are taken from **TXQR's tested values** (its best measured result was ~13 KB in
501 ms at 12 FPS with 1850 bytes per QR code and error-correction level L), adapted for the
interactive UI.

| Setting | Default | Notes |
|---------|---------|-------|
| Input file | OS file dialog | `--input` pre-selects; required only in headless mode |
| QR version | **20** (range 10–40) | UI slider; sets `chunkLen` to the version's byte capacity |
| Chunk length (`chunkLen`) | derived from QR version | Headless default **1850** |
| Redundancy factor | **2.0** | TXQR default |
| Frame rate | **15 FPS** (range 10–25) | UI slider |
| QR error-correction level | **L** | TXQR best-tested; max density |
| Loop/stream behavior | Loop generated frames | Infinite until stopped |
| Headless output dir | CLI arg (optional) | If set, write numbered PNG frames instead of displaying |

### 5.3 Dependencies (constrained)

Third-party dependencies are restricted to the **bare minimum required for QR code
generation**, plus optionally **Lombok** and **Swing** (JDK built-in UI, no extra
dependency).

| Allowed | Purpose | Notes |
|---------|---------|-------|
| ZXing `core` | Render frames as QR codes | Confirmed; the only QR-related library permitted |
| Lombok | Boilerplate reduction | Optional |
| Swing (JDK) | QR display window | Built into the JDK; no download needed |

Not allowed:

- External **fountain/LT code libraries** — the LT (Luby Transform) encoder and soliton
  distribution must be **implemented in-project**.
- Any other third-party utility/framework not listed above.
- Copying TXQR/gofountain or PixelBeam code. TXQR's algorithm may be re-implemented in
  Java from its published design; do not vendor the Go source.

---

## 6. Transfer Protocol

Adopt a TXQR-compatible frame format and fountain-coding scheme.

### 6.1 Frame format

```
<blockCode>/<chunkLen>/<total>|<data>
```

- `blockCode` — encoded block identifier.
- `chunkLen` — source chunk length used by the encoder.
- `total` — total length of the original payload.
- `data` — the encoded (base64) block payload.

This is the exact framing used by TXQR (`encode.go`), so the fountain/decoder layer can
follow the TXQR decode algorithm directly. Only the *inner payload* uses our `AQRC1`
envelope (Section 6.2); the frame header stays TXQR-compatible.

### 6.2 Encoding scheme

1. Read the source file bytes and compute:
   - `sha256` — lowercase hex SHA-256 of the raw file bytes.
   - `origLen` — original file length in bytes.
2. Build the transfer payload as a small text envelope followed by the file data:

   ```
   AQRC1|<origLen>|<sha256>|<base64(fileBytes)>
   ```

   - `AQRC1` — magic/version tag identifying the envelope.
   - Fields are `|`-separated; the base64 field is last so it may contain no `|`.
3. Split the envelope string into source blocks of `chunkLen`.
4. Generate **LT (Luby Transform) fountain-code** encoded blocks using a soliton
   distribution.
5. Generate `ceil(N * redundancyFactor)` encoded blocks and loop them.
6. Render each block as a QR code using error-correction level **L**.

Receiver side:

1. Decode each QR frame, parse the frame header `<blockCode>/<chunkLen>/<total>|<data>`.
2. Feed `(blockCode, data, chunkLen, total)` into the fountain decoder.
3. When the fountain decoder recovers the full envelope, parse `AQRC1`, `origLen`,
   `sha256`, and the base64 data.
4. Base64-decode the data, then verify:
   - decoded length == `origLen`, and
   - SHA-256(decoded bytes) == `sha256`.
5. On success, write the exact decoded bytes to the `.capture` file and show **Done**.
6. On mismatch, do **not** show Done; surface an integrity error and allow retry.

### 6.3 Integrity trailer

- Integrity is **required** in v1 and carried in the envelope described in 6.2. There is
  no separate trailing block needed.
- SHA-256 is provided by the JDK (`MessageDigest`) on both the Java emitter and the Java
  Android app — no extra dependency.
- The `.capture` file contains only the raw file bytes (envelope stripped), so it stays
  byte-identical to the source.
- The **Done** state (4.4) is shown only after the hash check passes.

---

## 7. Non-Functional Requirements

- **Reliability**: successful reconstruction must tolerate ≥20% dropped/corrupt frames.
- **Performance**: sustained ≥10 FPS decode attempts on vivo X100 and BlueStacks.
- **Determinism**: same input + same parameters → same frame sequence.
- **Portability**: emitter runs on a standard JDK (Windows primary target).
- **Licensing**: TXQR is MIT (safe to reference/port). PixelBeam is GPL-3.0 — do not
  copy its source into this project unless this project is also GPL-3.0.

---

## 8. Build & Packaging

- **Android**: Gradle build producing an installable APK.
  - Debug APK for direct sideloading (copy to vivo X100 / install in BlueStacks).
  - Release APK with minification; document signing.
- **Emitter**: Gradle (or Maven) build producing a runnable JAR.
- Signed/release APK must install without developer warnings beyond the normal
  "unknown sources" prompt.

---

## 9. Acceptance Criteria

1. `assembleDebug`/`assembleRelease` produces an APK that installs on vivo X100 and
   BlueStacks 5.22.
2. Landing screen shows Start + Download path with UTC `yyyyMMddHHmmss.capture`.
3. First run prompts for camera and file-writing permissions; denial is handled.
4. Camera opens on Start; a progress overlay is shown on the top of the camera view, and
   the animated QR stream from the emitter is decoded.
5. On full reconstruction: the camera closes, a **"Done"** label is shown, and a button
   returns the user to the start screen.
6. A complete transfer writes `/storage/emulated/0/Download/<UTC>.capture`
   byte-identical to the emitter's input file, verified by the `AQRC1` SHA-256 check.
7. Corrupt/incomplete data fails the integrity check and does **not** show "Done".
8. With ~20% frames intentionally dropped, the transfer still completes.

---

## 10. Decisions (resolved) and Remaining Open Questions

Resolved:

1. **Android language**: **Java**.
2. **Storage on API 29+**: **`MediaStore.Downloads`** (no `MANAGE_EXTERNAL_STORAGE`).
   API ≤ 28 uses `WRITE_EXTERNAL_STORAGE`.
3. **Protocol defaults**: TXQR tested values — chunk length **1850**, redundancy **2.0**,
   **12 FPS**, error-correction **L**.
4. **QR library**: **ZXing** (`core`) for both emitter (generate) and receiver (scan).
5. **App name**: display label **QRCodeStreamClient**; application ID **`com.mu.qrcodestreamclient`**.
6. **Integrity trailer**: **included** in v1 via the `AQRC1` envelope (SHA-256 + length).
7. **Headless mode**: **included**, emits numbered static PNG frames to a directory.

Remaining open:

1. **Emitter artifact / base name** (e.g. `qrcodestream-emitter`).

---

## 11. Suggested Milestones

1. **M1** — Protocol + emitter core (encode, frame generation) + console output.
2. **M2** — Android landing screen, permissions, camera preview.
3. **M3** — Android decoder + fountain reconstruction + `.capture` write.
4. **M4** — End-to-end transfer on BlueStacks, then vivo X100; reliability tuning.
5. **M5** — Release APK + JAR, build docs, acceptance test run.
