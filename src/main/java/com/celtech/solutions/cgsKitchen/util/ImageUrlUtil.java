package com.celtech.solutions.cgsKitchen.util;

public final class ImageUrlUtil {
    private ImageUrlUtil() {}

    public static String resolve(String path, String baseUrl) {
        if (path == null || path.isBlank()) return "/images/placeholder.jpg";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;

        if (baseUrl == null || baseUrl.isBlank()) return path;

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // Convert "/images/<folder>/<file>" -> "<base>/<folder>/<file>"
        if (path.startsWith("/images/")) return base + "/" + path.substring("/images/".length());
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
    }
}