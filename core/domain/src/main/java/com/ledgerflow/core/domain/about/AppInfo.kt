package com.ledgerflow.core.domain.about

/**
 * Settings > About's facts about this install (V2-PLAN §3.8): the version the
 * About page shows, and the device details a bug report carries *only after
 * the user has read them* (Report a bug; nothing is sent by the app, Law 6).
 *
 * Nothing here comes from the vault: no amount, merchant, message or setting.
 */
public data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val edition: AppEdition,
    val debuggable: Boolean,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
    val languageTag: String,
)

/** Which of the two apps this is (D-04): one reads SMS, the Play one cannot. */
public enum class AppEdition {
    /** `smsFull`: Galaxy Store and the repository's releases. */
    FULL,

    /** `playSafe`: Google Play, no SMS permission. */
    PLAY,
}

public fun interface AppInfoReader {
    public fun read(): AppInfo
}
