package ir.electricconnect.nativefree;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ExaHttpErrorTest {
    @Test public void unauthorizedReportsAuthenticationWithSafeTag() {
        String result = ExaHttpError.describe(401,
                "{\"tag\":\"INVALID_API_KEY\",\"error\":\"Invalid API key\"}");
        assertTrue(result.contains("HTTP 401"));
        assertTrue(result.contains("INVALID_API_KEY"));
        assertTrue(result.contains("کلید فعال"));
    }

    @Test public void forbiddenDoesNotIncorrectlyAssertInvalidKey() {
        String result = ExaHttpError.describe(403, "{\"tag\":\"FORBIDDEN\"}");
        assertTrue(result.contains("HTTP 403"));
        assertTrue(result.contains("FORBIDDEN"));
        assertTrue(result.contains("ثابت نمی‌کند"));
    }

    @Test public void requestValidationIsNotAnAuthenticationFailure() {
        String result = ExaHttpError.describe(400, "{\"tag\":\"INVALID_REQUEST_BODY\"}");
        assertTrue(result.contains("HTTP 400"));
        assertTrue(result.contains("تنظیمات جست‌وجوی برنامه"));
    }

    @Test public void billingAndRateLimitHaveDistinctMessages() {
        assertTrue(ExaHttpError.describe(402, "").contains("صورتحساب"));
        assertTrue(ExaHttpError.describe(429, "").contains("تعداد درخواست"));
    }

    @Test public void invalidServerBodyDoesNotEchoSecrets() {
        String secret = "my-secret-token";
        String result = ExaHttpError.describe(500, secret + "<html>failure</html>");
        assertTrue(result.contains("HTTP 500"));
        assertFalse(result.contains(secret));
    }

    @Test public void maliciousTagIsNotRendered() {
        String result = ExaHttpError.describe(401,
                "{\"tag\":\"INVALID_API_KEY\\nPRIVATE_STRING\"}");
        assertTrue(result.contains("HTTP 401"));
        assertFalse(result.contains("PRIVATE_STRING"));
    }
}
