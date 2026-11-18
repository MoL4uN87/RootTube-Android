# RootTube Trends and Top Channels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add six search-driven trending topics and a persistent unique-by-channel, subscriber-ranked "Top my channels" tab.

**Architecture:** Keep official kiosks unchanged, route the new categories through NewPipe's existing video search API, and save viewed-channel snapshots in a Room table. Add a separate channel list inside the existing Trends ViewPager, populated from a Room `Flow`.

**Tech Stack:** Kotlin, AndroidX Fragment/ViewPager2, RecyclerView, NewPipeExtractor, Room/KSP, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-18-roottube-trends-top-channels-design.md`

## Global Constraints

- Keep `applicationId = "com.roottube.android"`, with existing data intact.
- Keep all five original official kiosk tabs functional.
- Search-driven tabs must not be labeled official trends.
- No login, new server, background scraping or destructive database migrations.

## Review Focus

- Opening the same channel from a video and its page must create one entry; test unique-ID upsert.
- Opening a video with an empty/malformed uploader ID must not insert junk; test normalization.
- A lower or missing subscriber count must not unexpectedly replace a known higher count; test snapshot merge.
- A large count must rank ahead of a smaller one; test comparator with ties.
- Changing the current trending region must not affect the saved personal top or search-driven topics; verify via UI branch.

---

### Task 1: New trending categories

**Files:**
- Modify: `app/src/main/java/com/github/libretube/api/MediaServiceRepository.kt`
- Modify: `app/src/main/java/com/github/libretube/api/NewPipeMediaServiceRepository.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Test: `app/src/test/java/com/github/libretube/api/TrendingCategoryTest.kt`

**Interfaces:**
- Produces `TrendingCategory.isPersonalRanking: Boolean` and `TrendingCategory.searchQuery: String?`.
- `getTrending(region, category)` uses search results for six topics and kiosks for five original topics; caller never requests a personal ranking from `getTrending`.

- [ ] Write a failing test asserting all original labels/categories remain and six topics have their intended nonempty search queries.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*TrendingCategoryTest'` and observe the expected failure (or record the unavailable toolchain).
- [ ] Add categories/string resources and use `getSearchResults(query, "videos")` for six topics, mapping returned video `ContentItem` objects to `StreamItem`.
- [ ] Run the focused test and full `:app:testDebugUnitTest` when available.
- [ ] Commit the feature and test.

### Task 2: Unique persistent channel ranking and capture

**Files:**
- Create: `app/src/main/java/com/github/libretube/db/obj/ViewedChannel.kt`
- Create: `app/src/main/java/com/github/libretube/db/dao/ViewedChannelDao.kt`
- Create: `app/src/main/java/com/github/libretube/helpers/ViewedChannelsHelper.kt`
- Modify: `app/src/main/java/com/github/libretube/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/github/libretube/ui/fragments/ChannelFragment.kt`
- Modify: `app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt`
- Test: `app/src/test/java/com/github/libretube/helpers/ViewedChannelsHelperTest.kt`
- Generate: `app/schemas/com.github.libretube.db.AppDatabase/26.json` via KSP.

**Interfaces:**
- `ViewedChannelDao.observeAll(): Flow<List<ViewedChannel>>`, `ViewedChannelDao.getById(id)` and transactional `record` (unique primary key); `ViewedChannelsHelper.rank(list)` supplies the tested presentation order.
- `ViewedChannelsHelper.record(...)` accepts a channel ID/URL, name, avatar, observed subscriber count and timestamp; normalizes ID and writes a merged snapshot on IO.

- [ ] Write failing tests for duplicate opens, stable order, normalization and unknown counts (in-memory merger where JVM unit tests can run).
- [ ] Run focused test and confirm failure (or record toolchain limitation).
- [ ] Create entity, DAO, database migration 25→26, and shared helper; call it after successful channel fetch and video stream display.
- [ ] Run focused and full tests, and check schema creation/migration without data loss when toolchain is available.
- [ ] Commit.

### Task 3: Personal top tab

**Files:**
- Modify: `app/src/main/java/com/github/libretube/ui/fragments/TrendsFragment.kt`
- Create: `app/src/main/java/com/github/libretube/ui/adapters/TopViewedChannelsAdapter.kt`
- Modify: `app/src/main/res/layout/fragment_trends_content.xml` if a dedicated empty state is needed.
- Test: ranking ordering exercised by Task 2 tests.

**Interfaces:**
- Consumes `TrendingCategory.isPersonalRanking`, `ViewedChannelDao.observeAll()` and `ViewedChannelsHelper.rank(list)`.
- The personal page uses channel-row presentation and `NavigationHelper.navigateChannel` on click.

- [ ] Add the personal page to the existing tab set; do not call a kiosk/search for it.
- [ ] Observe DAO results during the view lifecycle and show the ranked channels, including an empty-state message.
- [ ] Confirm region control does not re-fetch personal ranking and handle an invalid saved category safely.
- [ ] Run tests, assemble debug, inspect changes and commit.

## Completion conditions

- All requirements trace to Task 1, Task 2 or Task 3.
- Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` in a configured Android environment; record any environmental blocker rather than claiming passing tests.
- Inspect `git diff --check` and final branch diff before handoff.
