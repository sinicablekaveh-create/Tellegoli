package ir.electricconnect.nativefree;

import java.text.Normalizer;
import java.util.Locale;

/** Shared normalization for local records, Telegram names and web result filtering. */
final class SearchText {
    private SearchText() {}

    static String digits(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c >= '۰' && c <= '۹') c = (char) ('0' + c - '۰');
            if (c >= '٠' && c <= '٩') c = (char) ('0' + c - '٠');
            out.append(c);
        }
        return out.toString();
    }

    static String normalize(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(digits(value), Normalizer.Form.NFKD)
            .toLowerCase(Locale.ROOT).replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
            .replace("ـ", "").replace('\u200c', ' ').replace('\u200d', ' ');
        return text.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").trim();
    }

    static String[] terms(String query) {
        String normalized = normalize(query);
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }

    static boolean matches(String value, String query) {
        String normalized = normalize(value);
        for (String term : terms(query)) if (!normalized.contains(term)) return false;
        return true;
    }

    static String likePattern(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
