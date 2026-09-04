# Phase 05: Static Pre-compiled Regexes in Hot String Parsing Paths

Status: ✅ Completed
Issue ID: PERF-CPU-01
Dependencies: Phase 04

## Objective
Eliminate repeated dynamic regex pattern compilation and state-machine construction inside high-frequency HTML scraping, connector parsing, and URL generation hot paths (`StationForecastParser`, `EvcsApiClient`, `StationUrlBuilder`, `EvcsRepository`), saving CPU cycles and garbage collection overhead.

---

## Requirements

### Functional
- [x] In `StationForecastParser.kt`, extract dynamic `Regex("<[^>]+>")` and `Regex("""\s+""")` inside `stripHtmlTags` to static `companion object` constants (`HTML_TAGS_REGEX`, `WHITESPACE_COLLAPSE_REGEX`).
- [x] In `EvcsApiClient.kt`, extract all 15 dynamic regex instances in `parseCoordinatesFromHtml` and metadata extractors (latitude/longitude metadata patterns, `geo.position`, JSON lat/lng extractors, map query regex, data attributes, address, working-time) to pre-compiled `companion object` constants.
- [x] In `StationUrlBuilder.kt`, extract diacritic stripping, punctuation filtering, whitespace collapse, and dash consolidation regexes in `slugify` to pre-compiled constants (`DIACRITICS_REGEX`, `NON_ALPHANUMERIC_REGEX`, `WHITESPACE_REGEX`, `CONSECUTIVE_DASHES_REGEX`).
- [x] In `EvcsRepository.kt`, extract the connector wattage regex `Regex("""(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE)` inside `parseConnectorsToPowers` to a static `companion object` constant (`KW_REGEX`).
- [x] Maintain 100% regex parsing parity: outputs for all HTML variants, coordinate formats, connector formats, and Vietnamese slug translations must be identical.

### Non-Functional
- [x] Zero regex recompilation during station HTML parsing, connector parsing, or slug construction.
- [x] CPU efficiency: Instant matching against cached DFA/NFA automata.

---

## Implementation Steps
1. **Refactor `StationForecastParser.kt`**:
   - Declare `HTML_TAGS_REGEX` and `WHITESPACE_COLLAPSE_REGEX` as static private constants in `companion object`.
2. **Refactor `EvcsApiClient.kt`**:
   - Move all 15 regex patterns inside `parseCoordinatesFromHtml` and detail scrapers to `companion object`.
3. **Refactor `StationUrlBuilder.kt`**:
   - Declare `DIACRITICS_REGEX`, `NON_ALPHANUMERIC_REGEX`, `WHITESPACE_REGEX`, and `CONSECUTIVE_DASHES_REGEX` as static constants.
4. **Refactor `EvcsRepository.kt`**:
   - Move `KW_REGEX` to `EvcsRepository.Companion`.
5. **Verify Parity**:
   - Run verification tests against all known station HTML snippets, connector strings, and diacritic test cases.

---

## Files to Modify/Create
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt` - Pre-compile HTML strip regexes.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Pre-compile coordinate and meta regexes.
- [MODIFY] `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` - Pre-compile slugify regexes.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Pre-compile connector parsing regex.
- [NEW] `app/src/test/java/com/evcs/favorites/StaticRegexHotPathParsingPerformanceTest.kt` - Exactly one comprehensive test for Phase 05.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/StaticRegexHotPathParsingPerformanceTest.kt`
- **Core Verifications**:
  1. `StationForecastParser` HTML stripping produces exact clean strings across complex HTML tags and multiline whitespace.
  2. `EvcsApiClient` extracts coordinates accurately from all formats (OpenGraph meta, geo.position, JSON payloads, map URLs, data attributes).
  3. `StationUrlBuilder.slugify` accurately strips Vietnamese diacritics, normalizes spaces, and consolidates hyphens.
  4. `EvcsRepository.parseConnectorsToPowers` accurately extracts wattages across single and multi-connector strings.
  5. Parsing is deterministic, thread-safe, and idempotent across thousands of sequential evaluations.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.StaticRegexHotPathParsingPerformanceTest
```
