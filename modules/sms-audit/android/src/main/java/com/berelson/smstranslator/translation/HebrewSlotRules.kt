package com.berelson.smstranslator.translation

/** Short grammatical constructions with typed variable fields, not complete SMS replacements. */
object HebrewSlotRules {
    data class Prepared(val text: String, val fixedTerms: List<String>)

    fun prepare(original: String, exclusions: List<String>): Prepared {
        val plan = TokenProtector.protect(original, exclusions)
        val byIndex = plan.tokens.associateBy { it.markerIndex }
        val fixed = mutableListOf<String>()
        val marker = Regex("⟦(\\d+)⟧")
        fun expand(text: String): String = marker.replace(text) { byIndex.getValue(it.groupValues[1].toInt()).original }
        var encoded = plan.encodedText
        val activeRules = rules + if (Regex("הזמנתך|משלוח").containsMatchIn(original)) listOf(companyRule) else emptyList()
        for (rule in activeRules) {
            encoded = rule.pattern.replace(encoded) { match ->
                val value = expand(match.groups["value"]!!.value)
                if (!rule.allowed.matches(value)) match.value else {
                    // A user exclusion that covers the construction is one opaque token; it
                    // never matches these literal Hebrew words + one typed amount/percentage.
                    val output = rule.prefix + match.groups["value"]!!.value + rule.suffix(value)
                    fixed += expand(output)
                    output
                }
            }
        }
        return TokenProtector.restore(plan, encoded)?.let { Prepared(it, fixed.distinct()) }
            ?: Prepared(original, emptyList())
    }

    private data class Rule(
        val pattern: Regex, val prefix: String, val allowed: Regex,
        val suffix: (String) -> String = { "" },
    )
    private const val SLOT = "(?<value>⟦\\d+⟧)"
    private val money = Regex("(?:[₪$€£]\\s?\\d[\\d.,]*|\\d[\\d.,]*\\s?[₪$€£])")
    private val percent = Regex("(?:\\d+(?:[.,]\\d+)?\\s?[%٪]|[%٪]\\s?\\d+(?:[.,]\\d+)?)")
    // מחברת can also mean a notebook. Require the shipping construction and a protected
    // company field followed by the expected-arrival predicate, not merely a delivery topic.
    private val companyRule = Rule(
        Regex("(?<![א-ת])מחברת\\s+$SLOT(?=\\s+צפוי(?:ה)?\\s+להגיע)"),
        "от компании ", Regex("[A-Za-z][A-Za-z0-9.-]{1,35}(?:[ \\t]+[A-Za-z][A-Za-z0-9.-]{0,25}){0,3}"),
    )
    private val rules = listOf(
        Rule(Regex("(?<![א-ת])תוך\\s+$SLOT\\s+ימי\\s+עסקים(?![א-ת])"), "в течение ", Regex("[0-9]{1,3}")) {
            val days = it.toInt()
            if (days % 10 == 1 && days % 100 != 11) " рабочего дня" else " рабочих дней"
        },
        Rule(Regex("(?<![א-ת])ומקבלים\\s+משלוח\\s+ב[-־]?\\s*$SLOT"), "и получайте доставку за ", money),
        Rule(Regex("(?<![א-ת])משלוח\\s+ב[-־]?\\s*$SLOT"), "доставка за ", money),
        Rule(Regex("(?<![א-ת])(?:רוכשים|בקנייה|בקניה)\\s+ב[-־]?\\s*$SLOT"), "при покупке на сумму ", money),
        Rule(Regex("(?<![א-ת])עד\\s+$SLOT\\s+הנחה"), "скидка до ", percent),
    )
}
