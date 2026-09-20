package com.berelson.smstranslator.translation

/**
 * High-confidence Hebrew-to-Russian terminology rules for common SMS contexts.
 *
 * Rules intentionally favour complete phrases over isolated words. This avoids replacing a
 * Hebrew word with the wrong Russian meaning when the same word is used in another context.
 */
object DomainRuleEngine {
    enum class Domain(val storageKey: String) {
        GROCERY("grocery"),
        DELIVERY("delivery"),
        MEDICAL("medical"),
        PHARMACY("pharmacy"),
        BANKING("banking"),
        GOVERNMENT("government"),
        WORK("work"),
        SCHOOL("school"),
        TRANSPORT("transport"),
        UTILITIES("utilities"),
        CONSTRUCTION("construction"),
        SECURITY("security");

        companion object {
            fun fromStorageKey(value: String?): Domain? = values().firstOrNull {
                it.storageKey == value
            }
        }
    }

    data class Segment(
        val text: String,
        val fixedTranslation: String? = null,
    )

    /**
     * Text prepared for one contextual ML Kit request. Known ambiguous phrases are replaced with
     * their reviewed translation and then protected by TranslationEngine while the surrounding
     * message is translated as a whole.
     */
    data class PreparedTranslation(
        val text: String,
        val fixedTranslations: List<String>,
    )

    fun resolve(text: String, forced: Domain?, sender: String = ""): Domain? =
        forced ?: detect(text, sender)

    fun detect(text: String, sender: String = ""): Domain? {
        if (text.isBlank() && sender.isBlank()) return null
        val normalizedText = text.lowercase()
        val normalizedSender = sender.lowercase()
        val scores: Map<Domain, Int> = topics.associate { topic ->
            val senderScore: Int =
                if (topic.senderHints.any { hint -> normalizedSender.contains(hint) }) 10 else 0
            val keywordScore: Int = topic.keywords.fold(0) { score, keyword ->
                score + if (containsLiteral(normalizedText, keyword.lowercase())) {
                    if (keyword.any(Char::isWhitespace)) 3 else 2
                } else {
                    0
                }
            }
            val ruleScore = topic.rules.count { rule ->
                containsLiteral(normalizedText, rule.hebrew.lowercase())
            }.coerceAtMost(3) * 4
            val contentScore = when (topic.domain) {
                Domain.GROCERY -> groceryContentScore(normalizedText)
                else -> 0
            }
            topic.domain to (senderScore + keywordScore + ruleScore + contentScore)
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.value ?: 0
        return best.key.takeIf { best.value >= MIN_DOMAIN_SCORE && best.value > secondScore }
    }

    /** Splits text into ordinary fragments and exact Russian replacements. */
    fun segments(
        text: String,
        domain: Domain?,
        protectedTerms: List<String> = emptyList(),
        context: String = text,
    ): List<Segment> {
        if (text.isEmpty()) return listOf(Segment(text))
        val selectedRules = topics.firstOrNull { it.domain == domain }?.rules.orEmpty() +
            (HebrewPhraseRules.forText(context) + HebrewNotificationRules.forText(context)).map { rule(it.first, it.second) }
        val normalized = text.lowercase()
        val occupied = BooleanArray(text.length)
        val accepted = mutableListOf<RuleMatch>()

        // A context rule must never rewrite a URL, address, code or user-protected range.
        TokenProtector.protect(text, protectedTerms).tokens.forEach { token ->
            for (index in token.start until token.endExclusive) occupied[index] = true
        }

        protectedTerms.map { it.trim() }.filter { it.isNotEmpty() }.forEach { term ->
            val needle = term.lowercase()
            var from = 0
            while (from <= normalized.length - needle.length) {
                val start = normalized.indexOf(needle, from)
                if (start < 0) break
                val end = start + needle.length
                if (hasWordBoundaries(text, start, end)) {
                    for (index in start until end) occupied[index] = true
                }
                from = start + needle.length.coerceAtLeast(1)
            }
        }

        selectedRules.sortedByDescending { it.hebrew.length }.forEach { rule ->
            phrasePattern(rule.hebrew).findAll(text).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                if (hasWordBoundaries(text, start, end) &&
                    (start until end).none { occupied[it] }
                ) {
                    accepted += RuleMatch(start, end, rule.russian)
                    for (index in start until end) occupied[index] = true
                }
            }
        }

        if (accepted.isEmpty()) return listOf(Segment(text))
        val result = mutableListOf<Segment>()
        var cursor = 0
        accepted.sortedBy { it.start }.forEach { match ->
            if (cursor < match.start) result += Segment(text.substring(cursor, match.start))
            result += Segment(
                text = text.substring(match.start, match.endExclusive),
                fixedTranslation = match.russian,
            )
            cursor = match.endExclusive
        }
        if (cursor < text.length) result += Segment(text.substring(cursor))
        return result
    }

