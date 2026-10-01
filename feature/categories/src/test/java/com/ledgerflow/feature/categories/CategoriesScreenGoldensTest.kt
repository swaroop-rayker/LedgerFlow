package com.ledgerflow.feature.categories

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class CategoriesScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: CategoriesUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
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

    @Test fun categories_1x() = capture("organise-categories-1x", categories, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun categories_2x() = capture("organise-categories-2x", categories, 2f)

    @Test fun merchants_1x() = capture("organise-merchants-1x", merchants, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun merchants_2x() = capture("organise-merchants-2x", merchants, 2f)

    @Test fun payment_1x() = capture("organise-payment-1x", payment, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun payment_2x() = capture("organise-payment-2x", payment, 2f)

    private companion object {
        const val HIDDEN_AT = 1_755_000_000_000L
    }
}
