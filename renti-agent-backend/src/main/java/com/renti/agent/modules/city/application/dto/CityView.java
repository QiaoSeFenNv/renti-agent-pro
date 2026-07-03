package com.renti.agent.modules.city.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 城市列表项（对齐旧 get_cities_payload 的城市对象，snake_case 转 camelCase）。
 * target 允许为 null（未开通城市），需显式输出。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CityView(
        String name,
        String slug,
        String en,
        String zh,
        String province,
        String adcode,
        boolean enabled,
        String status,
        String target,
        Double defaultLongitude,
        Double defaultLatitude
) {
}
