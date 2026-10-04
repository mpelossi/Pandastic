package org.pandastic.relay.brain;

/**
 * Decision → one SMS reply of at most 2 segments (A's SmsSender drops anything beyond 2).
 * The most important sentence comes first, so if text must be cut, only the tail is lost.
 * Length is counted here in pure Java (same rules as SmsMessage.calculateLength), so it is JVM-testable.
 */
public final class SmsFormatter {
    public static final int MAX_SEGMENTS = 2;
    private static final String PREFIX = "Pandastic: ";
    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
        + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENSION = "^{}\\[~]|€\f";  // two septets each

    private SmsFormatter() {}

    public static String format(Decision d) {
        String body;
        if ("CONFIDENT".equals(d.status) && d.adviceSms != null) body = d.title + ". " + d.adviceSms;
        else body = d.message != null ? d.message : Templates.askPerson(d.lang);
        String text = PREFIX + gsmSafe(body);
        if (segments(text) <= MAX_SEGMENTS) return text;
        if ("CONFIDENT".equals(d.status)) {  // keep the name, point to the in-app details
            String shortText = PREFIX + gsmSafe(d.title + ". " + Templates.seeHomePhone(d.lang));
            if (segments(shortText) <= MAX_SEGMENTS) return shortText;
        }
        return cut(text);
    }

    /** The reply text without the "Pandastic: " prefix: the facts the grounded writer may rephrase. */
    public static String facts(Decision d) { return format(d).substring(PREFIX.length()); }

    /** Adds a short line after the reply only if it still fits MAX_SEGMENTS; the reply itself is never cut for it. */
    public static String withTail(String sms, String tail) {
        if (tail == null || tail.isEmpty()) return sms;
        String longer = sms + "\n" + gsmSafe(tail);
        return segments(longer) <= MAX_SEGMENTS ? longer : sms;
    }

    /** Replaces common non-GSM characters so the SMS stays at 160 characters per segment. */
    static String gsmSafe(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '‘': case '’': case 'ʼ': out.append('\''); break;
                case '“': case '”': out.append('"'); break;
                case '–': case '—': case '−': out.append('-'); break;
                case '…': out.append("..."); break;
                case ' ': out.append(' '); break;
                case 'ŋ': out.append("ng'"); break;   // Luganda ŋ
                case 'Ŋ': out.append("Ng'"); break;
                default: out.append(GSM_BASIC.indexOf(c) >= 0 || GSM_EXTENSION.indexOf(c) >= 0 ? c : '?');
            }
        }
        return out.toString();
    }

    /** Number of SMS segments, using GSM-7 if every character fits, else UCS-2. */
    static int segments(String text) {
        int septets = 0;
        boolean gsm = true;
        for (int i = 0; i < text.length() && gsm; i++) {
            char c = text.charAt(i);
            if (GSM_BASIC.indexOf(c) >= 0) septets += 1;
            else if (GSM_EXTENSION.indexOf(c) >= 0) septets += 2;
            else gsm = false;
        }
        if (gsm) return septets <= 160 ? 1 : (septets + 152) / 153;
        int units = text.length();
        return units <= 70 ? 1 : (units + 66) / 67;
    }

    /** Cuts at a word boundary so the text fits MAX_SEGMENTS of GSM-7. */
    private static String cut(String text) {
        int end = Math.min(text.length(), 153 * MAX_SEGMENTS - 3);
        while (end > 0 && segments(text.substring(0, end) + "...") > MAX_SEGMENTS) end--;
        int space = text.lastIndexOf(' ', end);
        if (space > end / 2) end = space;
        return text.substring(0, end) + "...";
    }
}
