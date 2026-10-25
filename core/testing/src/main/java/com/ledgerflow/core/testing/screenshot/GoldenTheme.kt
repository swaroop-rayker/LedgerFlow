package com.ledgerflow.core.testing.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Which theme a golden is captured in (DESIGN_SYSTEM_PLAN §6.3).
 *
 * Every screen golden is captured twice: as the test is written, and again
 * with the phone in night mode, saved beside it with a `-dark` suffix. Dark is
 * LedgerFlow's default theme (SPEC §9.1), and until this existed exactly one
 * golden in the repository was dark.
 *
 * **Night mode, not `LfTheme(darkTheme = true)`.** This module does not depend
 * on `:core:designsystem`, and it does not need to: `LfTheme` follows
 * `isSystemInDarkTheme()`, which reads [LocalConfiguration]. Turning the
 * configuration to night is what a phone in dark mode does, so the dark golden
 * also covers anything else that follows the system setting.
 *
 * **Why a rule, not a Robolectric qualifier.** A class-level `+night` qualifier
 * would be replaced by every method-level `@Config(qualifiers = PHONE_2X)`, so
 * the 2x goldens would quietly come out light. The rule is applied at run time
 * and cannot be overridden that way.
 */
public object GoldenTheme {

    @Volatile
    private var dark: Boolean = false

    /** True while a [DarkGoldensRule] is running the test. */
    public val isDark: Boolean get() = dark

    /** `home-quiet-1x` in the light run, `home-quiet-1x-dark` in the dark one. */
    public fun fileName(name: String): String = if (dark) "$name$DARK_SUFFIX" else name

    /** Renders [content] in night mode during a dark run, unchanged otherwise. */
    @Composable
    public fun Provide(content: @Composable () -> Unit) {
        if (!dark) {
            content()
            return
        }
        val base = LocalConfiguration.current
        val night = remember(base) {
            Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
        }
        CompositionLocalProvider(LocalConfiguration provides night, content = content)
    }

    internal fun set(value: Boolean) {
        dark = value
    }
}

/** The suffix a dark golden carries. `goldenThemePairsCheck` pairs files by it. */
public const val DARK_SUFFIX: String = "-dark"

/**
 * Runs a test class's goldens in the dark theme.
 *
 * Used by a one-line subclass of each goldens test:
 *
 * ```
 * class DashboardScreenGoldensDarkTest : DashboardScreenGoldensTest() {
 *     @get:Rule val darkGoldens = DarkGoldensRule()
 * }
 * ```
 *
 * The subclass inherits every `@Test` and its `@Config`, so each light golden
 * gets a dark twin without a second copy of the test.
 */
public class DarkGoldensRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            GoldenTheme.set(true)
            try {
                base.evaluate()
            } finally {
                GoldenTheme.set(false)
            }
        }
    }
}
