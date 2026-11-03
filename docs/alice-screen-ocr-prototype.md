# Alice AI screen OCR prototype (Android)

**Experimental**. RootTube can already send a selected YouTube comment
to Alice AI via `ACTION_SEND` to the official Yandex Browser. The browser
does not return an Android Activity result. The previous manual clipboard
workflow remains available.

This feature adds an **optional** Accessibility Service that may obtain
the translation from a screenshot of the **official Yandex Browser Alice chat**
(`com.yandex.browser`) or the separate Alice app (`com.yandex.aliceapp`). On Android, the user must explicitly enable
`RootTube: перевод Алисы с экрана` in Accessibility settings.

## Privacy and scope

- Capture is armed **only** by an explicit tap on the Alice translate action.
- Active for at most 75 seconds and only while Yandex Browser or the dedicated Alice app is foreground. A screenshot may include other visible parts of those screens, but no image is stored.
- Screenshots and OCR text are processed on-device and are not stored.
- No Accessibility window-tree contents or personal chats are requested.
- A random 8-digit marker is included in the user-selected request. The OCR
  parser requires a standalone reply marker and Russian text below it;
  the original prompt may no longer be visible. The answer must stabilize across two screenshots.
- Only the matching selected comment is updated. Original YouTube comment
  text is not overwritten in the source data.
- Before hand-off, the emoji in the source comment are replaced with visible
  `СМАЙЛ1`, `СМАЙЛ2`, ... markers. The exact original Unicode emoji (including
  skin tones, flags and joined sequences) are reinserted in RootTube after
  recognition, because Tesseract cannot recognize emoji glyphs.
- Original all-caps Latin abbreviations and mixed-case product names such as
  `TPUSA`, `NASA`, `YouTube` and `iPhone` are replaced with `СЛОВО1`,
  `СЛОВО2`, ... markers before sending to Alice, then restored exactly.
  Ordinary English words are left unchanged for translation. If the OCR
  response loses the marker and the exact Latin spelling, RootTube declines
  to auto-apply the potentially corrupted translation.
- After recognition, the service attempts at most three Android Back actions
  only while the Yandex app remains foreground. The attempt stops immediately
  when RootTube resumes or the user enters another app.
- When OCR fails, the original manual copy/paste flow is used.
- **The selected comment itself is sent to Alice AI** via Yandex Browser;
  consult Yandex's policies for cloud-side processing.

## Set up model before building

Run `tools/prepare-alice-ocr.ps1` in PowerShell on Windows. It downloads
`rus.traineddata` from the Tesseract project's `tessdata_fast` repository,
puts both `rus.traineddata` and `eng.traineddata` into `app/src/main/assets/tessdata/` and prints SHA256. The model
asset is excluded from Git. Check that the downloaded files come from the official
`tesseract-ocr/tessdata_fast` repository.

Build `:app:testDebugUnitTest :app:assembleDebug`. On first use the app
copies the bundled models into app-private storage and initializes Tesseract with `rus+eng` to preserve mixed Cyrillic/Latin text.

## Current interaction

1. Enable the Accessibility service once through Android settings.
2. Inside RootTube comments select `AI+`, tap
   `Перевести Алисой AI` under a single comment.
3. The official Alice chat opens with a prepared translation request;
   **the user still needs to tap Send in Alice**.
4. After a correctly marked Russian reply is recognized twice, the
   Accessibility service attempts a bounded Android Back sequence; RootTube
   fills that selected comment on resume.
5. If any of these checks fail, manually copy the answer and import it
   using the old `Из буфера` or manual paste dialog.

The feature has **not yet been validated on a real handset**. Showing a
screenshot with ADB does not guarantee that the Accessibility Service's
`takeScreenshot` callback or Tesseract parsing will work on that device.

This is not a hidden Yandex API and has no paid Yandex Cloud credentials.
Do not claim a completely one-tap end-to-end flow until automatic chat
submission and reliable in-app return are separately tested.
