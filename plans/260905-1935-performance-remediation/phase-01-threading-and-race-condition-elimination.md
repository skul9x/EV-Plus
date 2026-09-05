# Phase 01: Threading & Race Condition Elimination (PERF-02)

Status: ✅ Completed  
Dependencies: None

## Objective
Eliminate coroutine lifecycle race conditions, nested job null assignments, and dispatcher desynchronization between background I/O operations and CPU filtering across [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt) and [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt).

## Requirements

### Functional
- [x] In [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt), assign `favoritesLoadJob` directly at the top-level of the `init` block:
  ```kotlin
  favoritesLoadJob = viewModelScope.launch(ioDispatcher) {
      if (repository.firestoreFavoritesRepository != null) {
          doFetchFavorites()
      } else {
          val loggedIn = authEngine.checkLoggedInAsync(ioDispatcher)
          if (loggedIn) {
              doFetchFavorites()
          } else {
              _uiState.value = FavoritesUiState.LoggedOut
          }
      }
  }
  ```
- [x] `FavoritesViewModel.initialLoadJob` (alias for `favoritesLoadJob`) must be non-null immediately upon ViewModel instantiation.
- [x] Invoking `initialLoadJob?.join()` must deterministically wait until the initial data load finishes and state reaches `FavoritesUiState.Success` or `FavoritesUiState.LoggedOut`.
- [x] In [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt), change `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` to default to `ioDispatcher` (e.g. `defaultDispatcher: CoroutineDispatcher = ioDispatcher`).
- [x] Ensure `NearbyViewModel.provideFactory` also defaults `defaultDispatcher` to `ioDispatcher`, allowing unit test harnesses injecting `ioDispatcher = testDispatcher` to automatically unify CPU filtering and I/O scheduling without races or timeouts.

### Non-Functional
- [x] Zero main-thread blocking during initial cache warming and station ranking.
- [x] 100% deterministic test execution on standard JVM test dispatchers (`advanceUntilIdle()`) without timing flakes or arbitrary delays.

## Implementation Steps
1. [x] Refactor [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt):
   - Replace nested launch in `init` with root-level `favoritesLoadJob = viewModelScope.launch(ioDispatcher) { ... }`.
   - Call inner loading logic directly or await `fetchFavorites().join()`.
   - Ensure `favoritesLoadJob` is non-null from the moment `FavoritesViewModel` constructor returns.
2. [x] Refactor [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt):
   - Update constructor parameter: `defaultDispatcher: CoroutineDispatcher = ioDispatcher`.
   - Update `provideFactory` parameter: `defaultDispatcher: CoroutineDispatcher = ioDispatcher`.
3. [x] Create the single comprehensive test file:
   - [ViewModelThreadingAndRaceConditionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/viewmodel/ViewModelThreadingAndRaceConditionTest.kt)
4. [x] Run the verification command to confirm all tests in the single file pass.

## Files to Create/Modify
- `[MODIFY]` [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt) - Synchronize root `favoritesLoadJob` assignment and awaiting in `init`.
- `[MODIFY]` [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt) - Make `defaultDispatcher` default to `ioDispatcher` in constructor and factory.
- `[NEW]` [ViewModelThreadingAndRaceConditionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/viewmodel/ViewModelThreadingAndRaceConditionTest.kt) - Single comprehensive test for Phase 01.

## Test Criteria (Single Comprehensive Test File)
- **Target Test File**: [ViewModelThreadingAndRaceConditionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/viewmodel/ViewModelThreadingAndRaceConditionTest.kt)
- **Verification Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.viewmodel.ViewModelThreadingAndRaceConditionTest"`
- Test cases included within the single file:
  - `testFavoritesViewModel_initialLoadJob_isNonNullImmediately_andAwaitingCompletesFlow`: Confirms `initialLoadJob` is immediately non-null and joining transitions state deterministically to `FavoritesUiState.Success`.
  - `testFavoritesViewModel_concurrentLogout_cancelsActiveJobsCleanly`: Confirms logout cleanly cancels in-flight jobs without leaks or unhandled exceptions.
  - `testNearbyViewModel_filteringAndRouting_runsDeterministicallyOnTestDispatcher`: Confirms station scanning and top 10 filtering execute deterministically without thread starvation when `advanceUntilIdle()` is called.

---
Next Phase: [Phase 02: Compose Recomposition & Scroll Optimization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1935-performance-remediation/phase-02-compose-recomposition-and-scroll-optimization.md)
