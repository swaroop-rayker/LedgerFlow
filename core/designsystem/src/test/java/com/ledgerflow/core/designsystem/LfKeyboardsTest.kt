package com.ledgerflow.core.designsystem

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfKeyboards
import org.junit.Test

/**
 * The keyboard configuration for recovery-phrase words.
 *
 * Every property here is invisible on screen — a field without them looks and
 * behaves the same — which is exactly why each one is asserted rather than
 * left to review. The 24 words protect every `.lfbk`; a keyboard that learns
 * them has stored them somewhere this app does not control.
 */
class LfKeyboardsTest {

    private val options = LfKeyboards.RecoveryWord

    /** The password variation is what mainstream keyboards refuse to learn from. */
    @Test
    fun recoveryWords_areAPasswordInput() {
        assertThat(options.keyboardType).isEqualTo(KeyboardType.Password)
    }

    /** Autocorrect could turn a real BIP-39 word into a different real one. */
    @Test
    fun recoveryWords_areNeverAutocorrected() {
        assertThat(options.autoCorrectEnabled).isFalse()
    }

    /** The wordlist is lowercase; auto-capitalising creates errors the user did not make. */
    @Test
    fun recoveryWords_areNotCapitalised() {
        assertThat(options.capitalization).isEqualTo(KeyboardCapitalization.None)
    }
}
