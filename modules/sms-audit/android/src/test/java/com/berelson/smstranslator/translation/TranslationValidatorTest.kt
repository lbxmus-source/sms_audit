package com.berelson.smstranslator.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationValidatorTest {
    private val source =
        "שלום, הכתובת היא חורב 15 חיפה. קוד CN100997761, הנחה 25%, מחיר ₪19.90 בשעה 14:30"

    @Test
    fun acceptsReadableTranslationWithExactTechnicalValues() {
        val result =
            "Здравствуйте, адрес: חורב 15 חיפה. Код CN100997761, скидка 25%, цена ₪19.90 в 14:30"
        assertTrue(TranslationValidator.validate(source, result).valid)
    }

    @Test
    fun rejectsChangedProtectedValue() {
        val result =
            "Здравствуйте, адрес: Хорев 15 Хайфа. Код CN100997762, скидка 25%, цена ₪19.90 в 14:30"
        assertFalse(TranslationValidator.validate(source, result).valid)
    }

    @Test
    fun rejectsLegacyAndUnrestoredMarkers() {
        assertFalse(TranslationValidator.validate("שלום", "ZZZZZZ").valid)
        assertFalse(TranslationValidator.validate("שלום", "Привет ⟦0⟧").valid)
        assertFalse(TranslationValidator.validate("שלום", "QXQXQX").valid)
        assertFalse(TranslationValidator.validate("שלום", "REMOVE").valid)
    }

    @Test
    fun allowsLegacyLookingCodeWhenItWasPresentInOriginal() {
        assertTrue(TranslationValidator.validate("קוד XQ10", "Код XQ10").valid)
    }

    @Test
    fun rejectsEmptyAndRepeatedGarbage() {
        assertFalse(TranslationValidator.validate("שלום", "").valid)
        assertFalse(TranslationValidator.validate("שלום", "аааааааа").valid)
        assertFalse(TranslationValidator.validate("351322", "code 351322").valid)
        assertFalse(
            TranslationValidator.validate(
                "שלום",
                "Это необоснованно длинный результат, которого совершенно не было в оригинале сообщения",
            ).valid
        )
    }

    @Test
    fun preservesUserProtectedTerm() {
        val original = "Встреча с Clalit завтра"
        val valid = "Meeting with Clalit tomorrow"
        val invalid = "Meeting with Клалит tomorrow"

        assertTrue(TranslationValidator.validate(original, valid, listOf("Clalit")).valid)
        assertFalse(TranslationValidator.validate(original, invalid, listOf("Clalit")).valid)
    }

    @Test
    fun acceptsHebrewPromotionWhenRussianGrammarReordersProtectedValues() {
        val original =
            "💥 15% קופון הנחה בתוקף עד 14.9.26 בשעה 06:00 https://fls.cx/xrhmzsp"
        val translated =
            "Купон на скидку 15% 💥 действует до 14.9.26 в 06:00 https://fls.cx/xrhmzsp"

        assertTrue(TranslationValidator.validate(original, translated).valid)
    }

    @Test
    fun acceptsOtpWhenPlainCodeAlsoAppearsInsideHashCode() {
        val original =
            "695147 הוא הקוד החד פעמי שלך לכניסה לאתר. הקוד בתוקף למשך 5 דקות. @my.mor.org.il #695147"
        val translated =
            "695147 — ваш одноразовый код для входа на сайт. Код действителен 5 минут. @my.mor.org.il #695147"

        assertTrue(TranslationValidator.validate(original, translated).valid)
    }

    @Test
    fun acceptsCheetahMessageWithCodeAddressAndLink() {
        val original =
            "שלום Alexander Berelson, משלוח CN100997761 ממתין לאיסוף, חורב 15 חיפה. לפרטים https://u.cheetahint.com/s13f2uw"
        val translated =
            "Здравствуйте, Alexander Berelson. Посылка CN100997761 ожидает получения по адресу חורב 15 חיפה. Подробности: https://u.cheetahint.com/s13f2uw"

        assertTrue(TranslationValidator.validate(original, translated).valid)
    }

    @Test
    fun segmentedFallbackAllowsContextSensitiveTokenDetection() {
        val original = "קוד #695147"
        val translated = "Код#695147"

        assertTrue(TranslationValidator.validate(original, translated).valid)
        assertTrue(TranslationValidator.validateSegmentedFallback(original, translated).valid)
    }
}
