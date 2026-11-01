package com.ledgerflow.feature.categories

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import com.ledgerflow.core.testing.taxonomy.FakeMerchantCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Organise > Merchants > Merchant categories (V2-6, V2-PLAN §5.1): counts,
 * hiding with somewhere for the merchants to go, refusals beside the name;
 * goldens at 1x, 2x and RTL in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class MerchantCategoriesTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val kinds = FakeMerchantCategoryRepository()
    private val merchants = FakeMerchantRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): Pair<MerchantCategoriesViewModel, Job> {
        val vm = MerchantCategoriesViewModel(kinds, merchants)
        val job = CoroutineScope(dispatcher).launch { vm.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return vm to job
    }

    private suspend fun created(name: String) = (kinds.create(name) as TaxonomyResult.Success).value

    private fun merchant(id: String, kind: String?) = Merchant(id, id, id, null, null, merchantCategoryId = kind)

    @Test
    fun eachRow_countsTheMerchantsFiledUnderIt() = runTest(dispatcher) {
        val fuel = created("Fuel")
        val food = created("Food delivery")
        merchants.merchants.value = listOf(merchant("a", fuel.id), merchant("b", fuel.id), merchant("c", null))
        val (vm, job) = viewModel()

        assertThat(vm.state.value.rows.associate { it.category.name to it.merchants })
            .containsExactly("Fuel", 2, "Food delivery", 0)
        assertThat(food.id).isNotEmpty()
        job.cancel()
    }

    /** §5.1: hiding asks where its merchants go, Uncategorised first chosen, never itself. */
    @Test
    fun hide_offersTheOthersAndUncategorised_andMovesItsMerchants() = runTest(dispatcher) {
        val fuel = created("Fuel")
        val travel = created("Travel & transport")
        kinds.filed["shell"] = fuel.id
        merchants.merchants.value = listOf(merchant("shell", fuel.id))
        val (vm, job) = viewModel()

        vm.onEvent(MerchantCategoriesEvent.AskHide(fuel.id, "Fuel"))
        dispatcher.scheduler.advanceUntilIdle()
        val dialog = vm.state.value.dialog as MerchantCategoryDialog.Hide
        assertThat(dialog.merchants).isEqualTo(1)
        assertThat(dialog.moveTo).isEqualTo(UNCATEGORISED_CHOICE)
        assertThat(dialog.candidates.map { it.id }).containsExactly(travel.id, UNCATEGORISED_CHOICE).inOrder()

        vm.onEvent(MerchantCategoriesEvent.TargetSelected(travel.id))
        vm.onEvent(MerchantCategoriesEvent.Confirmed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(kinds.filed["shell"]).isEqualTo(travel.id)
        assertThat(vm.state.value.dialog).isNull()
        job.cancel()
    }

    /** A taken name keeps the dialog open with the reason beside it. */
    @Test
    fun aTakenName_isRefusedInTheDialog() = runTest(dispatcher) {
        created("Fuel")
        val (vm, job) = viewModel()

        vm.onEvent(MerchantCategoriesEvent.Add)
        vm.onEvent(MerchantCategoriesEvent.TextChanged("fuel"))
        vm.onEvent(MerchantCategoriesEvent.Confirmed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.dialog).isInstanceOf(MerchantCategoryDialog.Name::class.java)
        assertThat(vm.state.value.message).contains("already exists")
        job.cancel()
    }

    // ── Goldens ─────────────────────────────────────────────────────────────

    private val page = MerchantCategoriesUiState(
        rows = listOf(
            row("1", "Groceries", 0xFF3E6AD6, 4),
            row("2", "Food delivery", 0xFF2C75AE, 2),
            row("3", "Restaurants & caf" + Char(0x00E9) + "s", 0xFF207E6E, 0),
            row("4", "Fuel", 0xFF2F7E50, 1),
            row("5", "Pharmacy & health", 0xFF5C7A1E, 0),
        ),
        hidden = listOf(HiddenTaxonomy("9", "Government & tax", hiddenAt = 1L)),
        hiddenExpanded = true,
    )

    private fun row(id: String, name: String, color: Long, count: Int) =
        MerchantCategoryRow(MerchantCategory(id, name, color.toInt(), id.toInt(), isSystem = true), count)

    private fun capture(name: String, state: MerchantCategoriesUiState, fontScale: Float, rtl: Boolean = false) =
        rule.captureScreenGolden(name, fontScale, if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            LfTheme { MerchantCategoriesScreen(state = state, onEvent = {}, onBack = {}) }
        }

    @Test fun page_1x() = capture("merchant-categories-1x", page, 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun page_2x() = capture("merchant-categories-2x", page, 2f)

    @Test fun page_1x_rtl() = capture("merchant-categories-1x-rtl", page, 1f, rtl = true)

    /** The hide question: its merchants, and where they go. A dialog is its own window. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun hideDialog_1x() {
        val state = page.copy(
            dialog = MerchantCategoryDialog.Hide(
                id = "1",
                name = "Groceries",
                merchants = 4,
                candidates = listOf(TaxonomyChoice("2", "Food delivery"), TaxonomyChoice("4", "Fuel")) +
                    TaxonomyChoice(UNCATEGORISED_CHOICE, UNCATEGORISED),
            ),
        )
        rule.setContent {
            GoldenTheme.Provide { LfTheme { MerchantCategoriesScreen(state = state, onEvent = {}, onBack = {}) } }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName("merchant-categories-hide-1x")}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MerchantCategoriesDarkTest : MerchantCategoriesTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
