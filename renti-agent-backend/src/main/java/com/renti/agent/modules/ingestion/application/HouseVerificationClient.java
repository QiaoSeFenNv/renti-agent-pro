package com.renti.agent.modules.ingestion.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.renti.agent.infrastructure.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 上海市住房租赁公共服务平台房源核验客户端：按核验编号反查官方核验状态。
 *
 * <p>接口 POST https://zfzl.fgj.sh.gov.cn/Housecheck/housecheck，表单参数 checknum，
 * 无需登录、无验证码。返回 JSON：status（1 通过 / 2 未通过 / 0 核验中）+ communityName/area/roomtype/
 * districtName/renttypeName；编号不存在时返回空。域名为境内，直连（不走代理）。</p>
 */
@Slf4j
@Component
public class HouseVerificationClient {

    private static final String BASE_URL = "https://zfzl.fgj.sh.gov.cn";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private final HttpClientFactory httpClientFactory;
    private volatile RestClient restClient;

    public HouseVerificationClient(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    /** 官方核验结果：status（1/2/0）+ 小区/面积/户型/区。 */
    public record VerificationResult(int status, String communityName, String area, String roomType,
                                     String districtName, String rentTypeName) {
        public boolean passed() {
            return status == 1;
        }
    }

    /**
     * 按核验编号查询官方核验状态。编号为空/未命中返回 empty；网络失败抛 IllegalStateException。
     */
    @SuppressWarnings("unchecked")
    public Optional<VerificationResult> verify(String checknum) {
        if (checknum == null || checknum.isBlank()) {
            return Optional.empty();
        }
        Object body;
        try {
            body = client().post()
                    .uri("/Housecheck/housecheck")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Referer", BASE_URL + "/housecheck/housecheck.html")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body("checknum=" + checknum.trim())
                    .retrieve()
                    .body(Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException("官方核验请求失败：" + exception.getMessage(), exception);
        }
        if (!(body instanceof Map<?, ?> map) || map.isEmpty()) {
            return Optional.empty(); // 编号不存在
        }
        var data = (Map<String, Object>) map;
        return Optional.of(new VerificationResult(
                intValue(data.get("status")),
                textValue(data.get("communityName")),
                textValue(data.get("area")),
                textValue(data.get("roomtype")),
                textValue(data.get("districtName")),
                textValue(data.get("renttypeName"))));
    }

    private RestClient client() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    restClient = httpClientFactory.create(BASE_URL, "", 8.0)
                            .mutate()
                            .defaultHeader("User-Agent", UA)
                            .build();
                }
            }
        }
        return restClient;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
