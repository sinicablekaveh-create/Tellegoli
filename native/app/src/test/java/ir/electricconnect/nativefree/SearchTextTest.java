package ir.electricconnect.nativefree;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchTextTest {
    @Test public void matchesPersianArabicLettersDigitsAndDiacritics() {
        assertTrue(SearchText.matches("شِرکت كيان ٠٩١٢ ABC", "abc کیان ۰۹۱۲"));
        assertTrue(SearchText.matches("سیم‌کابل تهران", "تهران سیم کابل"));
        assertFalse(SearchText.matches("شرکت کابل تهران", "کابل اصفهان"));
    }

    @Test public void blankQueryAndPhoneDigitsWork() {
        assertTrue(SearchText.matches("anything", "  "));
        assertEquals("+989121234567", SearchText.digits("+۹۸۹۱۲۱۲۳۴۵۶۷"));
        assertEquals("", SearchText.normalize(null));
    }

    @Test public void escapesSqlWildcardsAsLiteralText() {
        assertEquals("%50\\%\\_\\\\%", SearchText.likePattern("50%_\\"));
    }
}
