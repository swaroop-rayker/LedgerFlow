package com.ledgerflow.core.domain.diagnostics

import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.Money
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import org.junit.Test

/**
 * **The diagnostics report carries no money, by construction.**
 *
 * The screen's promise — counts and durations, no amounts — is what makes it
 * safe to show to someone helping with a parser, and it is also what keeps Law 2
 * out of reach: a report with no monetary field cannot net the two books.
 * Checked by walking every field reachable from [IngestDiagnostics], including
 * through lists, so a field added three levels down next year is covered
 * without editing this test.
 *
 * A money-shaped field fails whatever its type: [Money] and floating point by
 * type (Law 3), and anything named like an amount by name, since a `Long`
 * called `amountMinor` would be money in all but type.
 */
class DiagnosticsCarryNoMoneyTest {

    @Test
    fun noFieldReachableFromTheReportIsMoney() {
        val fields = reachableFields(IngestDiagnostics::class.java)

        assertWithMessage("the walk found nothing -- it is not looking where the fields are")
            .that(fields.size).isGreaterThan(MINIMUM_EXPECTED_FIELDS)

        fields.forEach { (owner, field) ->
            val where = "${owner.simpleName}.${field.name}"
            assertWithMessage("%s is typed as money", where)
                .that(field.type).isNotEqualTo(Money::class.java)
            assertWithMessage("%s is floating point (Law 3)", where)
                .that(field.type in FLOATING).isFalse()
            assertWithMessage("%s is named like an amount", where)
                .that(MONEY_NAME.containsMatchIn(field.name)).isFalse()
        }
    }

    /** Every declared instance field of every project class reachable from [root]. */
    private fun reachableFields(root: Class<*>): List<Pair<Class<*>, Field>> {
        val seen = mutableSetOf<Class<*>>()
        val out = mutableListOf<Pair<Class<*>, Field>>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type) || type.isEnum || !type.name.startsWith(PROJECT)) continue
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    out += type to field
                    projectTypesIn(field.genericType).forEach(queue::addLast)
                }
        }
        return out
    }

    private fun projectTypesIn(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> listOf(type)
        is ParameterizedType -> type.actualTypeArguments.flatMap(::projectTypesIn)
        // `List<out T>` as Kotlin may emit it for a non-final element type.
        is WildcardType -> type.upperBounds.flatMap(::projectTypesIn)
        else -> emptyList()
    }

    private companion object {
        const val PROJECT = "com.ledgerflow."

        /** The report has well over this many fields today; fewer means the walk broke. */
        const val MINIMUM_EXPECTED_FIELDS = 20

        val FLOATING: Set<Class<*>> = setOf(
            Double::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Double::class.javaObjectType,
            Float::class.javaObjectType,
        )

        val MONEY_NAME = Regex("amount|price|balance|minor|spent|received", RegexOption.IGNORE_CASE)
    }
}