    fun prepareTranslation(
        text: String,
        domain: Domain?,
        protectedTerms: List<String> = emptyList(),
        context: String = text,
    ): PreparedTranslation? {
        val preparedSegments = segments(text, domain, protectedTerms, context)
        val fixedTranslations = preparedSegments.mapNotNull(Segment::fixedTranslation)
            .distinct()
        if (fixedTranslations.isEmpty()) return null
        return PreparedTranslation(
            text = preparedSegments.joinToString(separator = "") { segment ->
                segment.fixedTranslation ?: segment.text
            },
            fixedTranslations = fixedTranslations,
        )
    }

    fun cacheSalt(domain: Domain?): String =
        "$RULESET_VERSION:${domain?.storageKey ?: "general"}"

    fun selectorTitle(language: String): String = when (language) {
        "ru" -> "Контекст перевода"
        "he" -> "הקשר התרגום"
        else -> "Translation context"
    }

    fun automaticLabel(language: String): String = when (language) {
        "ru" -> "Автоматически (рекомендуется)"
        "he" -> "אוטומטי (מומלץ)"
        else -> "Automatic (recommended)"
    }

    fun displayName(domain: Domain, language: String): String = when (language) {
        "ru" -> russianNames.getValue(domain)
        "he" -> hebrewNames.getValue(domain)
        else -> englishNames.getValue(domain)
    }

    private fun containsLiteral(text: String, literal: String): Boolean {
        if (literal.isEmpty() || text.length < literal.length) return false
        var from = 0
        while (from <= text.length - literal.length) {
            val start = text.indexOf(literal, from)
            if (start < 0) return false
            if (hasWordBoundaries(text, start, start + literal.length)) return true
            from = start + 1
        }
        return false
    }

    /**
     * Detects grocery catalogues from their content instead of depending on a store-name list.
     * Repeated price-and-unit rows are intentionally required before language-independent
     * structure alone can select this domain, which keeps an isolated price from being treated as
     * a supermarket advertisement.
     */
    private fun groceryContentScore(text: String): Int {
        val lines = text.lineSequence().map { line -> line.trim() }.filter { line ->
            line.isNotEmpty()
        }.toList()
        val pricedLines = lines.count { line -> groceryPricePattern.containsMatchIn(line) }
        val priceAndUnitLines = lines.count { line ->
            groceryPricePattern.containsMatchIn(line) &&
                groceryUnitHints.any { hint -> line.contains(hint) }
        }
        val productHints = groceryProductHints.count { hint -> containsLiteral(text, hint) }
        val promotionHints = groceryPromotionHints.count { hint -> containsLiteral(text, hint) }

        var score = 0
        if (priceAndUnitLines >= 3 && pricedLines >= 4) score += 7
        if (pricedLines >= 2 && productHints >= 2) score += 6
        if (productHints >= 4) score += 4
        if (pricedLines >= 1 && productHints >= 1 && promotionHints >= 2) score += 4
        return score
    }

    // Real senders vary spaces and Hebrew/ASCII quotation marks. Match the same phrase,
    // without normalising the SMS itself or swallowing original paragraph separators.
    private val phrasePatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val phraseSeparators = Regex("[ \t]*[,،][ \t]*|[ \t]+|[\"״]|['׳’]")
    private fun phrasePattern(phrase: String): Regex = phrasePatterns.computeIfAbsent(phrase) {
        val pattern = StringBuilder()
        var cursor = 0
        for (separator in phraseSeparators.findAll(phrase)) {
            pattern.append(Regex.escape(phrase.substring(cursor, separator.range.first)))
            pattern.append(when {
                separator.value.contains(',') || separator.value.contains('،') -> "[ \t]*[,،][ \t]*"
                separator.value.first() in "\"״" -> "[\"״]"
                separator.value.first() in "'׳’" -> "['׳’]"
                else -> "[ \t\u00A0]+"
            })
            cursor = separator.range.last + 1
        }
        pattern.append(Regex.escape(phrase.substring(cursor)))
        Regex(pattern.toString(), RegexOption.IGNORE_CASE)
    }

    private fun hasWordBoundaries(text: String, start: Int, endExclusive: Int): Boolean {
        val startsAsWord = text.getOrNull(start)?.isLetterOrDigit() == true
        val endsAsWord = text.getOrNull(endExclusive - 1)?.isLetterOrDigit() == true
        val leftOk = !startsAsWord || start == 0 || !text[start - 1].isLetterOrDigit()
        val rightOk = !endsAsWord || endExclusive == text.length ||
            !text[endExclusive].isLetterOrDigit() ||
            (text[endExclusive].isDigit() && text[endExclusive - 1] in "בלמ" &&
                text.substring(start, endExclusive).any(Char::isWhitespace))
        return leftOk && rightOk
    }

    private data class Rule(val hebrew: String, val russian: String)
    private data class Topic(
        val domain: Domain,
        val keywords: List<String>,
        val senderHints: List<String>,
        val rules: List<Rule>,
    )
    private data class RuleMatch(
        val start: Int,
        val endExclusive: Int,
        val russian: String,
    )

    private fun rule(hebrew: String, russian: String) = Rule(hebrew, russian)

