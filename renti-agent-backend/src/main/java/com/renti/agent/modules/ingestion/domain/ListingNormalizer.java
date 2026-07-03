package com.renti.agent.modules.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 房源规范化：移植旧 listing_ingestion.py 的 normalize_listing_candidate /
 * _quality_report / _listing_id / _dedupe_key 及其字段清洗工具。
 *
 * <p>规范化后的 payload 统一使用 camelCase 字段：
 * id/city/district/businessArea/community/title/longitude/latitude/rentPrice/layout/
 * areaSqm/rentType/nearestMetro/metroDistanceM/commuteMinutes/tags/riskTags/source/
 * updatedAt/sourceUrl/provider/externalId/image/images/raw。</p>
 */
public final class ListingNormalizer {

    public static final String DEFAULT_SOURCE_NAME = "manual_import";
    public static final String DEFAULT_PROVIDER = "manual";

    /** 发布必填字段（camelCase） */
    public static final List<String> REQUIRED_FIELDS = List.of(
            "city", "district", "businessArea", "community", "title",
            "longitude", "latitude", "rentPrice", "layout", "areaSqm", "rentType");

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern LIST_SPLIT_PATTERN = Pattern.compile("[,，/、;；|]");
    private static final Pattern IMAGE_SPLIT_PATTERN = Pattern.compile("[\\n\\r,，;；|]+");

    private ListingNormalizer() {
    }

    /** 规范化候选结果 */
    public record Normalized(Map<String, Object> listing, Map<String, Object> quality, String dedupeKey) {
    }

    @SuppressWarnings("unchecked")
    public static Normalized normalize(Map<String, Object> raw, Map<String, String> context) {
        var ctx = context == null ? Map.<String, String>of() : context;
        var provider = cleanText(firstNonNull(first(raw, "provider"), ctx.get("provider"), DEFAULT_PROVIDER), 80);
        var sourceName = cleanText(firstNonNull(first(raw, "source", "source_name"), ctx.get("sourceName"), DEFAULT_SOURCE_NAME), 80);
        var city = cleanText(firstNonNull(first(raw, "city"), ctx.get("city"), "上海"), 40);
        var externalId = cleanText(first(raw, "external_id", "externalId", "id", "listing_id"), 120);
        var sourceUrl = cleanText(first(raw, "source_url", "sourceUrl", "url", "link"), 500);

        var district = cleanText(first(raw, "district", "行政区"), 40);
        var businessArea = cleanText(first(raw, "business_area", "businessArea", "商圈", "板块"), 80);
        var community = cleanText(first(raw, "community", "小区", "community_name"), 120);
        var title = cleanText(firstNonNull(first(raw, "title", "标题", "name"), community), 160);
        int rentPrice = intFrom(first(raw, "rent_price", "rentPrice", "price", "租金"));
        int areaSqm = intFrom(first(raw, "area_sqm", "areaSqm", "area", "面积"));
        Double longitude = floatFrom(first(raw, "longitude", "lng", "lon", "经度"));
        Double latitude = floatFrom(first(raw, "latitude", "lat", "纬度"));
        var layout = cleanText(first(raw, "layout", "户型", "room"), 40);
        var rentType = cleanText(firstNonNull(first(raw, "rent_type", "rentType", "出租方式"), "整租"), 40);
        var nearestMetro = cleanText(firstNonNull(first(raw, "nearest_metro", "nearestMetro", "metro", "地铁"), "待补充"), 80);
        int metroDistanceM = intFrom(first(raw, "metro_distance_m", "metroDistanceM", "metro_distance", "地铁距离"));
        int commuteMinutes = intFrom(first(raw, "commute_minutes", "commuteMinutes", "commute", "通勤"));
        var tags = listFrom(first(raw, "tags", "标签"));
        var riskTags = listFrom(first(raw, "risk_tags", "riskTags", "风险"));
        var updatedAt = dateText(first(raw, "updated_at", "updatedAt", "published_at", "发布时间"));
        var image = cleanText(first(raw, "image", "cover", "cover_url", "图片"), 500);
        var images = imageListFromSource(raw);
        if (!image.isEmpty() && !images.contains(image)) {
            images.add(0, image);
        }
        image = images.isEmpty() ? image : images.get(0);

        var listingId = listingId(provider, externalId, sourceUrl, community, title, rentPrice, layout, areaSqm);
        var listing = new LinkedHashMap<String, Object>();
        listing.put("id", listingId);
        listing.put("city", city);
        listing.put("district", district);
        listing.put("businessArea", businessArea);
        listing.put("community", community);
        listing.put("title", title);
        listing.put("longitude", longitude);
        listing.put("latitude", latitude);
        listing.put("rentPrice", rentPrice);
        listing.put("layout", layout);
        listing.put("areaSqm", areaSqm);
        listing.put("rentType", rentType);
        listing.put("nearestMetro", nearestMetro);
        listing.put("metroDistanceM", metroDistanceM == 0 ? 999 : metroDistanceM);
        listing.put("commuteMinutes", commuteMinutes == 0 ? 45 : commuteMinutes);
        listing.put("tags", tags);
        listing.put("riskTags", riskTags);
        listing.put("source", sourceName);
        listing.put("updatedAt", updatedAt);
        listing.put("sourceUrl", sourceUrl);
        listing.put("provider", provider);
        listing.put("externalId", externalId);
        listing.put("image", image);
        listing.put("images", images);
        listing.put("raw", raw);

        return new Normalized(
                listing,
                qualityReport(listing),
                dedupeKey(provider, externalId, sourceUrl, community, title, rentPrice, layout, areaSqm));
    }

