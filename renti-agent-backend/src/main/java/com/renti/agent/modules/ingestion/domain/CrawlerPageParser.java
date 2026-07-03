package com.renti.agent.modules.ingestion.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公开列表页解析工具：移植旧 listing_crawlers.py 的正则解析
 * （链家/安居客卡片、户型/面积/图片/地址提取、HTML 清洗）。
 */
public final class CrawlerPageParser {

    private static final Pattern LIANJIA_CARD_PATTERN = Pattern.compile(
            "<div\\s+class=\"content__list--item\"[\\s\\S]*?"
                    + "(?=<div\\s+class=\"content__list--item\"|<div\\s+class=\"content__pg\"|$)");
    private static final Pattern ANJUKE_CARD_PATTERN = Pattern.compile(
            "<div\\s+[^>]*class=\"[^\"]*(?:zu-itemmod|zu-item|list-item)[^\"]*\"[\\s\\S]*?"
                    + "(?=<div\\s+[^>]*class=\"[^\"]*(?:zu-itemmod|zu-item|list-item)[^\"]*\""
                    + "|<div\\s+[^>]*class=\"[^\"]*(?:multi-page|pagination|page)[^\"]*\"|$)");
    private static final Pattern ANCHOR_PATTERN = Pattern.compile("<a[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img\\b[^>]*>");
    private static final Pattern RAW_IMAGE_URL_PATTERN = Pattern.compile(
            "https?://[^\"')\\s]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\"')\\s]*)?", Pattern.CASE_INSENSITIVE);
    private static final List<String> SHANGHAI_DISTRICTS = List.of(
            "浦东", "闵行", "徐汇", "静安", "黄浦", "长宁", "普陀", "虹口", "杨浦",
            "宝山", "嘉定", "松江", "青浦", "奉贤", "金山", "崇明");

    private CrawlerPageParser() {
    }

    // ---------- 链家 ----------

