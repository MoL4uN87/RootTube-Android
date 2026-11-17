# RootTube YouTube Comment Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let RootTube users like/unlike, post comments, and reply via official YouTube pages shown in a browser-backed in-app bottom sheet.

**Architecture:** Keep existing read-only Piped/native comment and translation UI. Route user-initiated actions through a validated YouTube URL builder into Android partial Custom Tabs; on return refresh native comment paging. All writes occur on YouTube web UI, never through unofficial authenticated endpoints.

**Tech Stack:** Kotlin, Android Fragments, AndroidX Browser 1.10.0, existing AndroidX Paging, JUnit 4, XML layouts.

**Spec:** `docs/superpowers/specs/2026-11-17-youtube-comment-actions-design.md`

## Global Constraints
- Keep existing translator/AI+ and video playback unchanged.
- Only open HTTPS `www.youtube.com`; never capture cookies, passwords, or tokens.
- Comments and likes require user's browser-based YouTube account; Piped credentials are unrelated.
- A tap in RootTube opens the YouTube panel, not an automatic like or comment.
- Use partial Custom Tabs when supported, full-size browser fallback otherwise.

## Review Focus
1. Malformed video ID: no arbitrary host or arbitrary URI loads; test rejects.
2. Comment ID with punctuation or URL separators: URL-encoded and cannot inject query parameters; test checks.
3. Empty/oversized comment ID: no comment-specific jump; test rejects.
4. Browser unavailable or security rejection: no crash; launcher returns failure and UI displays error.
5. Returning from browser after action: no stale local like state displayed as confirmed; native list refreshes.

---

### Task 1: Safe YouTube action URLs

**Files:**
- Create: `app/src/main/java/com/github/libretube/ui/comments/YouTubeCommentWebLinks.kt`
- Create: `app/src/test/java/com/github/libretube/ui/comments/YouTubeCommentWebLinksTest.kt`

**Interfaces:**
- Produces `object YouTubeCommentWebLinks` with `fun video(videoId: String): String?` and `fun comment(videoId: String, commentId: String): String?`.
- Valid video IDs match `[A-Za-z0-9_-]{11}`; comment ID nonblank, length <= 512, no control characters; comment ID is query-encoded.

- [ ] Step 1: Write JUnit tests asserting canonical video URL, comment `lc=` URL, invalid video rejection, unsafe separators encoding, and empty/oversized comment ID rejection.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*YouTubeCommentWebLinksTest' --console=plain`; expect initial compilation failure.
- [ ] Step 3: Add minimal pure Kotlin URL builder matching the test contract.
- [ ] Step 4: Run the focused test; expect PASS.
- [ ] Step 5: Commit safe links and tests.

### Task 2: Browser-backed partial YouTube panel

**Files:**
- Create: `app/src/main/java/com/github/libretube/ui/comments/YouTubeCommentPanel.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Consumes `YouTubeCommentWebLinks` results.
- Produces `object YouTubeCommentPanel` with `fun open(context: Context, url: String): Boolean`; `true` means launch intent successfully dispatched, not that interaction succeeded.

- [ ] Step 1: Write/verify dependency-specific contract review: caller supplies URL builder output, launcher catches ActivityNotFoundException/SecurityException.
- [ ] Step 2: Add `androidx.browser:browser:1.10.0` version-catalog entry and app dependency.
- [ ] Step 3: Implement CustomTabsIntent with ~80% screen initial height, system/default browser cookies, no ephemeral mode or browser credentials access, safe errors.
- [ ] Step 4: Build `./gradlew :app:assembleDebug --console=plain`; expect compile success.
- [ ] Step 5: Commit browser launcher + dependency.

### Task 3: Hook comment row actions, composer, and refresh on return

**Files:**
- Modify: `app/src/main/res/layout/comments_row.xml`
- Modify: `app/src/main/res/layout/fragment_comments.xml`
- Modify: `app/src/main/res/values/strings_roottube_comments.xml`
- Modify: `app/src/main/java/com/github/libretube/ui/adapters/CommentsPagingAdapter.kt`
- Modify: `app/src/main/java/com/github/libretube/ui/fragments/CommentsMainFragment.kt`
- Modify: `app/src/main/java/com/github/libretube/ui/fragments/CommentsRepliesFragment.kt`
- Modify: `app/src/main/java/com/github/libretube/ui/sheets/CommentsSheet.kt`

**Interfaces:**
- Adapter adds `openYouTubeActions: (Comment) -> Unit` and `replyOnYouTube: (Comment) -> Unit`, no side effects except callbacks.
- Sheet adds `openYouTubeCommentActions(videoId: String?, commentId: String? = null)`; emits child fragment result after resuming from panel, to trigger paging adapter `refresh()`.
- Each fragment wires both callbacks and bottom composer. Main composer opens video; replies composer opens current parent comment.

- [ ] Step 1: Add localized accessible button descriptions for like/response/new comment and browser error.
- [ ] Step 2: Add clickable like and explicit reply buttons to comment row and a fixed composer button in the existing comments layout.
- [ ] Step 3: Wire callbacks through both fragments to the sheet URL launcher; ensure normal reply navigation still works and translation UI persists.
- [ ] Step 4: Add refresh-after-return signal to both adapters and proper failure toast.
- [ ] Step 5: Run `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`, verify output.
- [ ] Step 6: Commit UI and wiring.

### Task 4: Device acceptance and documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-11-17-youtube-comment-actions-design.md` only if documented assumptions change.

- [ ] Step 1: On Samsung SM-S948B with Chrome supporting partial Custom Tabs, test opening individual comment and top-level composer.
- [ ] Step 2: Sign in to YouTube within browser if needed; test liking/unliking, posting, replying, closing panel, and preserving video.
- [ ] Step 3: Test browser unavailable/unsupported partial tab and comments-disabled video; record limitations.
- [ ] Step 4: Hand off one PowerShell command per step to user for device tests and mark build success only after observed output.