    /** 质量报告：missingFields/warnings/score/publishable（对齐旧 _quality_report） */
    public static Map<String, Object> qualityReport(Map<String, Object> listing) {
        var missing = REQUIRED_FIELDS.stream().filter(field -> isEmpty(listing.get(field))).toList();
        var warnings = new ArrayList<String>();
        int rentPrice = intFrom(listing.get("rentPrice"));
        int areaSqm = intFrom(listing.get("areaSqm"));
        if (rentPrice > 0 && rentPrice < 1000) {
            warnings.add("租金疑似过低");
        }
        if (rentPrice > 30000) {
            warnings.add("租金疑似过高");
        }
        if (areaSqm > 0 && areaSqm < 8) {
            warnings.add("面积疑似过小");
        }
        if (cleanText(listing.get("sourceUrl"), 500).isEmpty()) {
            warnings.add("缺少来源链接");
        }
        var quality = new LinkedHashMap<String, Object>();
        quality.put("publishable", missing.isEmpty());
        quality.put("missingFields", missing);
        quality.put("warnings", warnings);
        quality.put("score", Math.max(0, 100 - missing.size() * 14 - warnings.size() * 5));
        return quality;
    }

    /** 房源业务 ID：sh-{provider-slug}-{sha1[:12]}（对齐旧 _listing_id） */
    public static String listingId(String provider, String externalId, String sourceUrl,
                                   String community, String title, int rentPrice, String layout, int areaSqm) {
        var raw = !externalId.isEmpty() ? externalId
                : !sourceUrl.isEmpty() ? sourceUrl
                : community + ":" + title + ":" + rentPrice + ":" + layout + ":" + areaSqm;
        var slug = slug(provider.isEmpty() ? DEFAULT_PROVIDER : provider);
        return "sh-" + slug + "-" + sha1Hex(raw).substring(0, 12);
    }

    /** 去重键：external_id > source_url > 组合字段，sha256（对齐旧 _dedupe_key） */
    public static String dedupeKey(String provider, String externalId, String sourceUrl,
                                   String community, String title, int rentPrice, String layout, int areaSqm) {
        String raw;
        if (!externalId.isEmpty()) {
            raw = provider + "|external_id|" + externalId;
        } else if (!sourceUrl.isEmpty()) {
            raw = provider + "|source_url|" + sourceUrl;
        } else {
            raw = String.join("|", provider, community, title, String.valueOf(rentPrice), layout, String.valueOf(areaSqm));
        }
        return sha256Hex(raw);
    }

    /** 空值判定：null / 空串 / 数字 0（对齐旧 _is_empty） */
    public static boolean isEmpty(Object value) {
        if (value == null || "".equals(value)) {
            return true;
        }
        return value instanceof Number number && number.doubleValue() == 0.0;
    }

    /** 取第一个非空键值（对齐旧 _first） */
    public static Object first(Map<String, Object> source, String... keys) {
        for (var key : keys) {
            if (!source.containsKey(key)) {
                continue;
            }
            var value = source.get(key);
            if (value != null && !"".equals(value)) {
                return value;
            }
        }
        return null;
    }