    private val topics = listOf(
        Topic(
            domain = Domain.GROCERY,
            keywords = listOf(
                "מבצע", "קופון", "מועדון לקוחות", "סל קניות", "מלאי", "מחיר ליחידה",
                "סופרמרקט", "מוצר קפוא", "מוצרים קפואים", "מוצרי חלב", "ירקות",
                "פירות", "בשר טרי", "בשר קפוא", "עוף טרי", "דגים", "לק\"ג",
                "לק״ג", "למארז", "עד גמר המלאי", "בקנייה מעל",
            ),
            senderHints = listOf(
                "shufersal", "שופרסל", "rami levy", "רמי לוי", "carrefour", "קרפור",
                "victory", "ויקטורי", "yohananof", "יוחננוף", "osher ad", "אושר עד",
                "universe", "יוניברס",
            ),
            rules = listOf(
                rule("יוניברס  ע - נ – ק", "Огромные скидки в Universe"),
                rule("יוניברס ע - נ – ק", "Огромные скидки в Universe"),
                rule("יוניברס", "Universe"),
                rule("מבצע לזמן מוגבל", "акция действует ограниченное время"),
                rule("המבצע בתוקף", "акция действует"),
                rule("בתוקף עד", "действует до"),
                rule(
                    "או עד גמר המלאי, המוקדם שבהם",
                    "или до окончания запасов — в зависимости от того, что наступит раньше",
                ),
                rule("עד גמר המלאי", "пока товар есть в наличии"),
                rule("אזל מהמלאי", "нет в наличии"),
                rule("חזר למלאי", "снова в наличии"),
                rule("מחיר ליחידה", "цена за единицу"),
                rule("מחיר לקילו", "цена за килограмм"),
                rule("מחיר מבצע", "цена по акции"),
                rule("מחיר מיוחד", "специальная цена"),
                rule("הנחה לחברי מועדון", "скидка для участников клуба"),
                rule("לחברי מועדון בלבד", "только для участников клуба"),
                rule("לחברי מועדון", "для участников клуба"),
                rule("קופון אישי", "персональный купон"),
                rule("למימוש חד פעמי", "можно использовать один раз"),
                rule("לא כולל כפל מבצעים", "не суммируется с другими акциями"),
                rule("אין כפל מבצעים", "не суммируется с другими акциями"),
                rule("אחד פלוס אחד", "два по цене одного"),
                rule("השני בחצי מחיר", "второй товар за полцены"),
                rule("בקניית שניים", "при покупке двух товаров"),
                rule("בקנייה מעל", "при покупке на сумму от"),
                rule("מותנים בקנייה ב", "действует при покупке на сумму от "),
                rule("מותנה בקנייה ב", "действует при покупке на сумму от "),
                rule("מוגבל ללקוח", "ограничено на одного покупателя"),
                rule("ומוגבלים ל", "и можно приобрести не более "),
                rule("מוגבלים ל", "можно приобрести не более "),
                rule("מוגבל ל", "можно приобрести не более "),
                rule("לק\"ג", "за кг"),
                rule("לק״ג", "за кг"),
                rule("ק\"ג", "кг"),
                rule("ק״ג", "кг"),
                rule("למארז", "за упаковку"),
                rule("מארזים", "упаковок"),
                rule("משלוח חינם", "бесплатная доставка"),
                rule("סל הקניות", "корзина"),
                rule("ההזמנה מוכנה", "заказ готов"),
                rule("איסוף עצמי", "самовывоз"),
                rule("תוקף המוצר", "срок годности товара"),
                rule("ללא גלוטן", "без глютена"),
                rule("מוצר קפוא", "замороженный продукт"),
                rule("ירקות קפואים", "замороженные овощи"),
                rule("פירות וירקות", "фрукты и овощи"),
                rule("מבצעי ירקות ופירות", "акции на овощи и фрукты"),
                rule("מבצעי שרי ובצל אדום", "акции на помидоры черри и красный лук"),
                rule("מוצרי חלב", "молочные продукты"),
                rule("בשר טרי", "свежее мясо"),
                rule("לשון קפואה", "замороженный говяжий язык"),
                rule("אסאדו קפוא עם עצם", "замороженное асадо на кости"),
                rule("אסאדו", "асадо"),
                rule("שוק אווז קפוא", "замороженная гусиная ножка"),
                rule("חזה אווז קפוא", "замороженная гусиная грудка"),
                rule("רבע טלה טרי", "свежая четверть ягнёнка"),
                rule("בשר טחון טרי", "свежий говяжий фарш"),
                rule("בשר בקר טרי", "свежая говядина"),
                rule("בשר בקר קפוא", "замороженная говядина"),
                rule("חזה עוף טרי", "свежая куриная грудка"),
                rule("כרעיים עוף", "куриные окорочка"),
                rule("שוקיים עוף", "куриные голени"),
                rule("פרגיות טריות", "свежие куриные бёдра без кости"),
                rule("עוף שלם טרי", "свежая целая курица"),
                rule("שניצל עוף", "куриный шницель"),
                rule("פילה סלמון", "филе лосося"),
                rule("דג סלמון טרי", "свежий лосось"),
                rule("דג קפוא", "замороженная рыба"),
                rule("עגבניות שרי אדומות", "красные помидоры черри"),
                rule("עגבניות שרי", "помидоры черри"),
                rule("בצל אדום", "красный лук"),
                rule("כרוב לבן", "белокочанная капуста"),
                rule("גזר ארוז", "морковь в упаковке"),
                rule("בייבי בטטה", "мини-батат"),
                rule("תפוחי אדמה", "картофель"),
                rule("פלפל אדום", "красный сладкий перец"),
                rule("פלפל צהוב", "жёлтый сладкий перец"),
                rule("כרובית טרייה", "свежая цветная капуста"),
                rule("חסה ערבית", "салат ромэн"),
                rule("דלורית", "тыква баттернат"),
                rule("סלק בוואקום", "свёкла в вакуумной упаковке"),
                rule("שום יבש", "сухой чеснок"),
                rule("חלב טרי", "свежее молоко"),
                rule("גבינה צהובה", "твёрдый сыр"),
                rule("גבינה לבנה", "мягкий белый сыр"),
                rule("שמנת מתוקה", "сливки для взбивания"),
                rule("יוגורט טבעי", "натуральный йогурт"),
                rule("ביצים גדולות", "крупные яйца"),
                rule("לחם אחיד", "формовой хлеб"),
                rule("לחם מלא", "цельнозерновой хлеб"),
                rule("לפרטים ומבצעים נוספים", "подробности и другие акции"),
                rule("מבצע לשון", "акция на говяжий язык"),
                rule("מבצע שרי", "акция на помидоры черри"),
                rule("חל בסניפים נבחרים", "действует в отдельных филиалах"),
                rule("ולא כולל", "и не распространяется на"),
                rule(
                    "מחירם הקודם של המוצרים מוצג בסניף",
                    "предыдущая цена товаров указана в магазине",
                ),
                rule(
                    "כפוף לתנאי המבצע, למלאי ולמגוון בסניף",
                    "согласно условиям акции, при наличии и с учётом ассортимента магазина",
                ),
                rule("התמונה להמחשה בלבד", "изображение приведено для иллюстрации"),
                rule("להסרה שלחו 'הסר' ל", "чтобы отказаться от рассылки, отправьте 'הסר' на"),
                rule("להסרה שלחו", "чтобы отказаться от рассылки, отправьте"),
            ),
        ),
        Topic(
            domain = Domain.DELIVERY,
            keywords = listOf(
                "משלוח", "חבילה", "שליח", "איסוף", "נקודת חלוקה", "מסירה", "לוקר",
            ),
            senderHints = listOf(
                "cheetah", "צ'יטה", "getpackage", "boxit", "hfd", "ups", "fedex",
                "israel post", "דואר ישראל",
            ),
            rules = listOf(
                rule("החבילה נקלטה", "посылка принята"),
                rule("המשלוח נקלט", "отправление принято"),
                rule("המשלוח יצא לדרך", "отправление в пути"),
                rule("יצא למסירה", "передано курьеру для доставки"),
                rule("בדרך אליך", "направляется к вам"),
                rule("ממתין לאיסוף", "ожидает получения"),
                rule("נקודת איסוף", "пункт выдачи"),
                rule("תא איסוף", "ячейка выдачи"),
                rule("קוד איסוף", "код получения"),
                rule("שליח יגיע", "курьер прибудет"),
                rule("תיאום מסירה", "согласование доставки"),
                rule("ניסיון מסירה", "попытка доставки"),
                rule("לא נמסר", "не доставлено"),
                rule("נמסר בהצלחה", "успешно доставлено"),
                rule("הועבר לנקודת חלוקה", "передано в пункт выдачи"),
                rule("יש לאסוף תוך", "необходимо забрать в течение"),
                rule("שעות פתיחה", "часы работы"),
                rule("שינוי כתובת", "изменение адреса"),
                rule("אישור איסוף", "подтверждение получения"),
            ),
        ),
        Topic(
            domain = Domain.MEDICAL,
            keywords = listOf(
                "תור", "רופא", "מרפאה", "בית חולים", "בדיקה", "הפניה", "טופס 17",
                "קופת חולים", "מעבדה", "מיון",
            ),
            senderHints = listOf(
                "clalit", "כללית", "maccabi", "מכבי", "meuhedet", "מאוחדת", "leumit",
                "לאומית", "rambam", "רמבם", "bnei zion", "בני ציון", "mor", "lin",
            ),
            rules = listOf(
                rule("נקבע לך תור", "вам назначен приём"),
                rule("התור שלך נקבע", "ваш приём назначен"),
                rule("זימון תור", "запись на приём"),
                rule("תור לרופא", "приём у врача"),
                rule("רופא משפחה", "семейный врач"),
                rule("רופא עיניים", "офтальмолог"),
                rule("בדיקת עיניים", "обследование глаз"),
                rule("בדיקת דם", "анализ крови"),
                rule("בדיקות מעבדה", "лабораторные анализы"),
                rule("הפניה רפואית", "медицинское направление"),
                rule("התחייבות כספית", "гарантия оплаты лечения"),
                rule("טופס 17", "форма 17"),
                rule("מרפאת חוץ", "амбулаторная клиника"),
                rule("חדר מיון", "отделение неотложной помощи"),
                rule("בית חולים", "больница"),
                rule("יש להגיע בצום", "необходимо прийти натощак"),
                rule("נא להביא", "возьмите с собой"),
                rule("ביטול תור", "отмена приёма"),
                rule("הקדמת תור", "перенос приёма на более раннюю дату"),
                rule("תוצאות הבדיקה", "результаты обследования"),
                rule("המרשם חודש", "рецепт продлён"),
            ),
        ),
        Topic(
            domain = Domain.PHARMACY,
            keywords = listOf(
                "תרופה", "מרשם", "בית מרקחת", "רוקח", "מינון", "כדור", "טבליה",
            ),
            senderHints = listOf("super-pharm", "סופר פארם", "good pharm", "be פארם"),
            rules = listOf(
                rule("המרשם מוכן", "рецепт готов"),
                rule("התרופה מוכנה", "лекарство готово"),
                rule("איסוף תרופות", "получение лекарств"),
                rule("חידוש מרשם", "продление рецепта"),
                rule("תרופת מרשם", "рецептурное лекарство"),
                rule("ללא מרשם", "без рецепта"),
                rule("מלאי התרופה", "наличие лекарства"),
                rule("התרופה חסרה", "лекарства нет в наличии"),
                rule("תוקף המרשם", "срок действия рецепта"),
                rule("מינון מומלץ", "рекомендуемая дозировка"),
                rule("פעם ביום", "один раз в день"),
                rule("פעמיים ביום", "два раза в день"),
                rule("שלוש פעמים ביום", "три раза в день"),
                rule("לפני האוכל", "до еды"),
                rule("אחרי האוכל", "после еды"),
                rule("עם האוכל", "во время еды"),
                rule("תופעות לוואי", "побочные эффекты"),
                rule("יש להתייעץ עם רופא", "проконсультируйтесь с врачом"),
                rule("רוקח אחראי", "ответственный фармацевт"),
            ),
        ),
        Topic(
            domain = Domain.BANKING,
            keywords = listOf(
                "חשבון", "כרטיס אשראי", "עסקה", "חיוב", "זיכוי", "העברה בנקאית",
                "ריבית", "הלוואה", "יתרה", "משיכת מזומן",
            ),
            senderHints = listOf(
                "leumi", "לאומי", "hapoalim", "הפועלים", "discount", "דיסקונט",
                "mizrahi", "מזרחי", "isracard", "ישראכרט", "max", "cal-online", "כאל",
                "one zero",
            ),
            rules = listOf(
                rule("חיוב בכרטיס", "списание с карты"),
                rule("זיכוי בכרטיס", "возврат на карту"),
                rule("העסקה אושרה", "операция одобрена"),
                rule("העסקה נדחתה", "операция отклонена"),
                rule("הוראת קבע", "автоматический платёж"),
                rule("העברה בנקאית", "банковский перевод"),
                rule("יתרת החשבון", "остаток на счёте"),
                rule("מסגרת אשראי", "кредитный лимит"),
                rule("חריגה מהמסגרת", "превышение кредитного лимита"),
                rule("תשלום נדחה", "платёж отклонён"),
                rule("משיכת מזומן", "снятие наличных"),
                rule("הפקדת מזומן", "внесение наличных"),
                rule("חשבון עובר ושב", "текущий счёт"),
                rule("פירוט עסקאות", "список операций"),
                rule("עמלת המרה", "комиссия за конвертацию"),
                rule("ריבית שנתית", "годовая процентная ставка"),
                rule("תוקף הכרטיס", "срок действия карты"),
                rule("הכרטיס נחסם", "карта заблокирована"),
            ),
        ),
        Topic(
            domain = Domain.GOVERNMENT,
            keywords = listOf(
                "בקשה", "מסמכים", "תעודת זהות", "ביטוח לאומי", "משרד הפנים",
                "רשות המסים", "קצבה", "ועדה רפואית", "זכאות",
            ),
            senderHints = listOf(
                "gov.il", "btl", "ביטוח לאומי", "רשות המסים", "משרד הפנים",
                "רשות האוכלוסין",
            ),
            rules = listOf(
                rule("בקשתך התקבלה", "ваше заявление получено"),
                rule("הבקשה אושרה", "заявление одобрено"),
                rule("הבקשה נדחתה", "заявление отклонено"),
                rule("השלמת מסמכים", "предоставление недостающих документов"),
                rule("מסמכים חסרים", "отсутствуют необходимые документы"),
                rule("תעודת זהות", "удостоверение личности"),
                rule("מספר זהות", "номер удостоверения личности"),
                rule("ביטוח לאומי", "Ведомство национального страхования"),
                rule("משרד הפנים", "Министерство внутренних дел"),
                rule("רשות המסים", "Налоговое управление"),
                rule("רשות האוכלוסין וההגירה", "Управление народонаселения и миграции"),
                rule("קצבת נכות", "пособие по инвалидности"),
                rule("אחוזי נכות", "процент инвалидности"),
                rule("ועדה רפואית", "медицинская комиссия"),
                rule("זימון לוועדה", "вызов на комиссию"),
                rule("תשלום הקצבה", "выплата пособия"),
                rule("חוב לתשלום", "задолженность к оплате"),
                rule("אישור זכאות", "подтверждение права на льготу"),
                rule("פנייה מקוונת", "онлайн-обращение"),
            ),
        ),
        Topic(
            domain = Domain.WORK,
            keywords = listOf(
                "משמרת", "סידור עבודה", "שעות עבודה", "תלוש שכר", "מנהל משמרת",
                "נוכחות", "חופשת מחלה", "שעות נוספות",
            ),
            senderHints = emptyList(),
            rules = listOf(
                rule("משמרת בוקר", "утренняя смена"),
                rule("משמרת ערב", "вечерняя смена"),
                rule("משמרת לילה", "ночная смена"),
                rule("סידור עבודה", "рабочий график"),
                rule("שעות עבודה", "рабочие часы"),
                rule("שעות נוספות", "сверхурочные часы"),
                rule("יום חופשה", "день отпуска"),
                rule("חופשת מחלה", "больничный"),
                rule("אישור מחלה", "справка о болезни"),
                rule("תלוש שכר", "расчётный лист"),
                rule("שכר שעתי", "почасовая оплата"),
                rule("החתמת נוכחות", "отметка прихода на работу"),
                rule("דיווח נוכחות", "учёт рабочего времени"),
                rule("מנהל משמרת", "начальник смены"),
                rule("החלפת משמרת", "обмен сменами"),
                rule("איחור לעבודה", "опоздание на работу"),
                rule("הדרכת בטיחות", "инструктаж по технике безопасности"),
                rule("ציוד מגן", "средства защиты"),
                rule("תאונת עבודה", "производственная травма"),
            ),
        ),
        Topic(
            domain = Domain.SCHOOL,
            keywords = listOf(
                "בית ספר", "כיתה", "הורים", "שיעורי בית", "מערכת שעות", "טיול שנתי",
                "גן ילדים", "צהרון", "מחנכת", "מחנך",
            ),
            senderHints = listOf("בית ספר", "school", "גן כלנית"),
            rules = listOf(
                rule("אסיפת הורים", "родительское собрание"),
                rule("יום הורים", "день встреч с родителями"),
                rule("מחנך הכיתה", "классный руководитель"),
                rule("מחנכת הכיתה", "классная руководительница"),
                rule("שיעורי בית", "домашнее задание"),
                rule("מערכת שעות", "расписание уроков"),
                rule("הסעה לבית הספר", "школьная развозка"),
                rule("שעת איסוף", "время посадки"),
                rule("אישור הורים", "согласие родителей"),
                rule("טיול שנתי", "ежегодная школьная экскурсия"),
                rule("יום לימודים", "учебный день"),
                rule("אין לימודים", "занятий не будет"),
                rule("חופשת חג", "праздничные каникулы"),
                rule("יש להביא", "нужно принести"),
                rule("ארוחת בוקר", "завтрак"),
                rule("צהרון", "группа продлённого дня"),
                rule("גן ילדים", "детский сад"),
                rule("מסיבת סיום", "выпускной праздник"),
                rule("תכנית לימודים", "учебная программа"),
            ),
        ),
        Topic(
            domain = Domain.TRANSPORT,
            keywords = listOf(
                "רכבת", "אוטובוס", "תחנה", "קו", "רב קו", "נסיעה", "רציף", "מסלול",
            ),
            senderHints = listOf(
                "egged", "אגד", "moovit", "rav-kav", "רב קו", "רכבת ישראל",
                "israel railways", "metronit", "מטרונית",
            ),
            rules = listOf(
                rule("הרכבת מאחרת", "поезд задерживается"),
                rule("הרכבת בוטלה", "поезд отменён"),
                rule("שינוי במסלול", "изменение маршрута"),
                rule("תחנת מוצא", "начальная остановка"),
                rule("תחנת יעד", "конечная остановка"),
                rule("תחנת רכבת", "железнодорожная станция"),
                rule("תחנת אוטובוס", "автобусная остановка"),
                rule("קו אוטובוס", "автобусный маршрут"),
                rule("זמן הגעה משוער", "расчётное время прибытия"),
                rule("עומסי תנועה", "дорожные пробки"),
                rule("עבודות בכביש", "дорожные работы"),
                rule("רב קו", "карта «Рав-Кав»"),
                rule("טעינת רב קו", "пополнение карты «Рав-Кав»"),
                rule("יתרת נסיעות", "остаток поездок"),
                rule("תעריף נסיעה", "стоимость поездки"),
                rule("נסיעה חינם", "бесплатная поездка"),
                rule("ירידה בתחנה", "выход на остановке"),
            ),
        ),
        Topic(
            domain = Domain.UTILITIES,
            keywords = listOf(
                "חשמל", "מים", "גז", "מונה", "חשבונית", "תקלה", "תחזוקה", "טכנאי",
                "אינטרנט", "תשלום חשבון",
            ),
            senderHints = listOf(
                "חברת החשמל", "iec", "מי כרמל", "bezeq", "בזק", "hot", "partner",
                "cellcom", "סלקום",
            ),
            rules = listOf(
                rule("חשבון חשמל", "счёт за электричество"),
                rule("חשבון מים", "счёт за воду"),
                rule("חשבון גז", "счёт за газ"),
                rule("קריאת מונה", "показания счётчика"),
                rule("צריכת חשמל", "потребление электроэнергии"),
                rule("צריכת מים", "потребление воды"),
                rule("הפסקת חשמל", "отключение электричества"),
                rule("הפסקת מים", "отключение воды"),
                rule("תקלה אזורית", "районная авария"),
                rule("עבודות תחזוקה", "технические работы"),
                rule("התשלום התקבל", "платёж получен"),
                rule("יתרה לתשלום", "остаток к оплате"),
                rule("חשבונית חודשית", "ежемесячный счёт"),
                rule("מועד אחרון לתשלום", "последний срок оплаты"),
                rule("חיבור לאינטרנט", "подключение к интернету"),
                rule("תקלה באינטרנט", "неисправность интернета"),
                rule("ביקור טכנאי", "визит техника"),
            ),
        ),
        Topic(
            domain = Domain.CONSTRUCTION,
            keywords = listOf(
                "חומרי בניין", "כלי עבודה", "גבס", "קרמיקה", "איטום", "בטון", "מלט",
                "טיח", "בורג", "דיבל", "מקדחה",
            ),
            senderHints = listOf("home center", "הום סנטר", "ace", "אייס"),
            rules = listOf(
                rule("חומרי בניין", "строительные материалы"),
                rule("כלי עבודה", "инструменты"),
                rule("צבע לקיר", "краска для стен"),
                rule("צבע אקרילי", "акриловая краска"),
                rule("לוח גבס", "гипсокартонный лист"),
                rule("קיר גבס", "гипсокартонная перегородка"),
                rule("דבק קרמיקה", "клей для плитки"),
                rule("רובה לקרמיקה", "затирка для плитки"),
                rule("אריחי קרמיקה", "керамическая плитка"),
                rule("צמר סלעים", "каменная вата"),
                rule("חומר איטום", "герметизирующий материал"),
                rule("איטום גג", "гидроизоляция крыши"),
                rule("בטון מוכן", "готовый бетон"),
                rule("ציוד בטיחות", "защитное снаряжение"),
                rule("מידות המוצר", "размеры изделия"),
                rule("כושר נשיאה", "допустимая нагрузка"),
                rule("מלט", "цемент"),
                rule("טיח", "штукатурка"),
                rule("בורג", "винт"),
                rule("דיבל", "дюбель"),
                rule("מקדחה", "дрель"),
                rule("משחזת זווית", "угловая шлифовальная машина"),
            ),
        ),
        Topic(
            domain = Domain.SECURITY,
            keywords = listOf(
                "קוד חד פעמי", "קוד אימות", "סיסמה", "התחברות", "פעילות חריגה",
                "אימות דו שלבי", "הונאה", "איפוס סיסמה",
            ),
            senderHints = emptyList(),
            rules = listOf(
                rule("קוד חד פעמי", "одноразовый код"),
                rule("קוד אימות", "код подтверждения"),
                rule("קוד כניסה", "код входа"),
                rule("סיסמה זמנית", "временный пароль"),
                rule("אין להעביר את הקוד", "никому не передавайте код"),
                rule("אין למסור את הקוד", "никому не сообщайте код"),
                rule("הקוד בתוקף", "код действителен"),
                rule("ניסיון התחברות", "попытка входа"),
                rule("התחברות חדשה", "новый вход"),
                rule("מכשיר חדש", "новое устройство"),
                rule("פעילות חריגה", "подозрительная активность"),
                rule("אם לא ביצעת", "если это были не вы"),
                rule("חסימת החשבון", "блокировка учётной записи"),
                rule("איפוס סיסמה", "сброс пароля"),
                rule("אימות דו שלבי", "двухэтапная проверка"),
                rule("קישור מאובטח", "защищённая ссылка"),
                rule("אין ללחוץ על קישורים", "не переходите по ссылкам"),
                rule("הודעת הונאה", "мошенническое сообщение"),
                rule("אישור פעולה", "подтверждение операции"),
            ),
        ),
    )

