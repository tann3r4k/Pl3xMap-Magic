package dev.tanner.pl3xmap.magic;

import java.util.regex.Pattern;

final class MapText {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");

    private MapText() {
    }

    static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return LEGACY_COLOR.matcher(text).replaceAll("");
    }

    static String escape(String text) {
        String plain = plain(text);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static int argb(int rgb, double opacity) {
        double clamped = Math.clamp(opacity, 0.0D, 1.0D);
        int alpha = (int) Math.round(clamped * 255.0D);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    static int rgb(org.bukkit.Color color, int fallback) {
        return color == null ? fallback : color.asRGB() & 0xFFFFFF;
    }
}
