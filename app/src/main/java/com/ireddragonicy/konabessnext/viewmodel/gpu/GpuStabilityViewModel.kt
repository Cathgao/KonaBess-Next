package com.ireddragonicy.konabessnext.viewmodel.gpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ireddragonicy.konabessnext.core.model.DomainResult
import com.ireddragonicy.konabessnext.model.gpu.FailureReason
import com.ireddragonicy.konabessnext.model.gpu.GpuSample
import com.ireddragonicy.konabessnext.model.gpu.GpuStabilityUiState
import com.ireddragonicy.konabessnext.model.gpu.GpuTestMode
import com.ireddragonicy.konabessnext.model.gpu.StabilityStatus
import com.ireddragonicy.konabessnext.repository.GpuStabilityRepository
import com.ireddragonicy.konabessnext.repository.SettingsRepository
import com.ireddragonicy.konabessnext.repository.ShellRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates the GPU stability test lifecycle:
 *  - fetches the active GPU frequency list (root sysfs probe);
 *  - decides between pinning-sweep (root required) and free-run (no root);
 *  - starts / stops the chosen mode;
 *  - exposes a [StateFlow] of [GpuStabilityUiState] consumed by `GpuStabilityScreen`;
 *  - restores GPU governor and limits when the test ends (success, failure or abort).
 */
