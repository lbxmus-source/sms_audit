package com.berelson.smstranslator.translation

/** Exact, complete delivery constructions. No dependence on sender names.
 * If any part is unfamiliar, leave the message to the normal contextual translator.
 * Variable names, pickup locations, identifiers, dates and links come only from the source.
 */
object HebrewDeliveryTemplates {
    fun translate(text: String, protectedTerms: List<String> = emptyList()): String? {
        // Keep the ordinary pipeline in charge of original paragraph formatting.
        if ('\n' in text || '\r' in text) return null
        val pickupMatch = pickup.matchEntire(text)
        val match = pickupMatch ?: reminder.matchEntire(text) ?: return null
        fun part(name: String) = match.groups[name]?.value.orEmpty()
        val recipient = part("name")
        val location = part("location")
        val code = part("code")
        val url = part("url")
        val days = part("days")
        val dayNumber = days.toInt()
        val dayEnding = if (dayNumber % 10 == 1 && dayNumber % 100 != 11) "рабочего дня" else "рабочих дней"
        val output = if (pickupMatch != null) {
            "Здравствуйте, $recipient! Ваша посылка $code ожидает получения: $location. " +
                "Часы работы, подробности и подтверждение получения: $url " +
                "Рекомендуем забрать в течение $days $dayEnding. "
        } else {
            "Здравствуйте, $recipient! ${part("date")} в ${part("time")} " +
                "мы провели проверку наличия отправлений в магазине $location. " +
                "Ваша посылка $code (номер ${part("parcel")}) всё ещё ожидает получения. " +
                "Часы работы, подробности и подтверждение получения: $url " +
                "Рекомендуем забрать в течение $days $dayEnding. "
        }
        val result = output + "Спасибо, ${part("courier")}. Не отвечайте на это сообщение."
        return result.takeIf { TranslationValidator.validate(text, it, protectedTerms).valid }
    }

    private const val GREETING = "\\s*שלום\\s+(?<name>[^,\r\n]{1,80}),\\s*"
    private const val CODE = "(?<code>[A-Z]{1,4}[0-9]{6,18})"
    private const val ENDING = "\\s*לשעות\\s+פתיחה,?\\s*פרטים\\s+ואישור\\s+איסוף\\s+לחץ\\s+" +
        "(?<url>https?://[^\\s]+?)[.]?\\s+מומלץ\\s+לאסוף\\s+תוך\\s+" +
        "(?<days>[0-9]{1,2})\\s+ימי\\s+עסקים[.]?\\s*" +
        "(?:תודה|בברכה)\\s+(?<courier>[^*\r\n]{2,50}?)\\s*\\*?אין\\s+להשיב(?:\\s+להודעה\\s+זו)?\\*?[.]?\\s*"

    private val pickup = Regex(GREETING +
        "איזה\\s+כיף\\s*[:：]\\),?\\s*משלוח\\s+" + CODE +
        "\\s+ממתין\\s+לאיסוף\\s+ב\\s*(?<location>[^\r\n]{2,120}?)\\.\\s*" + ENDING)
    private val reminder = Regex(GREETING +
        "בתאריך\\s+(?<date>[0-9]{1,2}/[0-9]{1,2}(?:/[0-9]{2,4})?)\\s+בשעה\\s+" +
        "(?<time>[0-9]{1,2}:[0-9]{2})\\s+ביצענו\\s+ספירת\\s+מלאי\\s+בחנות\\s+" +
        "(?<location>[^\r\n]{2,120}?)\\s+והמשלוח\\s+שמספרו\\s+" + CODE +
        "\\s*\\(חבילה\\s*(?<parcel>[0-9]{6,18})\\)\\s*עדיין\\s+מחכה\\s+לך\\s+" +
        "שתבוא\\s+לאסוף\\s+אותו[.]?\\s*" + ENDING)
}