    /** 文本清洗（对齐旧 _clean_text：falsy 归空串、strip、截断） */
    public static String cleanText(Object value, int limit) {
        if (value == null || Boolean.FALSE.equals(value)
                || (value instanceof Number number && number.doubleValue() == 0.0)) {
            return "";
        }
        var text = stringOf(value).trim();
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    /** 整数提取：去千分位后取首个数字（对齐旧 _int_from） */
    public static int intFrom(Object value) {
        if (value == null || "".equals(value)) {
            return 0;
        }
        var text = stringOf(value).replace(",", "");
        var matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        return (int) Double.parseDouble(matcher.group());
    }

    /** 浮点解析（对齐旧 _float_from） */
    public static Double floatFrom(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 标签列表解析（对齐旧 _list_from） */
    public static List<String> listFrom(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                var cleaned = cleanText(item, 40);
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
        } else if (value instanceof String text) {
            for (var part : LIST_SPLIT_PATTERN.split(text)) {
                var cleaned = cleanText(part, 40);
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
        }
        return result;
    }

    /** 图片列表提取（对齐旧 _image_list_from_source，含中英文别名键） */
    public static List<String> imageListFromSource(Map<String, Object> source) {
        var values = new ArrayList<String>();
        for (var key : List.of("images", "image_urls", "imageUrls", "photos", "gallery", "图片列表")) {
            if (source.containsKey(key)) {
                values.addAll(imageValuesFrom(source.get(key)));
            }
        }
        for (var key : List.of("image", "cover", "cover_url", "coverUrl", "图片")) {
            if (source.containsKey(key)) {
                values.addAll(imageValuesFrom(source.get(key)));
            }
        }
        return dedupeTextValues(values, 24, 500);
    }

    public static boolean hasImageListField(Map<String, Object> source) {
        return List.of("images", "image_urls", "imageUrls", "photos", "gallery", "图片列表").stream()
                .anyMatch(source::containsKey);
    }

    @SuppressWarnings("unchecked")
    private static List<String> imageValuesFrom(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                result.addAll(imageValuesFrom(item));
            }
        } else if (value instanceof Map<?, ?> map) {
            result.addAll(imageValuesFrom(first((Map<String, Object>) map, "url", "src", "href", "image", "cover")));
        } else if (value instanceof String text) {
            for (var part : IMAGE_SPLIT_PATTERN.split(text)) {
                var cleaned = cleanText(part, 500);
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
        }
        return result;
    }

    /** 去重保序（对齐旧 _dedupe_text_values） */
    public static List<String> dedupeTextValues(List<String> values, Integer limit, int textLimit) {
        var result = new ArrayList<String>();
        for (var value : values) {
            var cleaned = cleanText(value, textLimit);
            if (!cleaned.isEmpty() && !result.contains(cleaned)) {
                result.add(cleaned);
            }
            if (limit != null && result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public static String dateText(Object value) {
        var cleaned = cleanText(value, 40);
        return cleaned.isEmpty() ? today() : cleaned;
    }

    public static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    /** 简易 slug（对齐旧 _slug：非字母数字转 -，压缩、去首尾、限 32） */
    public static String slug(String value) {
        var raw = value.toLowerCase().trim();
        var builder = new StringBuilder();
        for (var ch : raw.toCharArray()) {
            builder.append(Character.isLetterOrDigit(ch) ? ch : '-');
        }
        var result = builder.toString();
        while (result.contains("--")) {
            result = result.replace("--", "-");
        }
        result = result.replaceAll("^-+|-+$", "");
        if (result.length() > 32) {
            result = result.substring(0, 32);
        }
        return result.isEmpty() ? "source" : result;
    }

    public static String sha1Hex(String value) {
        return digestHex("SHA-1", value);
    }

    public static String sha256Hex(String value) {
        return digestHex("SHA-256", value);
    }

    private static String digestHex(String algorithm, String value) {
        try {
            var digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("摘要算法不可用: " + algorithm, exception);
        }
    }

    /** Python 风格 str()：浮点整数值输出如 121.0，避免与旧行为偏差过大即可 */
    private static String stringOf(Object value) {
        if (value instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return d.doubleValue() == d.longValue() ? String.valueOf(d.longValue()) + ".0" : String.valueOf(d);
        }
        return String.valueOf(value);
    }

    private static Object firstNonNull(Object... values) {
        for (var value : values) {
            if (value != null && !"".equals(value)) {
                return value;
            }
        }
        return null;
    }
}
