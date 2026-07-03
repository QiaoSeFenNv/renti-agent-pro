package com.renti.agent.modules.city.api;

import java.util.Map;

import com.renti.agent.modules.city.application.CityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 城市与首页配置端点（公开，无需登录）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    @GetMapping({"/api/cities", "/api/home/cities"})
    public Map<String, Object> cities(@RequestParam(defaultValue = "") String query,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer limit) {
        return cityService.citiesPayload(query, page, limit);
    }

    @GetMapping("/api/home/config")
    public Map<String, Object> homeConfig() {
        return cityService.homeConfigPayload();
    }
}
