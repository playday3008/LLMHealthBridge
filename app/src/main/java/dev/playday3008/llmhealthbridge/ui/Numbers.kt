package dev.playday3008.llmhealthbridge.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [value] in plain decimal notation (never an exponent) without trailing zeros. Every digit of
 * the shortest representation that reads back as [value] is kept, unless [maxDecimals] caps the
 * fraction (rounded half up).
 */
internal fun plainNumber(value: Double, maxDecimals: Int = Int.MAX_VALUE): String {
    val exact = BigDecimal.valueOf(value)
    val capped = if (exact.scale() > maxDecimals) {
        exact.setScale(maxDecimals, RoundingMode.HALF_UP)
    } else {
        exact
    }
    return capped.stripTrailingZeros().toPlainString()
}
