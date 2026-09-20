package com.berelson.smstranslator.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenProtectorTest {
    @Test
    fun reconstructsOriginalWithoutGeneratedMarkers() {
        val source = "Код 483920. Оплата ₪ 125.50 до 12/09/2026, 18:30 https://example.com/a?b=1"
        val segments = TokenProtector.segments(source)

        assertEquals(source, segments.joinToString("") { it.text })
        assertFalse(segments.joinToString("") { it.text }.contains("ZXQ"))
    }

    @Test
    fun keepsOperationalDataOutOfTranslator() {
        val source = "Код R133765806Y, телефон +972 52-999-0326, ссылки https://example.com/a?b=1, hasr.co.il/rm/bRaUI и @my.mor.org.il"
        val protected = TokenProtector.segments(source)
            .filterNot { it.translatable }
            .map { it.text }

        assertTrue("R133765806Y" in protected)
        assertTrue("+972 52-999-0326" in protected)
        assertTrue("https://example.com/a?b=1" in protected)
        assertTrue("hasr.co.il/rm/bRaUI" in protected)
        assertTrue("@my.mor.org.il" in protected)
    }

    @Test
    fun protectsSelectedTermsWithoutMatchingInsideOtherWords() {
        val source = "Clalit и MyClalit: Clalit!"
        val segments = TokenProtector.segments(source, protectedTerms = listOf("Clalit"))
        val protected = segments.filterNot { it.translatable }.map { it.text }

        assertEquals(source, segments.joinToString("") { it.text })
        assertEquals(2, protected.count { it == "Clalit" })
    }

    @Test
    fun ordinaryPunctuationStaysWithReadableText() {
        val source = "Кто вы думаете, что лучше всего подходит?"
        val segments = TokenProtector.segments(source)

        assertEquals(listOf(TokenProtector.Segment(source, true)), segments)
    }

    @Test
    fun protectsCodesPercentagesAmountsDatesTimesAndSymbols() {
        val source =
            "Код 038492, PIN 753, скидка 17.5%, сумма ₪125.50, значение 99.95, 13/09/2026 21:45, заказ #AB-2048 ✓ ✅ 👍🏽 👨‍👩‍👧‍👦"
        val protected = TokenProtector.segments(source)
            .filterNot { it.translatable }
            .map { it.text }

        assertTrue("038492" in protected)
        assertTrue("753" in protected)
        assertTrue("17.5%" in protected)
        assertTrue("₪125.50" in protected)
        assertTrue("99.95" in protected)
        assertTrue("13/09/2026" in protected)
        assertTrue("21:45" in protected)
        assertTrue("#AB-2048" in protected)
        assertTrue("✓" in protected)
        assertTrue("✅" in protected)
        assertTrue("👍🏽" in protected)
        assertTrue("👨‍👩‍👧‍👦" in protected)
        assertEquals(source, TokenProtector.segments(source).joinToString("") { it.text })
    }

    @Test
    fun technicalStylingRangesExcludeLinksAndEmail() {
        val source = "Код AB-2048: 25%. https://example.com/a?x=25% и name@example.com"
        val highlighted = TokenProtector.technicalRanges(source).map { source.substring(it) }

        assertTrue("2048" in highlighted)
        assertFalse(highlighted.any { it.contains("AB-") })
        assertTrue("25%" in highlighted)
        assertFalse(highlighted.any { it.contains("example.com") })
    }

    @Test
    fun stylesOnlyDigitsWhenCodeTouchesLettersOrPunctuation() {
        val source = "2026Год 3239-ב CN100997761"
        val highlighted = TokenProtector.technicalRanges(source).map { source.substring(it) }

        assertEquals(listOf("2026", "3239", "100997761"), highlighted)
        assertFalse(highlighted.any { value -> value.any { !it.isDigit() } })
    }

    @Test
    fun createsIndexedMarkersAndRestoresEveryProtectedValue() {
        val source = "Код 351322, заказ CN100997761, скидка 25%, цена ₪19.90\n\nhttps://example.com"
        val plan = TokenProtector.protect(source)

        assertTrue(plan.tokens.isNotEmpty())
        assertTrue(plan.encodedText.contains("⟦0⟧"))
        assertFalse(plan.encodedText.contains("QX"))
        assertEquals(source, TokenProtector.restore(plan, plan.encodedText))
    }

    @Test
    fun rejectsMissingOrDuplicatedMarkerDuringRestore() {
        val plan = TokenProtector.protect("Код 351322")
        val marker = plan.tokens.single().marker

        assertEquals(null, TokenProtector.restore(plan, plan.encodedText.replace(marker, "")))
        assertEquals(null, TokenProtector.restore(plan, plan.encodedText + marker))
    }

    @Test
    fun restoresMarkerWhenTranslatorAddsSpacesInsideBrackets() {
        val plan = TokenProtector.protect("Код 351322")
        val translated = plan.encodedText.replace("⟦0⟧", "⟦ 0 ⟧")

        assertEquals("Код 351322", TokenProtector.restore(plan, translated))
    }

    @Test
    fun restoresReorderedMarkersWhenEveryMarkerIsStillPresentOnce() {
        val plan = TokenProtector.protect("Код 351322 и скидка 25%")
        val first = plan.tokens[0].marker
        val second = plan.tokens[1].marker
        val reordered = plan.encodedText
            .replace(first, "__first__")
            .replace(second, first)
            .replace("__first__", second)

        assertEquals(
            "Код 25% и скидка 351322",
            TokenProtector.restore(plan, reordered),
        )
    }

    @Test
    fun restoresHebrewPromotionWhenTranslationMovesPercentBeforeEmoji() {
        val source = "💥 15% קופון הנחה"
        val plan = TokenProtector.protect(source)
        val emoji = plan.tokens.first { it.kind == TokenProtector.Kind.EMOJI }
        val percent = plan.tokens.first { it.kind == TokenProtector.Kind.PERCENT }
        val translated = "Купон на скидку ${percent.marker} ${emoji.marker}"

        assertEquals(
            "Купон на скидку 15% 💥",
            TokenProtector.restore(plan, translated),
        )
    }

    @Test
    fun protectsConservativeIsraeliAddressAsOneValue() {
        val source = "המשלוח יגיע אל חורב 15 חיפה מחר"
        val address = TokenProtector.extractedTokens(source)
            .first { it.first == TokenProtector.Kind.ADDRESS }

        assertEquals("חורב 15 חיפה", address.second)
    }

    @Test
    fun labelledAddressDoesNotSwallowFollowingCode() {
        val source = "Здравствуйте, адрес: חורב 15 חיפה. Код CN100997761"
        val tokens = TokenProtector.extractedTokens(source)

        assertTrue(tokens.any {
            it.first == TokenProtector.Kind.ADDRESS &&
                it.second == "חורב 15 חיפה"
        })
        assertTrue(tokens.any {
            it.first == TokenProtector.Kind.CODE && it.second == "CN100997761"
        })
        assertFalse(tokens.any {
            it.first == TokenProtector.Kind.ADDRESS && it.second.contains("Код")
        })
    }

    @Test
    fun doesNotTreatEveryWordAndNumberAsAddress() {
        val source = "קניתי תפוחים 15 היום"
        assertFalse(
            TokenProtector.extractedTokens(source)
                .any { it.first == TokenProtector.Kind.ADDRESS }
        )
    }
}
