package com.ledgerflow.core.crypto

/** Hex helpers shared by the vector tests. */
internal fun String.hexToBytes(): ByteArray {
    val clean = removePrefix("0x").replace(Regex("\\s"), "")
    require(clean.length % 2 == 0) { "Hex string must have an even length: $clean" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
