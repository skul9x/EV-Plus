# Macrobenchmark & Baseline Profile Setup Guide

This guide details the architecture, configuration, and automation commands for measuring Android application performance and capturing Ahead-of-Time (AOT) baseline profiles using AndroidX Macrobenchmark.

---

## 1. Overview

**AndroidX Macrobenchmark** measures large-scope, end-to-end user interactions on physical Android devices.
Key targets:
- **Cold Startup Time**: Time taken from process creation to the first interactive frame drawn.
- **Jank / Frame Timing**: Frame drop percentage, 90th/95th/99th percentile frame rendering durations during list scrolling and navigation transitions.
- **Baseline Profile Generation**: Generating ART profile rules (`baseline-prof.txt`) to eliminate JIT compilation overhead during startup and critical user journeys.

---

## 2. Macrobenchmark Module Architecture

Android benchmarks require a dedicated test module (`:macrobenchmark`) separate from the main application (`:app`).

### Module Gradle Configuration (`macrobenchmark/build.gradle.kts`)
```kotlin
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.evplus.macrobenchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
}
```

---

## 3. Cold Startup Measurement

Cold startup measures the performance from when the OS process is created to the first frame rendered (`reportFullyDrawn` or UI rendered).

### Startup Benchmark Test Example
```kotlin
package com.evplus.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = "com.evplus.app",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        iterations = 10,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

### Execution Gradle Target
```bash
./gradlew :macrobenchmark:connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=com.evplus.macrobenchmark.ColdStartupBenchmark
```

---

## 4. Measuring JankStats and Frame Timing

Frame rendering performance measures UI smoothness during scrolling of `NearbyScreen` and `FavoritesScreen` station cards.

### Frame Timing Benchmark Test Example
```kotlin
package com.evplus.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StationListScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollNearbyStationList() = benchmarkRule.measureRepeated(
        packageName = "com.evplus.app",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res("station_list")), 5_000)
        }
    ) {
        val stationList = device.findObject(By.res("station_list"))
        stationList.setGestureMargin(device.displayWidth / 5)
        stationList.fling(Direction.DOWN)
        device.waitForIdle()
    }
}
```

### Execution Gradle Target
```bash
./gradlew :macrobenchmark:connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=com.evplus.macrobenchmark.StationListScrollBenchmark
```

---

## 5. Generating & Updating Baseline Profiles

Baseline profile generation traces execution during key journeys and outputs rules for Ahead-Of-Time compilation.

### Baseline Profile Generator Rule
```kotlin
package com.evplus.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = "com.evplus.app"
    ) {
        pressHome()
        startActivityAndWait()

        // 1. Nearby Screen Journey: list scroll & filter interaction
        device.wait(Until.hasObject(By.res("station_list")), 5_000)
        val stationList = device.findObject(By.res("station_list"))
        stationList?.fling(Direction.DOWN)
        device.waitForIdle()

        // 2. Open Station Detail Sheet
        val firstStation = device.findObject(By.res("station_card_0"))
        firstStation?.click()
        device.wait(Until.hasObject(By.res("station_detail_sheet")), 3_000)
        device.waitForIdle()

        // 3. Switch to Favorites Screen
        device.pressBack()
        val favoritesTab = device.findObject(By.text("Favorites"))
        favoritesTab?.click()
        device.waitForIdle()
    }
}
```

### Automation Gradle Command
```bash
./gradlew :macrobenchmark:generateBaselineProfile
```
The output file is written to `app/src/main/baseline-prof.txt`.

---

## 6. Verification and CI/CD Quality Gates

1. **Rule File Integrity**:
   Baseline profile rules in `app/src/main/baseline-prof.txt` must contain standard ART prefixes:
   - `HSPL`: Hot, Startup, Post-startup methods.
   - Wildcard rules for composables (`->*`).
2. **Automated Unit Verification**:
   The test `BaselineProfileAndBenchmarkConfigTest.kt` verifies profile syntax, rule volume (> 50 rules), and presence of all critical user journeys prior to deployment.