    private val groceryPricePattern = Regex(
        """(?iu)(?:[€£¥₹₽₺₩₪${'$'}]\s*\d|\d(?:[\d\s.,]*\d)?\s*(?:[€£¥₹₽₺₩₪${'$'}]|ש[\"״']?ח|(?:usd|eur|gbp|ils|nis|chf|cad|aud|nzd|pln|czk|huf|ron|bgn|sek|nok|dkk|aed|sar|jpy|cny|inr|rub|uah)\b))""",
    )

    private val groceryUnitHints = listOf(
        "לק\"ג", "לק״ג", "ק\"ג", "ק״ג", "למארז", "מארז", "ליחידה", "גרם",
        "/kg", "per kg", "per kilo", "kg", " kgs", "gram", "grams", " pack",
        "packs", "за кг", "кг", "упаков", "за упаковку", "كيلو", "كجم", "غرام",
        "عبوة", "por kg", "por kilo", "paquete", "par kg", "paquet", "pro kg",
        "confezione", "al kg", "za kg", "opakowanie",
    )

    private val groceryProductHints = listOf(
        // Hebrew
        "ירקות", "פירות", "בשר", "עוף", "דגים", "חלב", "גבינה", "לחם", "ביצים",
        "עגבניות", "בצל", "כרוב", "גזר", "בטטה", "שום", "קפוא", "טרי",
        // English
        "grocery", "supermarket", "vegetables", "fruit", "fruits", "meat", "chicken",
        "fish", "milk", "cheese", "bread", "eggs", "tomatoes", "onions", "frozen", "fresh",
        // Russian
        "продукты", "супермаркет", "овощи", "фрукты", "мясо", "курица", "рыба",
        "молоко", "сыр", "хлеб", "яйца", "помидоры", "лук", "замороженный", "свежий",
        // Arabic
        "خضار", "فواكه", "لحم", "دجاج", "سمك", "حليب", "جبن", "خبز", "بيض",
        "طماطم", "بصل", "مجمد", "طازج",
        // Spanish, French, German, Italian and Portuguese
        "verduras", "frutas", "carne", "leche", "queso", "tomates", "cebollas",
        "congelado", "fresco", "légumes", "viande", "lait", "fromage", "oignons",
        "surgelé", "frais", "gemüse", "fleisch", "milch", "käse", "zwiebeln",
        "tiefgekühlt", "frisch", "verdura", "frutta", "latte", "formaggio", "pomodori",
        "cipolle", "congelato", "fresca", "legumes", "queijo", "congelados",
    )

