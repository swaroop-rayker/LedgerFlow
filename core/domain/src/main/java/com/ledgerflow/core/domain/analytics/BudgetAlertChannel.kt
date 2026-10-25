package com.ledgerflow.core.domain.analytics

/**
 * Whether budget alerts can reach the user (V2-PLAN §3.4).
 *
 * **There is no in-app switch for budget alerts, deliberately.** Android's
 * "Budget alerts" notification channel is the switch, and a second one in the
 * app would be two sources of truth that disagree the first time the user
 * mutes the channel from the shade. Settings shows the channel's state and
 * opens it.
 */
public enum class AlertChannelState {
    ON,

    /** The "Budget alerts" channel is turned off. */
    CHANNEL_OFF,

    /** All of LedgerFlow's notifications are off, or not yet permitted. */
    APP_OFF,
}

/** Reads [AlertChannelState]. Implemented beside the channel, in `:feature:budget`. */
public fun interface BudgetAlertChannel {
    public fun state(): AlertChannelState
}
