package com.ex5.music;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java helpers for Apple Music links and audio file names (unit-tested). */
final class Links {
    static final String APPLE_MUSIC_PACKAGE = "com.apple.android.music";

    private static final Pattern URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*(?:music|itunes)\\.apple\\.com/\\S*",
            Pattern.CASE_INSENSITIVE);

    private Links() {}

    /**
     * Finds an Apple Music link inside free text (for example a "share" message) and
     * returns it as a clean https URL, or null when the text holds no Apple Music link.
     */
    static String findAppleMusicLink(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text.trim());
        while (m.find()) {
            String candidate = m.group();
            while (!candidate.isEmpty() && ".,;:!?)]}>\"'".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://")) {
                candidate = "https://" + candidate.substring("http://".length());
            } else if (!lower.startsWith("https://")) {
                candidate = "https://" + candidate;
            }
            if (isAppleMusicUrl(candidate)) return candidate;
        }
        return null;
    }

    static boolean isAppleMusicUrl(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("music.apple.com") || host.endsWith(".music.apple.com")
                    || host.equals("itunes.apple.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static final String[] AUDIO_EXT = {
            "flac", "wav", "aif", "aiff", "alac", "m4a", "mp4", "caf",
            "mp3", "aac", "ogg", "oga", "opus", "wma", "ape", "dsf", "dff"
    };
    private static final String[] LOSSLESS_EXT = {"flac", "wav", "aif", "aiff", "alac", "ape", "dsf", "dff"};

    static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isAudioName(String name) {
        return contains(AUDIO_EXT, extension(name));
    }

    /** True when the extension alone guarantees a lossless file (.m4a may be AAC or ALAC). */
    static boolean isLosslessName(String name) {
        return contains(LOSSLESS_EXT, extension(name));
    }

    /** True for decoder MIME types that carry lossless audio. */
    static boolean isLosslessMime(String mime) {
        if (mime == null) return false;
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "audio/flac":
            case "audio/x-flac":
            case "audio/alac":
            case "audio/raw":
            case "audio/wav":
            case "audio/x-wav":
            case "audio/aiff":
            case "audio/x-aiff":
                return true;
            default:
                return false;
        }
    }

    static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static boolean contains(String[] values, String v) {
        for (String s : values) if (s.equals(v)) return true;
        return false;
    }
}
