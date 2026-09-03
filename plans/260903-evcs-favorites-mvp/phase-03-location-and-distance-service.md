# Phase 03: Location & Distance Service

Status: ✅ Completed  
Dependencies: Phase 02  

---

## 1. Objective
Implement GPS location services using Google Play Services `FusedLocationProviderClient`, high-precision Haversine distance calculation, human-readable distance formatting (e.g., "850 m", "3.4 km"), and automated nearest-first sorting of favorite charging stations.

---

## 2. Requirements

### Functional
- Implement `LocationService`:
  - Wraps `FusedLocationProviderClient` with runtime permission checks.
  - Emits user location updates via Kotlin `StateFlow<Location?>`.
  - Provides latest known coordinates to pass into `EvcsRepository` for localized station search.
- Implement `DistanceCalculator`:
  - Great-circle distance calculation using Haversine formula between user coordinates and station coordinates.
  - Human-friendly string formatting (meters if < 1 km, e.g. "850 m"; kilometers with 1 decimal place if >= 1 km, e.g. "3.4 km").
- Implement sorting:
  - Takes favorite stations list and user location, returns stations sorted by distance ascending.

### Non-Functional
- Low battery consumption (request single fresh location or balanced power updates).
- Thread-safe, non-blocking calculations.

---

## 3. Implementation Steps
1. Create `DistanceCalculator` with mathematical Haversine algorithm and formatting rules.
2. Implement `LocationService` interfacing with Android GPS / fused location provider.
3. Combine distance computation into station domain models to attach real-time distance metrics.
4. Implement the single comprehensive test file: `src/test/java/com/evcs/favorites/DistanceCalculatorTest.kt`.

---

## 4. Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/domain/location/DistanceCalculator.kt`: Haversine calculation and formatting logic.
- `app/src/main/java/com/evcs/favorites/domain/location/LocationService.kt`: Android location wrapper.
- `app/src/test/java/com/evcs/favorites/DistanceCalculatorTest.kt`: Single verification test for this phase.

---

## 5. Single Verification Test
- **File:** `app/src/test/java/com/evcs/favorites/DistanceCalculatorTest.kt`
- **Scope:**
  - Validates Haversine distance calculation accuracy across known GPS coordinates in Vietnam (e.g., Hanoi to Da Nang, Ho Chi Minh City locations).
  - Validates formatting thresholds ("450 m", "1.2 km", "15.0 km").
  - Validates list sorting logic ensuring closest charging stations are ordered first.

---
Next Phase: [phase-04-compose-ui-and-viewmodel.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-evcs-favorites-mvp/phase-04-compose-ui-and-viewmodel.md)
