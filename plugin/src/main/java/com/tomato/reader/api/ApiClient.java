package com.tomato.reader.api;

import com.tomato.reader.infra.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方代理服务的 HTTP 客户端。
 *
 * <p>刻意使用 {@link HttpURLConnection}（java.base 内）而不是 OkHttp / java.net.http：
 * 前者一定存在于 IDE 运行时，后者在 JBR 的裁剪镜像里不保证有，且平台自带的 OkHttp 版本随 IDE 变动。</p>
 */
public final class ApiClient {

    private final String base;
    private final Map<String, String> headers;
    private final int timeoutMs;

    public ApiClient(String baseUrl, Map<String, String> headers, int timeoutMs) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.base = b;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
        this.timeoutMs = timeoutMs <= 0 ? 15000 : timeoutMs;
    }

    public boolean isConfigured() {
        return !base.isEmpty();
    }

    public String baseUrl() {
        return base;
    }

    /** 拼接最终 URL，便于在设置页做"测试连接"时展示 */
    public String buildUrl(String path, Map<String, String> query) throws ApiException {
        if (base.isEmpty()) {
            throw new ApiException("还没有填写 API 地址");
        }
        StringBuilder sb = new StringBuilder(base);
        if (!path.startsWith("/")) {
            sb.append('/');
        }
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            char sep = path.contains("?") ? '&' : '?';
            for (Map.Entry<String, String> e : query.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isEmpty()) {
                    continue;
                }
                sb.append(sep).append(urlEncode(e.getKey())).append('=').append(urlEncode(v));
                sep = '&';
            }
        }
        return sb.toString();
    }

    /** 发起 GET 请求并解析 JSON */
    public Object getJson(String path, Map<String, String> query) throws ApiException {
        String url = buildUrl(path, query);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    conn.setRequestProperty(e.getKey().trim(), e.getValue() == null ? "" : e.getValue());
                }
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);

            if (code == 401 || code == 403) {
                throw new ApiException("未授权（HTTP " + code + "）。请检查 API 地址与授权请求头是否正确。"
                        + snippet(text));
            }
            if (code >= 400) {
                throw new ApiException("请求失败（HTTP " + code + "）。" + snippet(text));
            }
            if (text == null || text.trim().isEmpty()) {
                throw new ApiException("服务返回了空内容，地址可能不对。");
            }
            try {
                return Json.parse(text);
            } catch (RuntimeException e) {
                throw new ApiException("返回内容不是合法 JSON：" + snippet(text));
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("网络错误：" + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() > 200) {
            t = t.substring(0, 200) + "…";
        }
        return t.isEmpty() ? "" : "\n服务返回：" + t;
    }

    /** 把 "Key: Value" 多行文本解析成请求头表 */
    public static Map<String, String> parseHeaders(String raw, String phone, String password) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int idx = s.indexOf(':');
                if (idx < 0) {
                    idx = s.indexOf('=');
                }
                if (idx <= 0) {
                    continue;
                }
                String k = s.substring(0, idx).trim();
                String v = s.substring(idx + 1).trim();
                if (!k.isEmpty()) {
                    map.put(k, v);
                }
            }
        }
        if (phone != null && !phone.trim().isEmpty()) {
            map.put("X-Phone", phone.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            map.put("X-Password", password.trim());
        }
        return map;
    }
}
