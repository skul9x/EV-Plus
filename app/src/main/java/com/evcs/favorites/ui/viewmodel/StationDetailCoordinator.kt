package com.evcs.favorites.ui.viewmodel

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationTelemetryParser
import com.evcs.favorites.ui.state.StationDetailUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinator orchestrating the on-demand EVCS station detail telemetry pipeline.
 *
 * Provides:
 * - Instant bottom sheet opening (0ms) with static ports.
 * - Stage 1 (~200ms): Token handshake, live charging ports, ticker sanitization, community rating.
 * - Stage 2 (~500-1200ms): Socket.io 24h history and usage statistics with 4s timeout.
 * - Stage 3: Fire-and-forget sync ping.
 * - Clean lifecycle cancellation upon sheet dismissal with zero background overhead.
 */
class StationDetailCoordinator(
    private val coroutineScope: CoroutineScope,
    private val telemetryRepository: EvcsTelemetryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val statsTimeoutMs: Long = 4000L
) {

    private val _stationDetailState = MutableStateFlow(StationDetailUiState())
    val stationDetailState: StateFlow<StationDetailUiState> = _stationDetailState.asStateFlow()

    var activeJob: Job? = null
        private set

    /**
     * Derives initial port statuses from station's static powers or connector definitions.
     */
    fun deriveInitialPortStatuses(station: Station): List<StationPortStatus> {
        if (station.powers.isNotEmpty()) {
            val hasPlugs = station.powers.any { it.totalPlugs > 0 }
            if (hasPlugs) {
                return station.powers.filter { it.typeWatts > 0 }.map { power ->
                    val kw = (power.typeWatts / 1000).toInt()
                    val total = if (power.totalPlugs > 0) power.totalPlugs else 1
                    val avail = if (power.totalPlugs > 0) power.availablePlugs else total
                    StationPortStatus(
                        kw = kw,
                        availablePorts = avail,
                        totalPorts = total,
                        busyCount = maxOf(0, total - avail)
                    )
                }.sortedByDescending { it.kw }
            } else {
                return StationTelemetryParser.derivePortStatuses(station.powers, emptyMap())
            }
        }
        return StationTelemetryParser.derivePortStatuses(station.connectors, emptyMap())
    }

    /**
     * Selects a station for detail inspection, triggering synchronous state emission (0ms)
     * and launching asynchronous enrichment stages.
     */
    fun selectStationForDetail(station: Station): Job {
        activeJob?.cancel()

        // Instant Opening (0ms) synchronously
        val initialPorts = deriveInitialPortStatuses(station)
        _stationDetailState.value = StationDetailUiState(
            station = station,
            isLoadingTelemetry = true,
            isLoadingStats = true,
            isRefreshing = false,
            portStatuses = initialPorts
        )

        val job = coroutineScope.launch(mainDispatcher) {
            loadStationDetails(station)
        }
        activeJob = job
        return job
    }

    /**
     * Manually triggers refresh of live charging telemetry and 24h usage statistics.
     */
    fun refreshStationDetail(): Job? {
        val currentStation = _stationDetailState.value.station ?: return null
        activeJob?.cancel()

        _stationDetailState.update {
            it.copy(
                isRefreshing = true,
                isLoadingTelemetry = true,
                isLoadingStats = true,
                error = null
            )
        }

        val job = coroutineScope.launch(mainDispatcher) {
            loadStationDetails(currentStation)
        }
        activeJob = job
        return job
    }

    /**
     * Dismisses the detail sheet, cancelling any active network/socket jobs and resetting state.
     */
    fun dismissStationDetail() {
        activeJob?.cancel()
        activeJob = null
        _stationDetailState.value = StationDetailUiState()
    }

    private suspend fun loadStationDetails(station: Station) {
        try {
            // Stage 1a: Ephemeral tokens and rating handshake
            val tokensResult = withContext(ioDispatcher) {
                telemetryRepository.fetchStationTokens(station)
            }
            if (tokensResult.isFailure) {
                val ex = tokensResult.exceptionOrNull()
                if (ex is CancellationException) throw ex
                _stationDetailState.update {
                    it.copy(
                        isLoadingTelemetry = false,
                        isLoadingStats = false,
                        isRefreshing = false,
                        error = ex?.message ?: "Failed to load station telemetry"
                    )
                }
                return
            }

            val tokens = tokensResult.getOrThrow()
            _stationDetailState.update {
                it.copy(rating = tokens.rating)
            }

            // Concurrently execute Stage 1b (Live Charging) and Stage 2 (24h Stats)
            coroutineScope {
                // Stage 1b: Live Charging Telemetry (~200ms)
                launch {
                    try {
                        val chargingResult = withContext(ioDispatcher) {
                            telemetryRepository.fetchLiveCharging(station.id, tokens.chargeToken)
                        }
                        if (chargingResult.isSuccess) {
                            val telemetry = chargingResult.getOrThrow()
                            val livePorts = if (station.powers.isNotEmpty()) {
                                StationTelemetryParser.derivePortStatuses(station.powers, telemetry.busyByKw)
                            } else {
                                StationTelemetryParser.derivePortStatuses(station.connectors, telemetry.busyByKw)
                            }
                            _stationDetailState.update {
                                it.copy(
                                    isLoadingTelemetry = false,
                                    telemetry = telemetry,
                                    cleanForecast = telemetry.cleanForecast,
                                    portStatuses = livePorts
                                )
                            }

                            // Stage 3: Telemetry Ping fire-and-forget
                            val totalBusy = livePorts.sumOf { it.busyCount }
                            this@StationDetailCoordinator.coroutineScope.launch(ioDispatcher) {
                                try {
                                    telemetryRepository.sendTelemetryUpdate(station.id, tokens.apiToken, totalBusy)
                                } catch (_: Exception) {
                                    // Ignored: fire-and-forget
                                }
                            }
                        } else {
                            _stationDetailState.update {
                                it.copy(isLoadingTelemetry = false)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _stationDetailState.update {
                            it.copy(isLoadingTelemetry = false)
                        }
                    }
                }

                // Stage 2: 24h Usage Stats (~500-1200ms) with timeout
                launch {
                    try {
                        val stats = withTimeoutOrNull(statsTimeoutMs) {
                            val historyResult = withContext(ioDispatcher) {
                                telemetryRepository.fetch24hHistory(station.id, tokens.apiToken)
                            }
                            if (historyResult.isSuccess) {
                                val points = historyResult.getOrNull().orEmpty()
                                val totalPorts = if (station.totalPlugs > 0) {
                                    station.totalPlugs
                                } else {
                                    val currentPorts = _stationDetailState.value.portStatuses
                                    if (currentPorts.isNotEmpty()) currentPorts.sumOf { it.totalPorts } else 1
                                }
                                telemetryRepository.calculate24hStats(points, totalPorts)
                            } else {
                                null
                            }
                        }
                        _stationDetailState.update {
                            it.copy(
                                isLoadingStats = false,
                                stats24h = stats
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _stationDetailState.update {
                            it.copy(isLoadingStats = false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _stationDetailState.update {
                it.copy(
                    isLoadingTelemetry = false,
                    isLoadingStats = false,
                    isRefreshing = false,
                    error = e.message
                )
            }
        } finally {
            _stationDetailState.update {
                it.copy(isRefreshing = false)
            }
        }
    }
}
