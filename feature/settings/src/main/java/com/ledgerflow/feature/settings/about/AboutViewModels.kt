package com.ledgerflow.feature.settings.about

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.designsystem.format.ProseBlock
import com.ledgerflow.core.domain.about.AppEdition
import com.ledgerflow.core.domain.about.AppInfo
import com.ledgerflow.core.domain.about.AppInfoReader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What's new, the privacy policy or the terms: null blocks until read. */
@Immutable
public data class AboutDocumentUiState(val document: AboutDocument, val blocks: List<ProseBlock>? = null)

@HiltViewModel
public class AboutDocumentViewModel @Inject constructor(
    private val texts: AboutTexts,
) : ViewModel() {

    private val _state = MutableStateFlow<AboutDocumentUiState?>(null)
    public val state: StateFlow<AboutDocumentUiState?> = _state.asStateFlow()

    /** Reads [document] once; the route calls it with its argument. */
    public fun load(document: AboutDocument) {
        if (_state.value?.document == document) return
        _state.value = AboutDocumentUiState(document)
        viewModelScope.launch {
            val blocks = texts.document(document)
            _state.update { it?.copy(blocks = blocks) }
        }
    }
}

/** Open-source licences: every component, by name; null until read. */
@Immutable
public data class LicencesUiState(val components: List<LicensedComponent>? = null)

@HiltViewModel
public class LicencesViewModel @Inject constructor(
    texts: AboutTexts,
    source: LicenceListSource,
) : ViewModel() {

    private val _state = MutableStateFlow(LicencesUiState())
    public val state: StateFlow<LicencesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = LicencesUiState(texts.licences(source)) }
    }
}

/** One component's licences, each with its bundled text when there is one. */
@Immutable
public data class LicenceUiState(
    val component: LicensedComponent,
    val texts: Map<String, List<ProseBlock>> = emptyMap(),
)

@HiltViewModel
public class LicenceViewModel @Inject constructor(
    private val texts: AboutTexts,
    private val source: LicenceListSource,
) : ViewModel() {

    private val _state = MutableStateFlow<LicenceUiState?>(null)
    public val state: StateFlow<LicenceUiState?> = _state.asStateFlow()

    /** Finds [componentId] and reads its licence texts; the route calls it with its argument. */
    public fun load(componentId: String) {
        if (_state.value?.component?.id == componentId) return
        viewModelScope.launch {
            val component = texts.licences(source).firstOrNull { it.id == componentId } ?: return@launch
            _state.value = LicenceUiState(component)
            val bodies = component.licences.mapNotNull { licence ->
                licence.asset?.let { licence.id to texts.licence(it) }
            }.toMap()
            _state.update { it?.copy(texts = bodies) }
        }
    }
}

/** Help & FAQ: the questions, and which are open. */
@Immutable
public data class HelpUiState(val items: List<HelpItem>? = null, val open: Set<Int> = emptySet())

@HiltViewModel
public class HelpViewModel @Inject constructor(texts: AboutTexts) : ViewModel() {

    private val _state = MutableStateFlow(HelpUiState())
    public val state: StateFlow<HelpUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(items = texts.help()) } }
    }

    public fun toggle(index: Int) {
        _state.update { it.copy(open = if (index in it.open) it.open - index else it.open + index) }
    }
}

/**
 * Report a bug (V2-PLAN §3.8): what the user writes, and the device details
 * that go with it, **shown in full before anything leaves the app**. The app
 * sends nothing: Share hands the text to an app the user picks (Law 6).
 */
@Immutable
public data class ReportBugUiState(
    val description: String = "",
    val details: List<String> = emptyList(),
) {
    /** Exactly what Share sends: the screen shows these same lines. */
    public val report: String get() = bugReportText(description, details)
    public val canShare: Boolean get() = description.isNotBlank()
}

@HiltViewModel
public class ReportBugViewModel @Inject constructor(
    appInfo: AppInfoReader,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val details = MutableStateFlow(deviceDetails(appInfo.read()))

    /** Kept in the saved state, so a process death while the user is in the share sheet loses nothing. */
    private val description = saved.getStateFlow(KEY_DESCRIPTION, "")

    public val state: StateFlow<ReportBugUiState> =
        combine(description, details) { text, lines -> ReportBugUiState(text, lines) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReportBugUiState(description.value, details.value))

    public fun describe(text: String) {
        saved[KEY_DESCRIPTION] = text
    }

    private companion object {
        const val KEY_DESCRIPTION = "description"
    }
}

/** The Version row's words: "0.1.1 (2)". */
public fun versionLabel(info: AppInfo): String = "${info.versionName} (${info.versionCode})"

/** Which app this is, in the stores' words, and whether it is a development build. */
public fun editionLabel(info: AppInfo): String {
    val store = when (info.edition) {
        AppEdition.FULL -> "Galaxy Store and repository build"
        AppEdition.PLAY -> "Google Play build"
    }
    return if (info.debuggable) "$store, debug" else store
}

/** The lines a report carries about the device: nothing from the vault, nothing the user did not see. */
internal fun deviceDetails(info: AppInfo): List<String> = listOf(
    "App: LedgerFlow ${versionLabel(info)}, ${editionLabel(info)}",
    "Android: ${info.androidRelease} (API ${info.sdkInt})",
    "Phone: ${info.device}",
    "Language: ${info.languageTag}",
)

internal fun bugReportText(description: String, details: List<String>): String =
    buildString {
        appendLine("What happened:")
        appendLine(description.trim())
        appendLine()
        details.forEach { appendLine(it) }
    }.trimEnd()
