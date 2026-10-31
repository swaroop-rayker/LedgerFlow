package com.ledgerflow.feature.categories

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import org.robolectric.RuntimeEnvironment
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Organise" — categories, merchants and payment methods — at font scale 1.0
 * and 2.0 (§12, P5 step 4), with §9.6's checks. One golden per tab, the
 * merchants tab with its hidden section open, since that is where the
 * restore actions live.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class CategoriesScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        state: CategoriesUiState,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) =
        rule.captureScreenGolden(name, fontScale, direction) {
            LfTheme { CategoriesScreen(state = state, onEvent = {}, onBack = {}) }
        }

    private fun category(id: String, name: String, parent: String? = null, system: Boolean = false) =
        Category(
            id = id, parentId = parent, ledger = LedgerType.DEBIT, name = name,
            icon = "", colorArgb = 0xFF3E6AD6.toInt(), sortOrder = 0, isSystem = system,
        )

    private val categories = CategoriesUiState(
        tree = listOf(
            CategoryTree(
                parent = category("1", "Food & Dining", system = true),
                children = listOf(
                    category("2", "Groceries", parent = "1", system = true),
                    category("4", "Restaurants", parent = "1"),
                ),
            ),
            CategoryTree(parent = category("3", "Transport"), children = emptyList()),
        ),
    )

    private val refused by lazy { categories.copy(message = "\"Groceries\" already exists here.") }

    private val merchants = CategoriesUiState(
        section = TaxonomySection.Merchants,
        merchants = listOf(
            Merchant("1", "Big Bazaar", "bigbazaar", null, null),
            Merchant("5", "Zepto", "zepto", null, null),
        ),
        hidden = listOf(
            HiddenTaxonomy("2", "Amazon", hiddenAt = HIDDEN_AT),
            HiddenTaxonomy("3", "Reliance Fresh 1182", hiddenAt = HIDDEN_AT),
        ),
        hiddenExpanded = true,
    )

    private val payment = CategoriesUiState(
        section = TaxonomySection.PaymentMethods,
        paymentMethods = listOf(
            PaymentMethod(
                id = "1", type = PaymentMethodType.CASH, label = "Cash", issuer = null,
                last4 = null, colorArgb = null, isDefault = true,
            ),
            PaymentMethod(
                id = "2", type = PaymentMethodType.CREDIT_CARD, label = "HDFC Card", issuer = "HDFC",
                last4 = "4821", colorArgb = null, isDefault = false,
            ),
        ),
    )

    /** A refused change, as a warning notice (E3). */
    @Test fun refused_1x() = capture("organise-refused-1x", refused, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun refused_2x() = capture("organise-refused-2x", refused, 2f)

    @Test fun categories_1x() = capture("organise-categories-1x", categories, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun categories_2x() = capture("organise-categories-2x", categories, 2f)

    @Test fun merchants_1x() = capture("organise-merchants-1x", merchants, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun merchants_2x() = capture("organise-merchants-2x", merchants, 2f)

    @Test fun payment_1x() = capture("organise-payment-1x", payment, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun payment_2x() = capture("organise-payment-2x", payment, 2f)

    /** Subcategories indent from the start, so in RTL they step in from the right. */
    @Test fun categoriesRtl_1x() = capture("organise-categories-rtl-1x", categories, 1f, LayoutDirection.Rtl)

    @Test fun merchantsRtl_1x() = capture("organise-merchants-rtl-1x", merchants, 1f, LayoutDirection.Rtl)

    /** A category's actions: the sheet the row opens (option A, 2026-10-31). */
    @Test fun categorySheet_1x() = withSheet("organise-category-sheet-1x", categorySheet, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun categorySheet_2x() = withSheet("organise-category-sheet-2x", categorySheet, 2f)

    /** A subcategory's: no "Add subcategory", and Hide is still there. */
    @Test fun subcategorySheet_1x() = withSheet("organise-subcategory-sheet-1x", subcategorySheet, 1f)

    private val categorySheet by lazy {
        categories.copy(
            dialog = TaxonomyDialog.RowActions(TaxonomyRow.Category("1", "Food & Dining", isChild = false)),
        )
    }

    private val subcategorySheet by lazy {
        categories.copy(dialog = TaxonomyDialog.RowActions(TaxonomyRow.Category("2", "Groceries", isChild = true)))
    }

    /**
     * A sheet is its own window: it ignores [captureScreenGolden]'s density
     * override and is not under its one root, so the font scale goes through
     * the configuration and the whole screen is captured (as the Budgets
     * delete sheet's goldens do).
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun withSheet(name: String, state: CategoriesUiState, fontScale: Float) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            GoldenTheme.Provide {
                LfTheme { CategoriesScreen(state = state, onEvent = {}, onBack = {}) }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    private companion object {
        const val HIDDEN_AT = 1_755_000_000_000L
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoriesScreenGoldensDarkTest : CategoriesScreenGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
