package app.basera.common.validation;

import java.text.Normalizer;

/**
 * Input hygiene for free text that will be stored and later rendered. This is NOT an HTML sanitiser:
 * output encoding is the frontend's job (React escapes by default; never use dangerouslySetInnerHTML on
 * user text). This strips control characters, normalises Unicode and trims, so stored data is clean.
 */
public final class TextSanitizer {

    private TextSanitizer() {}

    public static String clean(String input) {
        if (input == null) {
            return null;
        }
        String normalised = Normalizer.normalize(input, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(normalised.length());
        for (int i = 0; i < normalised.length(); i++) {
            char c = normalised.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static String cleanSingleLine(String input) {
        String s = clean(input);
        return s == null ? null : s.replaceAll("\\s+", " ");
    }

    public static String truncate(String input, int max) {
        if (input == null || input.length() <= max) {
            return input;
        }
        return input.substring(0, max);
    }
}