@HiltViewModel
class GpuStabilityViewModel @Inject constructor(
    private val stabilityRepository: GpuStabilityRepository,
    private val shellRepository: ShellRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GpuStabilityUiState())
    val uiState: StateFlow<GpuStabilityUiState> = _uiState.asStateFlow()

    private var sweepJob: Job? = null
    private var elapsedJob: Job? = null

    init {
        // React to root-mode toggle changes from Settings. The app uses a
        // HorizontalPager for tab navigation, so the screen Composable is
        // always alive and lifecycle events never fire when the user switches
        // tabs. Subscribing to the prefs change is the only reliable way to
        // pick up a toggle made on the Settings tab.
        viewModelScope.launch {
            settingsRepository.rootModeFlow().collect {
                refreshPinningCapabilityInternal()
                if (_uiState.value.isRootPinningCapable) {
                    refreshFrequenciesInternal()
                } else {
                    // No longer pinning-capable: drop any stale frequency list
                    // so the UI doesn't keep showing numbers for a mode we
                    // can't run.
                    _uiState.update { it.copy(activeFrequenciesHz = emptyList()) }
                }
            }
        }
    }

    /**
     * Compute the runtime capability: true only when the user has root mode
     * toggled on in settings **and** the device actually grants root. Updates
     * [GpuStabilityUiState.mode] and `isRootPinningCapable` accordingly.
     *
     * Public so the UI layer can request a re-evaluation when the screen
     * becomes visible (e.g. after the user toggles Root Mode in Settings and
     * navigates back here). Safe to call from any thread; the underlying
     * shell ping is suspend.
     */
    fun recomputeCapability() {
        viewModelScope.launch { refreshPinningCapabilityInternal() }
    }

    private suspend fun refreshPinningCapabilityInternal() {
        val toggleOn = settingsRepository.isRootMode()
        val shellOk = if (toggleOn) shellRepository.isRootAvailable() else false
        val capable = toggleOn && shellOk
        _uiState.update { current ->
            current.copy(
                mode = if (capable) GpuTestMode.Pinning else GpuTestMode.Freerun,
                isRootPinningCapable = capable,
            )
        }
    }

    /**
     * Re-probe the kernel-reported GPU frequency list. Also re-evaluates the
     * pinning capability, since the user may have toggled Root Mode since the
     * last call. Only meaningful in pinning mode (the probe itself requires
     * root).
     */
    fun refreshFrequencies() {
        viewModelScope.launch {
            refreshPinningCapabilityInternal()
            if (_uiState.value.isRootPinningCapable) {
                refreshFrequenciesInternal()
            }
        }
    }

    private suspend fun refreshFrequenciesInternal() {
        val result = stabilityRepository.loadActiveFrequencies()
        _uiState.update { current ->
            when (result) {
                is DomainResult.Success -> current.copy(
                    activeFrequenciesHz = result.data,
                    failureMessage = null,
                )
                is DomainResult.Failure -> current.copy(
                    activeFrequenciesHz = emptyList(),
                    failureMessage = result.error.message,
                )
            }
        }
    }

    /**
     * Configure the per-point duration. Persisted only in memory; defaults back to
     * 60 s on process death. No-op in freerun mode (the stepper is hidden there).
     */
    fun setDurationPerPoint(seconds: Int) {
        val safe = seconds.coerceIn(5, 600)
        _uiState.update { it.copy(durationPerPointSec = safe) }
    }

    /** Configure the throttling tolerance. Below [ratioPct]% of the target counts as throttled. */
    fun setThrottlingRatio(ratioPct: Int) {
        val safe = ratioPct.coerceIn(20, 100)
        _uiState.update { it.copy(throttlingRatioPct = safe) }
    }

    /** Begin the test. Dispatches to pinning-sweep or free-run based on capability. */
    fun startTest() {
        val snapshot = _uiState.value
        if (snapshot.status == StabilityStatus.Running) return
        // Always re-verify the pinning capability *synchronously inside this
        // dispatch*. The cached UI flag can be stale if the user toggled Root
        // Mode in Settings and tapped Start before the recompute has settled.
        // Doing the ping here guarantees we never enter pinning mode without a
        // confirmed root shell.
        viewModelScope.launch {
            refreshPinningCapabilityInternal()
            val fresh = _uiState.value
            if (fresh.status != StabilityStatus.Running) {
                if (fresh.isRootPinningCapable) {
                    startPinningTest()
                } else {
                    startFreeRunTest()
                }
            }
        }
    }

    private fun startPinningTest() {
        val snapshot = _uiState.value
        if (snapshot.activeFrequenciesHz.isEmpty()) {
            _uiState.update { it.copy(failureReason = FailureReason.NoFrequencies) }
            return
        }
        _uiState.update {
            it.copy(
                status = StabilityStatus.Running,
                results = emptyList(),
                currentTargetHz = it.activeFrequenciesHz.first(),
                elapsedSec = 0,
                currentSamples = emptyList(),
                failureMessage = null,
                maxGpuTempC = null,
                // Reset dialog state for the new run; if this run ends in a
                // terminal status, the dialog will be (re-)armed by handleEvent.
                resultsDialogStatus = null,
                resultsDialogDismissed = true,
            )
        }
        startElapsedTicker()
        // Build the state from the live snapshot so the per-point duration
        // reflects whatever the user last set (and not a stale copy captured
        // before the run started).
        val currentSnapshot = _uiState.value
        sweepJob = viewModelScope.launch {
            try {
                stabilityRepository.runStabilityTest(currentSnapshot).collect { event ->
                    handleEvent(event)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(status = StabilityStatus.Aborted) }
                stopElapsedTicker()
                throw ce
            }
        }
    }

    private fun startFreeRunTest() {
        _uiState.update {
            it.copy(
                status = StabilityStatus.Running,
                results = emptyList(),
                currentTargetHz = null,
                elapsedSec = 0,
                currentSamples = emptyList(),
                failureMessage = null,
                maxGpuTempC = null,
                resultsDialogStatus = null,
                resultsDialogDismissed = true,
            )
        }
        val currentSnapshot = _uiState.value
        sweepJob = viewModelScope.launch {
            try {
                stabilityRepository.runFreeStressTest(currentSnapshot).collect { event ->
                    handleEvent(event)
                }
                // Flow completed cleanly (user pressed stop). The repository
                // already emitted Aborted; transition back to Idle so the UI
                // shows the post-run state and the chart stays visible.
                _uiState.update {
                    it.copy(
                        status = StabilityStatus.Idle,
                        currentTargetHz = null,
                        elapsedSec = 0,
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(
                        status = StabilityStatus.Idle,
                        currentTargetHz = null,
                        elapsedSec = 0,
                    )
                }
                stopElapsedTicker()
                throw ce
            }
        }
    }

    /** Stop the sweep early. Original GPU limits are restored by the repository. */
    fun stopTest() {
        if (_uiState.value.status != StabilityStatus.Running) return
        viewModelScope.launch { stabilityRepository.cancelTest() }
    }

    /** Reset the results list. Used by the "Clear results" button (pinning mode only). */
    fun clearResults() {
        _uiState.update {
            it.copy(
                results = emptyList(),
                failureMessage = null,
                currentSamples = emptyList(),
                elapsedSec = 0,
                currentTargetHz = null,
                status = StabilityStatus.Idle,
                maxGpuTempC = null,
                resultsDialogStatus = null,
                resultsDialogDismissed = true,
            )
        }
    }

    /**
     * User dismissed the results dialog (tapped OK or the area outside).
     * Mark it dismissed so we don't re-show it for the same terminal status.
     */
    fun dismissResultsDialog() {
        _uiState.update { it.copy(resultsDialogDismissed = true) }
    }

    /** Called from the GL renderer when the surface is created. */
    fun onRendererReady() = Unit

    /** Called from the GL renderer when `glGetError()` reports a non-zero value. */
    fun onRenderError(code: Int) {
        stabilityRepository.reportRenderError(code)
    }

    private fun handleEvent(event: GpuStabilityRepository.TestEvent) {
        when (event) {
            is GpuStabilityRepository.TestEvent.SampleCollected -> {
                val sample = event.sample
                val maxTemp = sequenceOf(_uiState.value.maxGpuTempC, sample.temperatureC)
                    .filterNotNull().maxOrNull()
                _uiState.update { current ->
                    current.copy(
                        currentSamples = current.currentSamples + sample,
                        maxGpuTempC = maxTemp,
                    )
                }
            }
            is GpuStabilityRepository.TestEvent.PointFinished -> {
                val result = event.result
                val candidateTemps = listOfNotNull(_uiState.value.maxGpuTempC) +
                    result.samples.mapNotNull { it.temperatureC }
                val maxTemp: Float? = candidateTemps.maxOrNull()
                _uiState.update { current ->
                    val nextIndex = current.results.size + 1
                    val nextTarget = current.activeFrequenciesHz.getOrNull(nextIndex)
                    current.copy(
                        results = current.results + result,
                        currentTargetHz = nextTarget,
                        elapsedSec = 0,
                        // Don't reset currentSamples here — we want the chart
                        // to show the full history of the run, not just the
                        // current point. The list is cleared at startTest().
                        maxGpuTempC = maxTemp,
                    )
                }
            }
            is GpuStabilityRepository.TestEvent.Failed -> {
                stopElapsedTicker()
                _uiState.update {
                    it.copy(
                        status = StabilityStatus.Failed,
                        failureReason = event.reason,
                        currentTargetHz = null,
                        elapsedSec = 0,
                        // Arm the results dialog for this terminal status.
                        // It's only shown when there are results to show — the
                        // UI side checks that.
                        resultsDialogStatus = StabilityStatus.Failed,
                        resultsDialogDismissed = it.results.isEmpty(),
                    )
                }
            }
            GpuStabilityRepository.TestEvent.AllCompleted -> {
                stopElapsedTicker()
                _uiState.update {
                    val allPassed = it.results.isNotEmpty() && it.results.all { r -> r.passed }
                    val terminal = if (allPassed) StabilityStatus.Completed else StabilityStatus.Failed
                    it.copy(
                        status = terminal,
                        currentTargetHz = null,
                        elapsedSec = 0,
                        resultsDialogStatus = terminal,
                        resultsDialogDismissed = it.results.isEmpty(),
                    )
                }
            }
            GpuStabilityRepository.TestEvent.Aborted -> {
                stopElapsedTicker()
                if (_uiState.value.mode == GpuTestMode.Freerun) {
                    // In freerun mode "aborted" simply means the user stopped
                    // the test. Snap back to Idle so the Start button reappears
                    // and the chart remains visible.
                    _uiState.update {
                        it.copy(
                            status = StabilityStatus.Idle,
                            currentTargetHz = null,
                            elapsedSec = 0,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            status = StabilityStatus.Aborted,
                            resultsDialogStatus = StabilityStatus.Aborted,
                            resultsDialogDismissed = it.results.isEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Tick the per-second elapsed counter shown above the GLSurfaceView. Pinning mode only. */
    private fun startElapsedTicker() {
        stopElapsedTicker()
        elapsedJob = viewModelScope.launch {
            while (_uiState.value.status == StabilityStatus.Running) {
                delay(1_000L)
                _uiState.update { it.copy(elapsedSec = (it.elapsedSec + 1).coerceAtMost(it.durationPerPointSec)) }
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

    /**
     * Append the latest GPU sample to the running point. Called from a polling job in
     * the UI layer (because samples arrive at ~1 Hz and we don't want them to live
     * inside the repository's coroutine).
     */
    fun ingestSample(sample: GpuSample) {
        _uiState.update { it.copy(currentSamples = it.currentSamples + sample) }
    }

    override fun onCleared() {
        super.onCleared()
        sweepJob?.cancel()
        elapsedJob?.cancel()
        viewModelScope.launch { stabilityRepository.cancelTest() }
    }
}