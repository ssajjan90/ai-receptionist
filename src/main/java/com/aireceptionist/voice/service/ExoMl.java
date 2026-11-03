package com.aireceptionist.voice.service;

/**
 * Story 6.2/6.3: small builder for Exotel's ExoML call-flow XML. Every piece of caller- or
 * LLM-derived text goes through {@link #escape} — an AI answer containing {@code <} or {@code &}
 * would otherwise produce malformed XML and drop the call. Escaped values only ever land in text
 * nodes (never attributes), so quotes are left as-is — {@code &apos;} would be read aloud
 * literally by some TTS engines.
 */
public final class ExoMl {

    private ExoMl() {
    }

    public static String response(String... verbs) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>");
        for (String verb : verbs) {
            xml.append(verb);
        }
        return xml.append("</Response>").toString();
    }

    /** Exotel built-in TTS. {@code languageCode} is an app language code ("en", "hi", "hi-en"). */
    public static String say(String text, String languageCode) {
        return "<Say voice=\"woman\" language=\"" + exotelLanguage(languageCode) + "\">" + escape(text) + "</Say>";
    }

    public static String play(String audioUrl) {
        return "<Play>" + escape(audioUrl) + "</Play>";
    }

    /**
     * Records the caller's next utterance and asks Exotel to transcribe it to {@code actionUrl}
     * (see {@link VoiceCallbackUrls#transcript()}).
     */
    public static String recordForTranscript(String actionUrl) {
        return "<Record action=\"" + escapeAttribute(actionUrl) + "\" maxLength=\"30\" transcribe=\"true\"/>";
    }

    public static String pause(int seconds) {
        return "<Pause length=\"" + seconds + "\"/>";
    }

    public static String redirect(String url) {
        return "<Redirect>" + escape(url) + "</Redirect>";
    }

    public static String hangup() {
        return "<Hangup/>";
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }

    static String exotelLanguage(String languageCode) {
        return languageCode != null && languageCode.startsWith("hi") ? "hi-in" : "en-in";
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                default -> {
                    // XML 1.0 forbids most control chars; LLM output occasionally contains them.
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