    private val groceryPromotionHints = listOf(
        "מבצע", "הנחה", "בתוקף", "עד גמר המלאי", "בקנייה", "לחברי מועדון",
        "sale", "offer", "discount", "valid until", "while stocks last", "club members",
        "акция", "скидка", "действует до", "до окончания запасов",
        "عرض", "خصم", "حتى نفاد الكمية", "oferta", "descuento", "promotion", "remise",
        "angebot", "rabatt", "offerta", "sconto", "promoção", "desconto",
    )

    private val russianNames = mapOf(
        Domain.GROCERY to "Продуктовый магазин",
        Domain.DELIVERY to "Доставка и посылки",
        Domain.MEDICAL to "Медицина",
        Domain.PHARMACY to "Аптека",
        Domain.BANKING to "Банк и платежи",
        Domain.GOVERNMENT to "Государственные службы",
        Domain.WORK to "Работа",
        Domain.SCHOOL to "Школа и детский сад",
        Domain.TRANSPORT to "Транспорт",
        Domain.UTILITIES to "Коммунальные услуги и связь",
        Domain.CONSTRUCTION to "Строительство и ремонт",
        Domain.SECURITY to "Коды и безопасность",
    )
    private val hebrewNames = mapOf(
        Domain.GROCERY to "סופרמרקט ומזון",
        Domain.DELIVERY to "משלוחים וחבילות",
        Domain.MEDICAL to "רפואה",
        Domain.PHARMACY to "בית מרקחת",
        Domain.BANKING to "בנק ותשלומים",
        Domain.GOVERNMENT to "שירותים ממשלתיים",
        Domain.WORK to "עבודה",
        Domain.SCHOOL to "בית ספר וגן ילדים",
        Domain.TRANSPORT to "תחבורה",
        Domain.UTILITIES to "חשבונות ותקשורת",
        Domain.CONSTRUCTION to "בנייה ושיפוצים",
        Domain.SECURITY to "קודים ואבטחה",
    )
    private val englishNames = mapOf(
        Domain.GROCERY to "Grocery store",
        Domain.DELIVERY to "Delivery and parcels",
        Domain.MEDICAL to "Medical",
        Domain.PHARMACY to "Pharmacy",
        Domain.BANKING to "Banking and payments",
        Domain.GOVERNMENT to "Government services",
        Domain.WORK to "Work",
        Domain.SCHOOL to "School and kindergarten",
        Domain.TRANSPORT to "Transport",
        Domain.UTILITIES to "Utilities and telecom",
        Domain.CONSTRUCTION to "Construction and repair",
        Domain.SECURITY to "Codes and security",
    )

    private const val MIN_DOMAIN_SCORE = 3
    private const val RULESET_VERSION = "he-ru-domain-rules-v6-notifications"
}
