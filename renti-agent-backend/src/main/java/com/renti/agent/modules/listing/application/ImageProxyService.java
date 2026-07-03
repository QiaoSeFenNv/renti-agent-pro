package com.renti.agent.modules.listing.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.renti.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 房源图片代理：对齐旧 image_proxy.py。
 *
 * <p>仅允许公网 HTTP/HTTPS 地址（拒绝内网/环回/链路本地/组播 IP 与 localhost），
 * 响应体上限 4MB，非图片内容按魔数推断，链家 CDN 带 Referer 伪装。</p>
 */
@Slf4j
@Service
public class ImageProxyService {

    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 拉取远程图片，返回字节与 Content-Type；地址非法/内容非图片抛 400，网络失败抛 502 */
    public FetchedImage fetch(String url) {
        var cleanUrl = url == null ? "" : url.trim();
        if (!isRemoteHttpUrl(cleanUrl)) {
            throw BusinessException.badRequest("图片地址必须是公网 HTTP/HTTPS URL。");
        }
        var request = HttpRequest.newBuilder(URI.create(cleanUrl))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Referer", refererFor(cleanUrl))
                .GET()
                .build();

        byte[] payload;
        String contentType;
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                payload = body.readNBytes(MAX_IMAGE_BYTES + 1);
            }
            contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";")[0].trim().toLowerCase();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Listing image fetch failed: {} ({})", cleanUrl, exception.getMessage());
            throw BusinessException.upstream("房源图片暂时无法读取。");
        }

        if (payload.length > MAX_IMAGE_BYTES) {
            throw BusinessException.badRequest("图片文件过大。");
        }
        if (!contentType.startsWith("image/")) {
            contentType = inferContentType(payload);
        }
        if (!contentType.startsWith("image/")) {
            throw BusinessException.badRequest("远程地址未返回图片内容。");
        }
        return new FetchedImage(payload, contentType);
    }

    public record FetchedImage(byte[] payload, String contentType) {
    }

    static boolean isRemoteHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        var host = uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase();
        return !host.isEmpty() && !isPrivateHost(host);
    }

    static boolean isPrivateHost(String host) {
        var normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")) {
            return true;
        }
        if (normalized.contains(":")) {
            return isPrivateIpv6(normalized);
        }
        return isPrivateIpv4(normalized);
    }

    private static boolean isPrivateIpv4(String host) {
        var parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        var octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int first = octets[0];
        int second = octets[1];
        return first == 0                                  // unspecified
                || first == 10                             // 10/8
                || first == 127                            // loopback
                || (first == 172 && second >= 16 && second <= 31)   // 172.16/12
                || (first == 192 && second == 168)         // 192.168/16
                || (first == 169 && second == 254)         // link-local
                || (first >= 224 && first <= 239);         // multicast
    }

    private static boolean isPrivateIpv6(String host) {
        var value = host.toLowerCase();
        return value.equals("::") || value.equals("::1")
                || value.startsWith("fe80") || value.startsWith("fc") || value.startsWith("fd")
                || value.startsWith("ff");
    }

    private static String refererFor(String url) {
        var uri = URI.create(url);
        var host = uri.getHost() == null ? "" : uri.getHost();
        if (host.endsWith("ljcdn.com")) {
            return "https://sh.lianjia.com/";
        }
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/";
    }

    private static String inferContentType(byte[] payload) {
        if (startsWith(payload, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(payload, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return "image/png";
        }
        if (startsWith(payload, "GIF87a".getBytes()) || startsWith(payload, "GIF89a".getBytes())) {
            return "image/gif";
        }
        if (startsWith(payload, "RIFF".getBytes())) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private static boolean startsWith(byte[] payload, byte[] prefix) {
        if (payload.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (payload[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
