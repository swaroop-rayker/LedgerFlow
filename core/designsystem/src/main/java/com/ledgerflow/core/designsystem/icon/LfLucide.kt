// Path data copied from Lucide: a long line is one shape, and splitting it
// would make it no longer diffable against the source SVG.
@file:Suppress("MaxLineLength")

package com.ledgerflow.core.designsystem.icon

/*
 * Lucide's geometry for every icon the app draws (step F4, ADR-0031): the
 * line-icon family the Night Vault canvas drew in, 2 dp strokes with round
 * ends on a 24 dp grid.
 *
 * Copied, not depended on: 33 files from `lucide-icons/lucide` (`icons/<name>.svg`,
 * fetched 2026-10-28; `list-filter`, `chevron-down`, `database` and `tag`
 * 2026-10-29, step F5), converted to path data unchanged in shape -- `circle`,
 * `rect` and `polyline` written out as the equivalent paths. ISC licence, at
 * `assets/licenses/Lucide-ISC.txt`. A new icon is added here, from the same
 * source, so the family stays one.
 *
 * Internal: screens name icons by job through [LfIcons], never a Lucide name.
 */
internal object LfLucide {
    /** Lucide `house`. */
    val House: List<String> = listOf(
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )

    /** Lucide `list`. */
    val ListRows: List<String> = listOf(
        "M3 5h.01",
        "M3 12h.01",
        "M3 19h.01",
        "M8 5h13",
        "M8 12h13",
        "M8 19h13",
    )

    /** Lucide `chart-column`. */
    val ChartColumn: List<String> = listOf(
        "M3 3v16a2 2 0 0 0 2 2h16",
        "M18 17V9",
        "M13 17V5",
        "M8 17v-3",
    )

    /** Lucide `user-round`. */
    val UserRound: List<String> = listOf(
        "M7 8a5 5 0 1 0 10 0a5 5 0 1 0 -10 0z",
        "M20 21a8 8 0 0 0-16 0",
    )

    /** Lucide `plus`. */
    val Plus: List<String> = listOf(
        "M5 12h14",
        "M12 5v14",
    )

    /** Lucide `x`. */
    val X: List<String> = listOf(
        "M18 6 6 18",
        "m6 6 12 12",
    )

    /** Lucide `trash`. */
    val Trash: List<String> = listOf(
        "M10 11v6",
        "M14 11v6",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M3 6h18",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
    )

    /** Lucide `chevron-right`. */
    val ChevronRight: List<String> = listOf(
        "m9 18 6-6-6-6",
    )

    /** Lucide `chevron-left`: `chevron-right` mirrored, which is how Lucide draws it. */
    val ChevronLeft: List<String> = listOf(
        "m15 18-6-6 6-6",
    )

    /** Lucide `chevron-down`. */
    val ChevronDown: List<String> = listOf(
        "m6 9 6 6 6-6",
    )

    /** Lucide `list-filter`. */
    val ListFilter: List<String> = listOf(
        "M2 5h20",
        "M6 12h12",
        "M9 19h6",
    )

    /** Lucide `database`; its `ellipse` written out as the equivalent path. */
    val Database: List<String> = listOf(
        "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0z",
        "M3 5V19A9 3 0 0 0 21 19V5",
        "M3 12A9 3 0 0 0 21 12",
    )

    /** Lucide `tag`. Its 0.5-radius dot is filled in the source; stroked 2 dp it draws the same dot. */
    val Tag: List<String> = listOf(
        "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z",
        "M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0z",
    )

    /** Lucide `settings`. */
    val Settings: List<String> = listOf(
        "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
    )

    /** Lucide `inbox`. */
    val Inbox: List<String> = listOf(
        "M22 12L16 12L14 15L10 15L8 12L2 12",
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    )

    /** Lucide `wallet`. */
    val Wallet: List<String> = listOf(
        "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
        "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",
    )

    /** Lucide `utensils`. */
    val Utensils: List<String> = listOf(
        "M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2",
        "M7 2v20",
        "M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7",
    )

    /** Lucide `car`. */
    val Car: List<String> = listOf(
        "M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2",
        "M5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
        "M9 17h6",
        "M15 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
    )

    /** Lucide `receipt`. */
    val Receipt: List<String> = listOf(
        "M12 17V7",
        "M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8",
        "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z",
    )

    /** Lucide `shopping-bag`. */
    val ShoppingBag: List<String> = listOf(
        "M16 10a4 4 0 0 1-8 0",
        "M3.103 6.034h17.794",
        "M3.4 5.467a2 2 0 0 0-.4 1.2V20a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.667a2 2 0 0 0-.4-1.2l-2-2.667A2 2 0 0 0 17 2H7a2 2 0 0 0-1.6.8z",
    )

    /** Lucide `heart-pulse`. */
    val HeartPulse: List<String> = listOf(
        "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
        "M3.22 13H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27",
    )

    /** Lucide `clapperboard`. */
    val Clapperboard: List<String> = listOf(
        "m12.296 3.464 3.02 3.956",
        "M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3z",
        "M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        "m6.18 5.276 3.1 3.899",
    )

    /** Lucide `plane`. */
    val Plane: List<String> = listOf(
        "M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z",
    )

    /** Lucide `graduation-cap`. */
    val GraduationCap: List<String> = listOf(
        "M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z",
        "M22 10v6",
        "M6 12.5V16a6 3 0 0 0 12 0v-3.5",
    )

    /** Lucide `sparkles`. */
    val Sparkles: List<String> = listOf(
        "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
        "M20 2v4",
        "M22 4h-4",
        "M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
    )

    /** Lucide `users`. */
    val Users: List<String> = listOf(
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M16 3.128a4 4 0 0 1 0 7.744",
        "M22 21v-2a4 4 0 0 0-3-3.87",
        "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z",
    )

    /** Lucide `landmark`. */
    val Landmark: List<String> = listOf(
        "M10 18v-7",
        "M11.119 2.205a2 2 0 0 1 1.762 0l7.84 3.846A.5.5 0 0 1 20.5 7h-17a.5.5 0 0 1-.22-.949z",
        "M14 18v-7",
        "M18 18v-7",
        "M3 22h18",
        "M6 18v-7",
    )

    /** Lucide `circle-ellipsis`. */
    val CircleEllipsis: List<String> = listOf(
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z",
        "M17 12h.01",
        "M12 12h.01",
        "M7 12h.01",
    )

    /** Lucide `briefcase`. */
    val Briefcase: List<String> = listOf(
        "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16",
        "M4 6h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z",
    )

    /** Lucide `store`. */
    val Store: List<String> = listOf(
        "M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5",
        "M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244",
        "M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05",
    )

    /** Lucide `trending-up`. */
    val TrendingUp: List<String> = listOf(
        "M16 7h6v6",
        "m22 7-8.5 8.5-5-5L2 17",
    )

    /** Lucide `undo-2`. */
    val Undo2: List<String> = listOf(
        "M9 14 4 9l5-5",
        "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
    )

    /** Lucide `key-round`. */
    val KeyRound: List<String> = listOf(
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        "M16 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0z",
    )

    /** Lucide `gift`. */
    val Gift: List<String> = listOf(
        "M12 7v14",
        "M20 11v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8",
        "M7.5 7a1 1 0 0 1 0-5A4.8 8 0 0 1 12 7a4.8 8 0 0 1 4.5-5 1 1 0 0 1 0 5",
        "M4 7h16a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z",
    )
}
