package com.renti.agent.modules.city.application;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.CityEntity;
import com.renti.agent.infrastructure.persistence.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 城市库种子：首次启动（cities 表为空）时从 classpath:seed/cities.json 导入
 * 旧库 renti_cities 的 prefecture_city 行（306 条，含坐标与排序）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CitySeeder {

    private final CityRepository cityRepository;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    @Transactional
    public void seed() {
        if (cityRepository.count() > 0) {
            return;
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    new ClassPathResource("seed/cities.json").getInputStream(),
                    new TypeReference<>() {
                    });
            var cities = rows.stream().map(this::toEntity).toList();
            cityRepository.saveAll(cities);
            log.info("Seeded {} cities from seed/cities.json", cities.size());
        } catch (Exception exception) {
            log.error("Failed to seed cities", exception);
        }
    }

    private CityEntity toEntity(Map<String, Object> row) {
        var city = new CityEntity();
        city.setName(text(row.get("name")));
        city.setSlug(text(row.get("slug")));
        city.setNameEn(text(row.get("name_en")));
        city.setProvince(text(row.get("province")));
        city.setAdcode(text(row.get("adcode")));
        city.setEnabled(Boolean.TRUE.equals(row.get("enabled")));
        city.setStatus(text(row.get("status")).isEmpty() ? "planned" : text(row.get("status")));
        city.setDefaultLongitude(doubleValue(row.get("default_longitude")));
        city.setDefaultLatitude(doubleValue(row.get("default_latitude")));
        city.setSortOrder(row.get("sort_order") instanceof Number number ? number.intValue() : 100);
        return city;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
