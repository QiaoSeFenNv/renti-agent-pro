package com.renti.agent.modules.user.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.ImportedListingEntity;
import com.renti.agent.infrastructure.persistence.entity.UserFavoriteEntity;
import com.renti.agent.infrastructure.persistence.repository.ImportedListingRepository;
import com.renti.agent.infrastructure.persistence.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户工作台：收藏 + 自有导入房源。行为对齐旧版 user_workspace.py。
 */
@Service
@RequiredArgsConstructor
public class UserWorkspaceService {

    /** 收藏快照白名单（含旧 snake_case 与新 camelCase 变体，快照按传入原样保留白名单键） */
    private static final Set<String> FAVORITE_SNAPSHOT_KEYS = Set.of(
            "id", "title", "price", "location", "match", "image", "position", "detail",
            "rent_price", "rentPrice", "district", "business_area", "businessArea",
            "community", "distance_m", "distanceM", "within_radius", "withinRadius");

    private static final Set<String> IMPORTED_SNAPSHOT_KEYS = Set.of(
            "id", "title", "price", "location", "match", "note", "tone",
            "image", "images", "position", "detail",
            // 表单导入的结构化字段：坐标用于地图工作台标点，其余用于卡片展示与排序
            "longitude", "latitude", "rentPrice", "rent_price", "layout",
            "areaSqm", "area_sqm", "rentType", "rent_type",
            "district", "businessArea", "business_area", "community");

    private static final String DEFAULT_CITY = "上海";

    private final UserFavoriteRepository userFavoriteRepository;
    private final ImportedListingRepository importedListingRepository;

    // ---------------------------------------------------------------- favorites

