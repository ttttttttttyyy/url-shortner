package com.url.shortly.service;

import java.util.Locale;

/**
 * Best-effort, dependency-free classification of a user-agent string.
 *
 * <p>This is deliberately coarse: precise parsing is a provider decision, and
 * unknown strings are reported as {@link #UNKNOWN} rather than guessed.
 */
public final class UserAgentClassifier {

    public static final String UNKNOWN = "Unknown";

    private UserAgentClassifier() {
    }

    public record ClientInfo(String device, String operatingSystem, String browser) {
    }

    public static ClientInfo classify(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (ua.isBlank() || !looksLikeBrowser(ua)) {
            return new ClientInfo(UNKNOWN, UNKNOWN, UNKNOWN);
        }
        return new ClientInfo(device(ua), operatingSystem(ua), browser(ua));
    }

    /** CLI clients such as curl are not browsers; report no classification. */
    private static boolean looksLikeBrowser(String ua) {
        return ua.contains("mozilla") || ua.contains("applewebkit") || ua.contains("chrome")
                || ua.contains("safari") || ua.contains("firefox") || ua.contains("edg/")
                || ua.contains("opr/") || ua.contains("opera");
    }

    private static String device(String ua) {
        if (ua.contains("ipad") || ua.contains("tablet")) {
            return "Tablet";
        }
        if (ua.contains("iphone") || ua.contains("mobil") || ua.contains("android")) {
            return "Mobile";
        }
        return "Desktop";
    }

    private static String operatingSystem(String ua) {
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "iOS";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("cr.os")) {
            return "Chrome OS";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return UNKNOWN;
    }

    private static String browser(String ua) {
        // Edge and Opera UAs also contain Chrome/Safari tokens.
        if (ua.contains("edg/") || ua.contains("edge/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return UNKNOWN;
    }
}
