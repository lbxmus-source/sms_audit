package com.berelson.smstranslator.translation

/** Reviewed, offline-only data. See HEBREW_RULES.md before extending these lists. */
object HebrewPrefixRules {
    enum class EntityType {
        PERSON, SURNAME, CITY, STREET, COMPANY, BRAND, ORGANIZATION, STORE,
        COURIER, MEDICAL, PLACE, CUSTOM_PROTECTED,
    }

    data class Entity(val hebrew: String, val russian: String?, val type: EntityType)
    data class Split(val prefixes: String, val stem: String)
    data class ContextRule(val word: String, val pattern: Regex)

    val forceSplit = mapOf(
        "בסמוטריץ" to Split("ב", "סמוטריץ"),
        "לסמוטריץ" to Split("ל", "סמוטריץ"),
        "מסמוטריץ" to Split("מ", "סמוטריץ"),
        "וסמוטריץ" to Split("ו", "סמוטריץ"),
    )

    // Whole-word protection always precedes automatic analysis, including ambiguous מ.
    val neverSplit = setOf(
        "שלום", "שלם", "שלח", "שלחו", "משלוח", "משלוחים", "ממתין", "מלאי",
        "מחיר", "משפחה", "מכתב", "מלך", "מים", "מחר", "מקום", "בשר", "בית",
        "בצל", "בגלל", "בחר", "בחירה", "בוחרים", "לשון", "לחם", "לבן", "למה",
        "כלב", "כרוב", "כלכלה", "כיף", "הודעה", "הזמנה", "הסרה", "שום", "שוק",
        "שנה", "שם", "שיר", "שמש", "שעות", "שירות", "ועדה", "ורד", "וילון",
    )

    // Small reviewed vocabulary, NOT a claim to contain a complete Hebrew dictionary.
    val knownWords = neverSplit + setOf(
        "חנות", "מרפאה", "רופא", "ספר", "ילד", "ילדים", "תור", "עבודה", "חבילה",
        "פגישה", "איסוף", "פתיחה", "עסקים", "מוצרים", "ירקות", "פירות",
    )

    val entities = listOf(
        Entity("סמוטריץ", "Смотрич", EntityType.SURNAME),
        Entity("סמוטריץ׳", "Смотрич", EntityType.SURNAME),
        Entity("סמוטריץ'", "Смотрич", EntityType.SURNAME),
        Entity("חיפה", "Хайфа", EntityType.CITY),
        Entity("ירושלים", "Иерусалим", EntityType.CITY),
        Entity("תל אביב", "Тель-Авив", EntityType.CITY),
        Entity("טירת כרמל", null, EntityType.CITY),
        Entity("חורב", null, EntityType.STREET),
        Entity("חנה סנש", null, EntityType.STREET),
        Entity("הכלניות", null, EntityType.STREET),
        Entity("שופרסל", null, EntityType.STORE),
        Entity("יוניברס", null, EntityType.STORE),
        Entity("סטופמרקט", null, EntityType.STORE),
        Entity("בנט", "Беннет", EntityType.SURNAME),
        Entity("איזנקוט", "Айзенкот", EntityType.SURNAME),
        Entity("בן גביר", "Бен-Гвир", EntityType.SURNAME),
        Entity("אביב גפן", null, EntityType.PERSON),
        Entity("שב\"ק ס", null, EntityType.ORGANIZATION),
        Entity("סאבלימינל והצל", null, EntityType.PERSON),
        Entity("צ'יטה", null, EntityType.COURIER),
        Entity("צ׳יטה", null, EntityType.COURIER),
    )

    // Explicitly reviewed combinations; observations alone never enter this set.
    val confirmedSplits: Set<String> = emptySet()
    val contextRules = listOf(
        ContextRule("והחבילה", Regex("(?:נשלחה|הגיעה|ממתינה)")),
        ContextRule("שהחבילה", Regex("(?:הודעה|עדכון|נמסר)")),
        ContextRule("החבילה", Regex("(?:משלוח|לאיסוף|שליח|ממתינה)")),
    )

    const val VERSION = "he-prefixes-2-phrases"
}