    @Transactional(readOnly = true)
    public Map<String, Object> listFavoritesPayload(Long userId) {
        var rows = userFavoriteRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::favoriteToMap)
                .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("favorites", rows);
        body.put("total", rows.size());
        return body;
    }

    @Transactional
    public Map<String, Object> saveFavoritePayload(Long userId, Map<String, Object> payload) {
        var request = payload == null ? Map.<String, Object>of() : payload;
        var snapshot = snapshotFrom(request);
        if (snapshot == null) {
            return invalidListing("缺少可收藏的房源信息。");
        }
        var listingId = listingIdFrom(snapshot, request);
        if (listingId.isEmpty()) {
            return invalidListing("缺少房源 ID。");
        }

        var favorite = userFavoriteRepository.findByUserIdAndListingId(userId, listingId)
                .orElseGet(() -> {
                    var created = new UserFavoriteEntity();
                    created.setUserId(userId);
                    created.setListingId(listingId);
                    return created;
                });
        favorite.setListingSnapshot(filterKeys(snapshot, FAVORITE_SNAPSHOT_KEYS));
        favorite.setUpdatedAt(OffsetDateTime.now());
        userFavoriteRepository.save(favorite);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("favorite", favoriteToMap(favorite));
        body.put("summary", "已收藏该房源。");
        return body;
    }

    @Transactional
    public Map<String, Object> deleteFavoritePayload(Long userId, String listingId) {
        var removed = userFavoriteRepository.deleteByUserIdAndListingId(userId, listingId) > 0;
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("removed", removed);
        body.put("summary", removed ? "已取消收藏。" : "该房源不在收藏列表中。");
        return body;
    }

    // ---------------------------------------------------------- imported listings

    @Transactional(readOnly = true)
    public Map<String, Object> listImportedPayload(Long userId, String city) {
        var selectedCity = cleanCity(city);
        var rows = importedListingRepository.findByUserIdAndCityOrderByUpdatedAtDesc(userId, selectedCity).stream()
                .map(this::importedToMap)
                .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("city", selectedCity);
        body.put("listings", rows);
        body.put("total", rows.size());
        return body;
    }

    @Transactional
    public Map<String, Object> saveImportedPayload(Long userId, Map<String, Object> payload) {
        var request = payload == null ? Map.<String, Object>of() : payload;
        var selectedCity = cleanCity(request.get("city") == null ? null : String.valueOf(request.get("city")));
        var snapshot = snapshotFrom(request);
        if (snapshot == null) {
            return invalidListing("缺少可保存的自有房源信息。");
        }
        var listingId = truncate(listingIdFrom(snapshot, request), 96);
        if (listingId.isEmpty()) {
            return invalidListing("缺少自有房源 ID。");
        }

        var listing = importedListingRepository.findByUserIdAndCityAndListingId(userId, selectedCity, listingId)
                .orElseGet(() -> {
                    var created = new ImportedListingEntity();
                    created.setUserId(userId);
                    created.setCity(selectedCity);
                    created.setListingId(listingId);
                    return created;
                });
        listing.setPayload(filterKeys(snapshot, IMPORTED_SNAPSHOT_KEYS));
        listing.setUpdatedAt(OffsetDateTime.now());
        importedListingRepository.save(listing);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("city", selectedCity);
        body.put("listing", importedToMap(listing));
        body.put("summary", "自有房源已保存到当前城市工作台。");
        return body;
    }

    @Transactional
    public Map<String, Object> clearImportedPayload(Long userId, String city) {
        var selectedCity = cleanCity(city);
        var removed = importedListingRepository.deleteByUserIdAndCity(userId, selectedCity);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("city", selectedCity);
        body.put("removed", removed);
        body.put("summary", "已清空 " + selectedCity + " 的 " + removed + " 条自有房源。");
        return body;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotFrom(Map<String, Object> payload) {
        if (payload.get("listing") instanceof Map<?, ?> listing) {
            return (Map<String, Object>) listing;
        }
        if (payload.get("snapshot") instanceof Map<?, ?> snapshot) {
            return (Map<String, Object>) snapshot;
        }
        return null;
    }

    private String listingIdFrom(Map<String, Object> snapshot, Map<String, Object> payload) {
        var fromSnapshot = snapshot.get("id");
        if (fromSnapshot != null && !String.valueOf(fromSnapshot).isBlank()) {
            return String.valueOf(fromSnapshot).strip();
        }
        var fromPayload = payload.get("listingId");
        return fromPayload == null ? "" : String.valueOf(fromPayload).strip();
    }

    private Map<String, Object> filterKeys(Map<String, Object> snapshot, Set<String> allowed) {
        var filtered = new LinkedHashMap<String, Object>();
        snapshot.forEach((key, value) -> {
            if (allowed.contains(key)) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    private Map<String, Object> favoriteToMap(UserFavoriteEntity entity) {
        var row = new LinkedHashMap<String, Object>();
        row.put("listingId", entity.getListingId());
        row.put("listing", entity.getListingSnapshot());
        row.put("createdAt", entity.getCreatedAt());
        row.put("updatedAt", entity.getUpdatedAt());
        return row;
    }

    private Map<String, Object> importedToMap(ImportedListingEntity entity) {
        var row = new LinkedHashMap<String, Object>();
        row.put("listingId", entity.getListingId());
        row.put("city", entity.getCity());
        row.put("listing", entity.getPayload());
        row.put("createdAt", entity.getCreatedAt());
        row.put("updatedAt", entity.getUpdatedAt());
        return row;
    }

    private Map<String, Object> invalidListing(String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", "invalid_listing");
        body.put("summary", summary);
        return body;
    }

    /** 对齐旧版 _clean_city：压缩空白、截断 40 字符、空值回退“上海” */
    public static String cleanCity(String value) {
        var raw = value == null ? "" : value.strip();
        var collapsed = String.join(" ", raw.split("\\s+"));
        var cleaned = collapsed.length() > 40 ? collapsed.substring(0, 40) : collapsed;
        return cleaned.isEmpty() ? DEFAULT_CITY : cleaned;
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