    public static List<Map<String, Object>> parseLianjiaPage(String html, String pageUrl) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var card : findAll(LIANJIA_CARD_PATTERN, html)) {
            if (isUnavailableCard(card)) {
                continue;
            }
            var externalId = match(card, "data-house_code=\"([^\"]+)\"");
            var title = cleanHtml(match(card, "content__list--item--title[\\s\\S]*?<a[^>]*>([\\s\\S]*?)</a>"));
            var href = match(card, "content__list--item--title[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"");
            var sourceUrl = href.isEmpty() ? pageUrl : urlJoin(pageUrl, href);
            var location = lianjiaLocationParts(card);
            var price = match(card, "content__list--item-price\">\\s*<em>([\\d.]+)</em>");
            var area = match(card, "([\\d.]+)\\s*㎡");
            var layout = layoutFromCard(card);
            var rentType = title.contains("·") ? title.split("·", 2)[0].trim() : "整租";
            var images = imageUrlsFromCard(card, pageUrl);
            var image = images.isEmpty() ? "" : images.get(0);
            var tags = new ArrayList<String>();
            for (var value : findAllGroups(Pattern.compile(
                    "<i class=\"content__item__tag[^\"]*\">([\\s\\S]*?)</i>"), card)) {
                tags.add(cleanHtml(value));
            }
            if (title.isEmpty() || location[2].isEmpty()) {
                continue;
            }
            rows.add(row(externalId, title, location[0], location[1], location[2], price, layout, area,
                    rentType, tags, sourceUrl, image, images, "lianjia", "lianjia_shanghai"));
        }
        return rows;
    }

    // ---------- 安居客 ----------

    public static List<Map<String, Object>> parseAnjukePage(String html, String pageUrl) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var card : findAll(ANJUKE_CARD_PATTERN, html)) {
            if (isUnavailableCard(card)) {
                continue;
            }
            var sourceUrl = anjukeSourceUrl(card, pageUrl);
            var title = anjukeTitle(card);
            var location = anjukeLocationParts(card, title);
            var price = anjukePrice(card);
            var area = areaFromCard(card);
            var layout = layoutFromCard(card);
            var rentType = title.contains("合租") ? "合租" : "整租";
            var images = imageUrlsFromCard(card, pageUrl);
            var image = images.isEmpty() ? "" : images.get(0);
            var tags = anjukeTags(card);
            var externalId = match(sourceUrl, "(?:fangyuan/|/)(\\d+)(?:\\.html|/)?");
            if (externalId.isEmpty()) {
                externalId = match(card, "data-[^=]*id=\"([^\"]+)\"");
            }
            if (title.isEmpty() || location[2].isEmpty()) {
                continue;
            }
            rows.add(row(externalId, title, location[0], location[1], location[2], price, layout, area,
                    rentType, tags, sourceUrl.isEmpty() ? pageUrl : sourceUrl, image, images,
                    "anjuke", "anjuke_shanghai"));
        }
        return rows;
    }

    // ---------- 通用工具 ----------

    public static String cleanHtml(String value) {
        var text = (value == null ? "" : value).replaceAll("<[^>]+>", " ");
        text = unescapeHtml(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    public static String match(String value, String pattern) {
        var matcher = Pattern.compile(pattern).matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    public static String urlJoin(String baseUrl, String href) {
        try {
            return URI.create(baseUrl).resolve(unescapeHtml(href).trim()).toString();
        } catch (IllegalArgumentException exception) {
            return href;
        }
    }

    public static String unescapeHtml(String value) {
        return (value == null ? "" : value)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    static boolean isUnavailableCard(String card) {
        var text = cleanHtml(card);
        return text.contains("已出租") || text.contains("已租") || text.contains("已下架") || text.contains("已成交");
    }

    static String layoutFromCard(String card) {
        var des = match(card, "content__list--item--des\">([\\s\\S]*?)</p>");
        var plain = cleanHtml(des.isEmpty() ? card : des);
        var full = Pattern.compile("(\\d+)\\s*室\\s*(\\d+)\\s*厅\\s*(\\d+)\\s*卫").matcher(plain);
        if (full.find()) {
            return full.group(1) + "室" + full.group(2) + "厅" + full.group(3) + "卫";
        }
        var partial = Pattern.compile("(\\d+)\\s*室\\s*(\\d+)\\s*厅").matcher(plain);
        if (partial.find()) {
            return partial.group(1) + "室" + partial.group(2) + "厅";
        }
        var rooms = Pattern.compile("(\\d+)\\s*室").matcher(plain);
        if (rooms.find()) {
            return rooms.group(1) + "室";
        }
        return "";
    }

    static String areaFromCard(String card) {
        var area = match(card, "([\\d.]+)\\s*(?:㎡|平米|m²)");
        return area.isEmpty() ? match(cleanHtml(card), "([\\d.]+)\\s*(?:㎡|平米|m²)") : area;
    }

    static List<String> imageUrlsFromCard(String card, String pageUrl) {
        var urls = new ArrayList<String>();
        for (var tag : findAll(IMG_TAG_PATTERN, card)) {
            for (var attr : List.of("data-src", "data-original", "lazy_src", "src", "data-lazy")) {
                var value = match(tag, attr + "=\"([^\"]+)\"");
                if (!value.isEmpty()) {
                    urls.add(urlJoin(pageUrl, value));
                }
            }
        }
        var rawMatcher = RAW_IMAGE_URL_PATTERN.matcher(card);
        while (rawMatcher.find()) {
            urls.add(unescapeHtml(rawMatcher.group()));
        }
        var result = new ArrayList<String>();
        for (var url : urls) {
            var clean = url.trim();
            if (!clean.isEmpty() && !result.contains(clean)) {
                result.add(clean);
            }
            if (result.size() >= 12) {
                break;
            }
        }
        return result;
    }

    private static String[] lianjiaLocationParts(String card) {
        var des = match(card, "content__list--item--des\">([\\s\\S]*?)</p>");
        var anchors = anchorTexts(des);
        return new String[]{
                anchors.size() > 0 ? anchors.get(0) : "",
                anchors.size() > 1 ? anchors.get(1) : "",
                anchors.size() > 2 ? anchors.get(2) : ""};
    }

    private static String anjukeSourceUrl(String card, String pageUrl) {
        var href = match(card, "\\blink=\"([^\"]+)\"");
        if (href.isEmpty()) {
            href = match(card, "<a[^>]+href=\"([^\"]+)\"[^>]*class=\"[^\"]*(?:img|zu-info|house-title|title)[^\"]*\"");
        }
        if (href.isEmpty()) {
            href = match(card, "<h3[\\s\\S]*?<a[^>]+href=\"([^\"]+)\"");
        }
        if (href.isEmpty()) {
            href = match(card, "<a[^>]+href=\"([^\"]*(?:/fangyuan/|zu\\.anjuke\\.com)[^\"]*)\"");
        }
        return href.isEmpty() ? "" : urlJoin(pageUrl, href);
    }

    private static String anjukeTitle(String card) {
        var title = match(card, "<h3[^>]*>[\\s\\S]*?<a[^>]*>([\\s\\S]*?)</a>");
        if (title.isEmpty()) {
            title = match(card, "<a[^>]+class=\"[^\"]*(?:house-title|title)[^\"]*\"[^>]*>([\\s\\S]*?)</a>");
        }
        if (title.isEmpty()) {
            title = match(card, "title=\"([^\"]+)\"");
        }
        return cleanHtml(title);
    }

    private static String anjukePrice(String card) {
        var price = match(card, "<(?:strong|b)[^>]+class=\"[^\"]*price[^\"]*\"[^>]*>\\s*([\\d.]+)\\s*</(?:strong|b)>");
        if (price.isEmpty()) {
            price = match(card, "<(?:strong|b)[^>]*>\\s*([\\d.]+)\\s*</(?:strong|b)>[\\s\\S]{0,80}?(?:元/月|元\\s*/\\s*月|元)");
        }
        if (price.isEmpty()) {
            price = match(cleanHtml(card), "([1-9]\\d{2,5})\\s*(?:元/月|元\\s*/\\s*月|元/月起)");
        }
        return price;
    }

    private static String[] anjukeLocationParts(String card, String title) {
        var addressHtml = match(card, "<address[^>]*>([\\s\\S]*?)</address>");
        if (addressHtml.isEmpty()) {
            addressHtml = match(card,
                    "<p[^>]+class=\"[^\"]*(?:details-item|comm-address|address)[^\"]*\"[^>]*>([\\s\\S]*?)</p>");
        }
        var anchors = anchorTexts(addressHtml).stream()
                .filter(item -> !item.isEmpty() && !item.equals(title) && !item.equals("查看地图"))
                .toList();
        var plain = cleanHtml(addressHtml.isEmpty() ? card : addressHtml);
        if (anchors.size() >= 3) {
            return new String[]{anchors.get(0), anchors.get(1), anchors.get(2)};
        }
        if (anchors.size() == 2) {
            return new String[]{anchors.get(0), "", anchors.get(1)};
        }
        if (anchors.size() == 1) {
            var community = anchors.get(0);
            var suffix = plain.replace(community, " ");
            var parts = addressParts(suffix);
            var district = !parts.isEmpty() && isShanghaiDistrict(parts.get(0))
                    ? parts.get(0) : districtFromText(plain);
            var businessArea = parts.size() >= 2 && !parts.get(1).equals(district) ? parts.get(1) : "";
            return new String[]{district, businessArea, community};
        }
        var parts = addressParts(plain);
        var district = districtFromText(plain);
        var filtered = parts.stream()
                .filter(item -> !item.equals(title) && !item.equals("整租") && !item.equals("合租"))
                .filter(item -> !Pattern.compile("\\d+(?:\\.\\d+)?(?:㎡|平米|元|室|厅)").matcher(item).find())
                .toList();
        var community = filtered.isEmpty() ? "" : filtered.get(filtered.size() - 1);
        var businessArea = filtered.size() >= 2 && !filtered.get(filtered.size() - 2).equals(district)
                ? filtered.get(filtered.size() - 2) : "";
        return new String[]{district, businessArea, community};
    }

    private static List<String> addressParts(String value) {
        var result = new ArrayList<String>();
        for (var item : value.split("[-－—>|/、\\s]+")) {
            var trimmed = item.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("地址") && !trimmed.equals("位置")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> anjukeTags(String card) {
        var result = new ArrayList<String>();
        for (var value : findAllGroups(Pattern.compile(
                "<(?:span|i)[^>]+class=\"[^\"]*(?:tag|item-tags|cls-|broker-tag)[^\"]*\"[^>]*>([\\s\\S]*?)</(?:span|i)>"),
                card)) {
            var cleaned = cleanHtml(value);
            if (!cleaned.isEmpty() && !result.contains(cleaned) && cleaned.length() <= 12) {
                result.add(cleaned);
            }
            if (result.size() >= 10) {
                break;
            }
        }
        return result;
    }

    private static String districtFromText(String value) {
        for (var district : SHANGHAI_DISTRICTS) {
            if (value.contains(district)) {
                return district;
            }
        }
        return "";
    }

    private static boolean isShanghaiDistrict(String value) {
        return !districtFromText(value).isEmpty();
    }

    private static List<String> anchorTexts(String html) {
        var result = new ArrayList<String>();
        for (var value : findAllGroups(ANCHOR_PATTERN, html == null ? "" : html)) {
            result.add(cleanHtml(value));
        }
        return result;
    }

    private static List<String> findAll(Pattern pattern, String value) {
        var result = new ArrayList<String>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static List<String> findAllGroups(Pattern pattern, String value) {
        var result = new ArrayList<String>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static Map<String, Object> row(String externalId, String title, String district, String businessArea,
                                           String community, String price, String layout, String area,
                                           String rentType, List<String> tags, String url, String image,
                                           List<String> images, String provider, String source) {
        var rowMap = new LinkedHashMap<String, Object>();
        rowMap.put("external_id", externalId);
        rowMap.put("title", title);
        rowMap.put("district", district);
        rowMap.put("business_area", businessArea);
        rowMap.put("community", community);
        rowMap.put("price", price);
        rowMap.put("layout", layout);
        rowMap.put("area", area);
        rowMap.put("rent_type", rentType);
        rowMap.put("nearest_metro", "待补充");
        rowMap.put("metro_distance_m", 999);
        rowMap.put("tags", tags);
        rowMap.put("url", url);
        rowMap.put("image", image);
        rowMap.put("images", images);
        rowMap.put("provider", provider);
        rowMap.put("source", source);
        return rowMap;
    }
}
