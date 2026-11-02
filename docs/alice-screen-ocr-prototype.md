# Alice AI screen OCR prototype (Android)

**Experimental**. RootTube can already send a selected YouTube comment
to Alice AI via `ACTION_SEND` to the official Yandex Browser. The browser
does not return an Android Activity result. The previous manual clipboard
workflow remains available.

This feature adds an **optional** Accessibility Service that may obtain
the translation from a screenshot of the **separately installed Alice app**
(`com.yandex.aliceapp`). On Android, the user must explicitly enable
`RootTube: перевод Алисы с экрана` in Accessibility settings.

## Privacy and scope

- Capture is armed **only** by an explicit tap on the Alice translate action.
- Active for at most 75 seconds and while `com.yandex.aliceapp` is foreground.
- Screenshots and OCR text are processed on-device and are not stored.
- No Accessibility window-tree contents or personal chats are requested.
- A random 8-digit marker is included in the user-selected request. The OCR
  parser requires two occurrences of that specific marker and Russian text
  after the second one. The answer must stabilize across two screenshots.
- Only the matching selected comment is updated. Original YouTube comment
  text is not overwritten in the source data.
- When OCR fails, the original manual copy/paste flow is used.
- **The selected comment itself is sent to Alice AI** via Yandex Browser;
  consult Yandex's policies for cloud-side processing.

## Set up model before building

Run `tools/prepare-alice-ocr.ps1` in PowerShell on Windows. It downloads
`rus.traineddata` from the Tesseract project's `tessdata_fast` repository,
puts it into `app/src/main/assets/tessdata/` and prints SHA256. The model
asset is excluded from Git. Check that the downloaded file is ~3.7 MB and
comes from the official `tesseract-ocr/tessdata_fast` repository.

Build `:app:testDebugUnitTest :app:assembleDebug`. On first use the app
copies the bundled model into app-private storage for Tesseract.

## Current interaction

1. Enable the Accessibility service once through Android settings.
2. Inside RootTube comments select `AI+`, tap
   `Перевести Алисой AI` under a single comment.
3. The official Alice chat opens with a prepared translation request;
   **the user still needs to tap Send in Alice**.
4. After a correctly marked Russian reply is recognized twice, the
   Accessibility service attempts one Android Back action; RootTube
   fills that selected comment on resume.
5. If any of these checks fail, manually copy the answer and import it
   using the old `Из буфера` or manual paste dialog.

The feature has **not yet been validated on a real handset**. Showing a
screenshot with ADB does not guarantee that the Accessibility Service's
`takeScreenshot` callback or Tesseract parsing will work on that device.

This is not a hidden Yandex API and has no paid Yandex Cloud credentials.
Do not claim a completely one-tap end-to-end flow until automatic chat
submission and reliable in-app return are separately tested.
