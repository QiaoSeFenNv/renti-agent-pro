package com.renti.agent.modules.search.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * search 模块通用工具：文本清洗、数值解析、Haversine 距离、图片代理地址等。
 * 分别对齐旧 map_intent.py / listing_ingestion.py / image_proxy.py 中的同名 helper。
 */
public final class SearchSupport {

    public static final String DEFAULT_CITY = "上海";
    public static final String IMAGE_PROXY_PATH = "/api/assets/listing-image";

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s,，/、;；|]+");
    private static final Pattern DISTANCE_NOISE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:米|m|M|公里|千米|km|KM)");
    private static final Pattern QUERY_NOISE =
            Pattern.compile("(我想|想要|帮我|查找|查询|寻找|找|附近|周边|房源|信息|租房|上海市|上海|的)");

    private SearchSupport() {
    }

    public static String cleanText(Object value, int limit) {
        var text = String.join(" ", str(value).strip().split("\\s+"));
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    public static Integer optionalInt(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static Double optionalDouble(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static int intOr(Object value, int fallback) {
        var parsed = optionalInt(value);
        return parsed == null ? fallback : parsed;
    }

    public static double doubleOr(Object value, double fallback) {
        var parsed = optionalDouble(value);
        return parsed == null ? fallback : parsed;
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    public static boolean isMap(Object value) {
        return value instanceof Map<?, ?>;
    }

    /** 对齐旧 _string_list_from：list 或 逗号/顿号 分隔字符串 → 清洗后的字符串列表 */
    public static List<String> stringList(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                var cleaned = str(item).strip();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned);
                }
            }
            return result;
        }
        if (value instanceof String string) {
            for (var item : SPLIT_PATTERN.split(string)) {
                var cleaned = item.strip();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned);
                }
            }
        }
        return result;
    }

    /** Haversine 直线距离（米），对齐旧 _distance_meters */
    public static double distanceMeters(double fromLng, double fromLat, double toLng, double toLat) {
        double radius = 6371000;
        double phi1 = Math.toRadians(fromLat);
        double phi2 = Math.toRadians(toLat);
        double deltaPhi = Math.toRadians(toLat - fromLat);
        double deltaLambda = Math.toRadians(toLng - fromLng);
        double a = Math.pow(Math.sin(deltaPhi / 2), 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.pow(Math.sin(deltaLambda / 2), 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 对齐旧 _normalize_query_keyword：压缩空白并去掉“上海(市)”前缀，截断 80 字 */
    public static String normalizeQueryKeyword(String text) {
        var cleaned = String.join(" ", str(text).strip().split("\\s+"));
        cleaned = cleaned.replaceFirst("^(?:上海市?|上海)\\s*", "");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    /** 对齐旧 listing_ingestion._query_tokens：去噪声词后切分为 ≥2 字 token */
    public static List<String> queryTokens(String value) {
        var normalized = str(value).strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        normalized = DISTANCE_NOISE.matcher(normalized).replaceAll(" ");
        normalized = QUERY_NOISE.matcher(normalized).replaceAll(" ");
        var tokens = new ArrayList<String>();
        for (var part : SPLIT_PATTERN.split(normalized)) {
            var token = cleanText(part, 60);
            if (token.length() < 2 || tokens.contains(token)) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() >= 8) {
                break;
            }
        }
        return tokens;
    }

    /** 对齐旧 image_proxy.listing_image_display_url */
    public static String imageDisplayUrl(Object url) {
        var cleanUrl = str(url).strip();
        if (cleanUrl.isEmpty()) {
            return "";
        }
        if (cleanUrl.startsWith(IMAGE_PROXY_PATH)) {
            return cleanUrl;
        }
        if (!isRemoteHttpUrl(cleanUrl)) {
            return "";
        }
        return IMAGE_PROXY_PATH + "?url=" + URLEncoder.encode(cleanUrl, StandardCharsets.UTF_8);
    }

    /** 对齐旧 image_proxy.listing_images_display_urls */
    public static List<String> imageDisplayUrls(Object urls) {
        var result = new ArrayList<String>();
        if (urls instanceof List<?> list) {
            for (var url : list) {
                var display = imageDisplayUrl(url);
                if (!display.isEmpty() && !result.contains(display)) {
                    result.add(display);
                }
            }
        }
        return result;
    }

    private static boolean isRemoteHttpUrl(String url) {
        java.net.URI parsed;
        try {
            parsed = java.net.URI.create(url);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        var scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        var host = parsed.getHost() == null ? "" : parsed.getHost().strip().toLowerCase();
        if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty()) {
            return false;
        }
        return !isPrivateHost(host);
    }

    private static boolean isPrivateHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return true;
        }
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        var parts = host.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 10 || first == 127 || first == 0
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 169 && second == 254)
                || first >= 224;
    }
}
