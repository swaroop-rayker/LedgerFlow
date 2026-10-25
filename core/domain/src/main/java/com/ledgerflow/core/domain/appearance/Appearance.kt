package com.ledgerflow.core.domain.appearance

import kotlinx.coroutines.flow.Flow

/**
 * Settings > Appearance (`docs/V2-PLAN.md` §3.2, owner 2026-10-24).
 *
 * Three device-local choices about how the app looks and moves. None of them
 * says anything about the user's money, and all three have to be known
 * **before the vault opens** -- the theme paints the unlock screen, and the
 * start screen is decided the moment the vault is open -- so they live in the
 * out-of-vault store (ADR-0030), not in `app_meta`.
 */
public data class AppearancePreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val animations: AnimationPreference = AnimationPreference.FULL,
    val startScreen: StartScreen = StartScreen.HOME,
)

/** Light or dark. The palette itself never changes (SPEC §9.1). */
public enum class ThemePreference {
    /** Whatever the phone is set to; what the app always did. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Whether to draw dark, given whether the phone is in dark mode. */
    public fun isDark(phoneIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> phoneIsDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * How much the app moves.
 *
 * **There is no "follow the phone" value, because [FULL] is that.** The phone's
 * "Remove animations" switch is an accessibility setting and always wins
 * (SPEC §9.5), so a user who picks Full on a phone with animations removed
 * still gets none; Full on any other phone is the app as it has always been.
 * A separate "System" option would therefore behave identically to Full in
 * every state, which is a choice that does nothing.
 */
public enum class AnimationPreference {
    /** Slides and fades, as the phone allows. The default. */
    FULL,

    /** Fades only: no screen slides sideways. */
    REDUCED,

    /** Nothing animates, as if the phone's animations were removed. */
    OFF,
    ;

    /**
     * What actually runs. [phoneAnimationsOn] is false when the phone's
     * animator duration scale is 0 ("Remove animations").
     */
    public fun motionLevel(phoneAnimationsOn: Boolean): MotionLevel = when {
        !phoneAnimationsOn -> MotionLevel.OFF
        else -> when (this) {
            FULL -> MotionLevel.FULL
            REDUCED -> MotionLevel.REDUCED
            OFF -> MotionLevel.OFF
        }
    }
}

/** The motion the app draws once the phone's own setting is taken into account. */
public enum class MotionLevel {
    FULL,

    /** Fade-throughs only (BUG57's rule still holds: never two screens at once). */
    REDUCED,

    /** No animation at all: every Compose animation is scaled to zero. */
    OFF,
}

/** The tab the app opens on. Read once per launch (see the shell). */
public enum class StartScreen {
    HOME,
    LEDGER,
    ANALYTICS,
}

/**
 * Where [AppearancePreferences] are kept: `:core:datastore` (ADR-0030).
 *
 * A port here because `:feature:settings` writes it and `:app` reads it, and
 * features never depend on features.
 */
public interface AppearanceStore {

    /** The current choices; defaults until the file is first written. */
    public val preferences: Flow<AppearancePreferences>

    public suspend fun setTheme(theme: ThemePreference)

    public suspend fun setAnimations(animations: AnimationPreference)

    public suspend fun setStartScreen(startScreen: StartScreen)
}

/**
 * The phone's own animation setting (`Settings.Global.ANIMATOR_DURATION_SCALE`).
 *
 * A port so the Settings screen and the activity read it through one place,
 * and so a test can say "animations removed" without a device.
 */
public interface PhoneMotionSettings {

    /** 1.0 normally; 0.0 when the phone has "Remove animations" on. */
    public fun animatorDurationScale(): Float
}
