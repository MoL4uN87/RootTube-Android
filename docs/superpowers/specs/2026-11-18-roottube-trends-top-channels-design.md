# RootTube: extended trends and top viewed channels

## Intent

Add easy-to-browse topic discovery and a personal channel ranking to RootTube's existing "Trending" page, while preserving all five original NewPipe kiosk categories and the installed application's data.

## Scope and behavior

- Existing Gaming, Trailers, Podcasts, Music and Live tabs continue using official NewPipe kiosks.
- Six new tabs (Sports, Technology, Cars, News, Education and Humor) display video search results; these are **not official YouTube trends**. Russian search terms are used for these Russian-user topic shortcuts.
- A final "Top my channels" tab lists channels opened directly or encountered through an opened video, ordered by subscriber count descending. A row opens the channel page.
- Store the stable channel ID, name, avatar, last known subscriber count and last-opened time in Room. The channel ID is unique, so another opening updates the existing row without a duplicate.
- Opening a video uses the uploader information included with the loaded stream; opening a channel uses the actual loaded channel metadata. Updating a channel also updates the order on the next observation.
- The ranking is available offline from its locally stored snapshot; empty rankings display an explanation. Missing/unknown subscriber counts never rank above known larger counts.
- The top-channels tab and search-driven topics do not depend on trending-region selection; the region selector is shown only for official kiosk tabs.

## Constraints and non-goals

- Keep the existing Android application ID and all existing data. Room schema must migrate forward without destructive fallback.
- Do not change login, subscriptions or YouTube API provider; do not add a server or background crawling of viewing history.
- The subscriber count is a *last observed* value, updated on the next opening of that channel or a video exposing newer metadata, not a live subscriber-count feed.

## Approaches considered

1. **Selected:** extend the current ViewPager tabs, use NewPipe video search for the six topics, and persist a unique-ID ranking with Room. This reuses established UI and database patterns and is robust across restarts.
2. SharedPreferences JSON list for the ranking: a little less wiring but error-prone for concurrent updates and sorting; rejected.
3. Implement a server-side channel analytics service: would require credentials, hosting and polling; out of scope.

## Verification

- Automated tests cover topic search routing, normalization/deduplication and stable ranking order.
- Android compilation plus Room migration/schema validation, and manual smoke test on a phone, are required before calling an APK released.
