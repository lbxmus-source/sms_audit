package com.berelson.smstranslator.translation

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class NotificationRulesTest {
    private fun prepare(source: String, sender: String = "") = OfflineTranslationPreparation.prepare(
        source, "he", "ru", DomainRuleEngine.detect(source), sender = sender,
    )
    private fun checkPrepared(source: String, sender: String = ""): OfflineTranslationPreparation.Prepared {
        val p = prepare(source, sender)
        val safe = TranslationValidator.validate(source, p.text, p.protectedTerms)
        assertTrue("$safe\n${p.text}", safe.valid)
        val plan = TokenProtector.protect(p.text, p.protectedTerms + p.fixedTerms)
        assertEquals(p.text, TokenProtector.restore(plan, plan.encodedText))
        return p
    }

    @Test fun inventoryPreservesAnUnknownStoreAndAllVariableFields() {
        for (store in listOf("מינימרקט תנובה,הכלניות 2 טירת כרמל", "חנות הבדיקה,הרצל 88 נתניה")) {
            val source = "שלום Test Person,\nבתאריך 18/09 בשעה 08:21 ביצענו ספירת מלאי בחנות $store והמשלוח שמספרו AE041531431 (חבילה 102194129) עדיין מחכה לך שתבוא לאסוף אותו. לשעות פתיחה, פרטים ואישור איסוף לחץ https://example.test/pickup מומלץ לאסוף תוך 2 ימי עסקים. בברכה צ'יטה שליחויות *אין להשיב להודעה זו*"
            val p = checkPrepared(source, "CHEETAH")
            assertTrue(p.text, p.text.contains("Мы провели инвентаризацию в магазине"))
            assertTrue(p.text, p.text.contains(store))
            assertTrue((p.fixedTerms + p.protectedTerms).contains(store))
            for (field in listOf("18/09", "08:21", "AE041531431", "102194129", "https://example.test/pickup")) {
                assertTrue(p.text.contains(field))
                assertFalse(TranslationValidator.validate(source, p.text.replace(field, ""), p.protectedTerms).valid)
            }
        }
    }

    @Test fun surveyNegationAndAttitudeAreNotReversed() {
        val source = "מה עמדתך על נתניהו כראש ממשלה?\n1. מוקיר את פועלו ועליו להמשיך\n2. מוקיר את פועלו אך עליו לפרוש\n3. לא מוקיר את פועלו אך אין מועמד טוב ממנו\n4. לא מוקיר את פועלו אך עליו לפרוש\n5. לא יודע\nלהסרה שלחו 'הסר'"
        val p = checkPrepared(source)
        assertTrue(p.text.contains("1. Ценю его работу; считаю, что он должен продолжать"))
        assertTrue(p.text.contains("2. Ценю его работу, но считаю, что он должен уйти в отставку"))
        assertTrue(p.text.contains("3. Не ценю его работу, но лучшего кандидата нет"))
        assertTrue(p.text.contains("4. Не ценю его работу; считаю, что он должен уйти в отставку"))
        assertEquals("הוא מוקיר את פועלו", prepare("הוא מוקיר את פועלו").text)
    }

    @Test fun municipalityMeansKindergartenPlacementNotABath() {
        val source = "הורים יקרים שלום רב,\nבאפשרותכם לצפות בשיבוץ ילדיכם לגן עירוני לתשפ״ז באתר עיריית חיפה בקישור המצורף. https://example.test/edu?customerId=140000\nכמו כן, אין צורך לעדכן את חברת הצהרונים בנוגע לשיבוץ אנו מתואמים בנושא מולם.\nלמידע נוסף ולשאלות ניתן ליצור קשר עם מוקד המידע: 1-700-50-7002 שלוחה 1.\nבברכה, מרכז רישום ומידע, מינהל החינוך – עיריית חיפה\nלהסרה לחצו על https://example.test/unsub"
        val p = checkPrepared(source, "HaifaMuni")
        assertTrue(p.text, p.text.contains("зачисления ваших детей в муниципальный детский сад"))
        assertTrue(p.text.contains("организатору продлёнки"))
        assertTrue(p.text.contains("на учебный год תשפ״ז"))
        assertTrue(p.text.contains("добавочный 1"))
        assertEquals("אני הולך לגן", prepare("אני הולך לגן").text)
    }

    @Test fun phoneNotificationDistinguishesLocationFromOrigin() {
        for (quote in listOf("\"", "״")) {
            val source = "מי התקשר בחו${quote}ל:מנוי +97246197000 התקשר אליך ב31/07 09:18 שעון ישראל"
            val p = checkPrepared(source)
            assertTrue(p.text, p.text.contains("Кто звонил, пока вы были за границей"))
            assertTrue(p.text, p.text.contains("звонил вам 31/07"))
            assertTrue(p.text.contains("по израильскому времени"))
            val other = prepare(source.replace("בחו", "מחו"))
            assertTrue(other.text.contains("Кто звонил из-за границы"))
        }
    }

    @Test fun postalAddressLabelCanTranslateButAddressCannotChange() {
        val source = "להזכירך-משלוח RS1337658066Y ח 1017 עדיין ממתין לך ביחידת הדואר- 7DAYS (בית עסק)- כתובת שדרות הנשיא 119 חיפה.\nלאחר קבלת המשלוח יש ללחוץ על https://example.test/received\nלמידע נוסף לחץ: https://example.test/info\nלתשומת לבך, ליחידות הדואר נדרש זימון תור מראש. תודה, דואר ישראל"
        val p = checkPrepared(source, "Israel_Post")
        assertTrue(p.text, p.text.contains("торговая точка"))
        assertTrue(p.text, p.text.contains("Адрес שדרות הנשיא 119 חיפה"))
        assertFalse(TranslationValidator.validate(source, p.text.replace("119", "118"), p.protectedTerms).valid)
        assertEquals("אני הולך לבית עסק", prepare("אני הולך לבית עסק").text)
    }

    @Test fun deliveryCompanyIsNeitherNotebookNorADifferentMerchant() {
        for (company in listOf("iHerb", "OtherShop", "Fresh Store")) {
            val source = "לקוח יקר, הזמנתך שמספרה 555679240-0 מחברת $company צפויה להגיע אליך היום. אין צורך להמתין בכתובת לשליח.לאישור השארת החבילה ליד הדלת יש להיכנס לקישור הבא: https://example.test/order/555679240-0"
            val p = checkPrepared(source, "orian")
            assertTrue(p.text, p.text.contains("от компании $company"))
            assertTrue((p.fixedTerms + p.protectedTerms).contains(company))
            assertTrue(p.text.contains("Вам не нужно ждать курьера"))
            assertTrue(p.text.contains("разрешить оставить посылку у двери"))
        }
        assertTrue(HebrewNotificationRules.entities("прочитайте מחברת Notebook").isEmpty())
        assertTrue(prepare("המשלוח כולל מחברת לילד").text.contains("מחברת"))
    }

    @Test fun collectionDriverAndContainersHaveContextualMeanings() {
        val source = "לקוח יקר נהג של חברת סייקל נהג בדיקה, 054-8000000 . יגיע היום לאסוף את מיכלי המשקה לפי הזמנתך . נא וודא שהשקים ארוזים לפי הנוהל, נגישים לנהג ומסומנים במספר הלקוח . תודה סייקל"
        val p = checkPrepared(source, "CYCLE")
        assertTrue(p.text, p.text.contains("Водитель компании סייקל נהג בדיקה"))
        assertTrue(p.text.contains("тару от напитков"))
        assertTrue(p.text.contains("доступны водителю"))
        assertTrue((p.fixedTerms + p.protectedTerms).contains("סייקל נהג בדיקה"))
        assertEquals("נהג הוא מושג אחר", prepare("נהג הוא מושג אחר").text)
    }

    @Test fun ranksAndInvitationsHandleQuoteVariantsAndDifferentDates() {
        for (quotes in listOf("רא\"ל (מיל')", "רא״ל (מיל׳)", "רא״ל (מיל')")) {
            val source = "אנחנו מתכבדים להזמינך לשיחה על ביטחון, תקווה ומנהיגות ראויה – עם $quotes ושר קבינט המלחמה לשעבר גדי איזנקוט. 23/09 ביום רביעי בשעה 20:00 שדרות הנשיא 140, חיפה. להרשמה: https://example.test/register"
            val p = checkPrepared(source, "YASHAR")
            assertTrue(p.text, p.text.contains("с генерал-лейтенантом запаса"))
            assertTrue(p.text.contains("бывшим членом военного кабинета"))
        }
        for (date in listOf("06/09", "13/10")) {
            val source = "חברים וחברות יקרים, אנחנו מזמינים אתכם לכנס הבחירות של מפלגת ישראל ביתנו. יום ראשון, $date בשעה 18:30 ביתן 10, גני התערוכה (אקספו), תל אביב. *השתתפות בהרשמה מראש*- מספר המקומות מוגבל! נא להירשם בקישור המצורף⬇️ https://example.test/event להסרה יש להשיב \"הסר\" למספר: 0537000000"
            val p = checkPrepared(source, "BEYTENU")
            assertTrue(p.text.contains("предварительной регистрации"))
            assertTrue(p.text.contains("Количество мест ограничено"))
            assertTrue(p.text.contains(date))
            sync {
                val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, false, "he", "ru") {
                    error("Reviewed invitation should retain all fields without a model call")
                }
                assertTrue(result.contains("Количество мест ограничено"))
            }
        }
    }

    @Test fun calReviewedOfferDoesNotNeedModelToTranslateRemainingPhoneLabel() = sync {
        for (comma in listOf(",", ", ")) {
            val source = "אמפי תל אביב- כאל לפני כולם!\nההנחה הכי גדולה לכרטיסים למופעים הכי חמים: אביב גפן, שב\"ק ס, סאבלימינל והצל וגם לאונג' VIP משודרג הכולל אוכל, שתייה וכניסה נפרדת במחיר מיוחד ובלעדי ללקוחות כאל! מהרו להזמין${comma}המלאי מוגבל:\nhttps://dl.cal-online.co.il/mobile?mainLinkName=AmphiTLV_C&ts=cal&tm=sms&tc=\nתמורת חוויה. כפוף לתנאים. להסרה יש להשיב 1 למספר 055-7000000"
            val p = checkPrepared(source, "Cal")
            val remaining = TokenProtector.segments(p.text, p.protectedTerms + p.fixedTerms).filter { it.translatable && it.text.any { c -> c in 'א'..'ת' } }
            assertTrue("Remaining: $remaining\n${p.text}", remaining.isEmpty())
            val output = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, false, "he", "ru") {
                error("This reviewed offer has no natural Hebrew left to translate")
            }
            assertTrue(output.contains("Амфитеатр"))
            assertTrue(output.endsWith("на номер 055-7000000"))
        }
    }

    @Test fun userProtectedPhrasesAndUrlsStayUntouched() {
        val text = "מוקיר את פועלו https://example.test/כתובת"
        val p = OfflineTranslationPreparation.prepare(text, "he", "ru", null, listOf("מוקיר את פועלו"))
        assertEquals(text, p.text)
        assertEquals("данные: 14", prepare("данные: 14").text)
    }

    @Test fun businessDayEndingsFollowTheCurrentNumber() {
        for (days in listOf(1, 2, 11, 21, 22)) {
            val p = checkPrepared("משלוח מומלץ לאסוף תוך $days ימי עסקים")
            val ending = if (days == 1 || days == 21) "рабочего дня" else "рабочих дней"
            assertTrue(p.text, p.text.contains("Рекомендуем забрать в течение $days $ending"))
        }
    }

    private fun sync(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        requireNotNull(outcome).getOrThrow()
    }
}
