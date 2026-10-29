package com.ai.workbench.core.kb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/**
 * 文档文本抽取器：统一「多格式文件 → 纯文本」与「网页 URL → 标题+正文」两条入口。
 *
 * 为什么选 Tika 而不是 PDFBox+POI+jsoup 组合：一套依赖覆盖 pdf/doc/docx/html/rtf
 * （内部就是这些库），调用面只有一个 Tika.parse，学习成本与出错面都最小。
 *
 * 诚实边界：解析不出的（扫描版 PDF、超 2MB 正文的大文档）不造假——抛异常，
 * 由调用方如实写 FAILED + error_msg，检索层不会出现空切片假 READY。
 */
@Component
public class DocumentExtractor {

    /** 支持的扩展名：txt/md/csv 走 UTF-8 直读，其余交 Tika（与 KbController.ALL_TYPES 保持一致） */
    public static final Set<String> SUPPORTED = Set.of("txt", "md", "csv", "pdf", "doc", "docx", "html", "htm");

    /** 纯文本类：字节即文本，无需解析引擎 */
    private static final Set<String> PLAIN_TYPES = Set.of("txt", "md", "csv");

    /**
     * 提取正文的 writeLimit 上限（2MB）：解析在请求线程同步执行，超长文本直接判失败，
     * 防止一个巨型 PDF 把堆内存撑爆——上限截断比无界吞下更安全也更诚实。
     */
    private static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;

    /** 网页抓取响应体上限：5MB，防止被抓取超大文件拖垮带宽与内存 */
    private static final long MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024;

    /** 连接与响应共用 10s 超时：同步链路里用户在等结果，不能无限等 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 解析引擎：AutoDetectParser 按内容自动探测类型并选择内部解析器（PDFBox/POI 等）。
     * Tika 3 起门面类 Tika 不再提供 ContentHandler 版 parse，直接用 Parser API；
     * AutoDetectParser 线程安全，全进程复用一个即可。
     */
    private final AutoDetectParser parser = new AutoDetectParser();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 网页抓取结果：title 为页面 <title>（取不到时由调用方用 URL 兜底），text 为正文纯文本 */
    public record Extracted(String title, String text) {
    }

    /**
     * 文件抽取：txt/md/csv 直接按 UTF-8 读；pdf/doc/docx/html/htm 交 Tika 提正文。
     * 不支持的类型抛 IllegalArgumentException；解析失败抛 IllegalStateException，
     * 文本超 2MB 抛 IllegalArgumentException——调用方统一按「本篇 FAILED」处理。
     */
    public String extract(byte[] bytes, String ext) {
        String type = ext == null ? "" : ext.toLowerCase();
        if (!SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("不支持的文档类型：" + ext);
        }
        if (PLAIN_TYPES.contains(type)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARS);
            Metadata metadata = new Metadata();
            parser.parse(in, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (WriteLimitReachedException e) {
            throw new IllegalArgumentException("文档提取文本超过 2MB 上限，请拆分后上传", e);
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalStateException("文档解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 网页抓取：仅 http/https + 私网黑名单 + 5MB/10s 上限。
     * 约束来源是抓取接口天然暴露给登录用户，不设防就等于开放一台内网 HTTP 代理。
     */
    public Extracted fetchUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式无效：" + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https 链接");
        }
        assertPublicHost(uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "ai-workbench-kb/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("网页抓取失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("网页抓取被中断", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("网页返回异常状态：HTTP " + response.statusCode());
        }
        byte[] body;
        try {
            body = readLimited(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("网页读取失败：" + e.getMessage(), e);
        }
        try (InputStream in = new ByteArrayInputStream(body)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARS);
            Metadata metadata = new Metadata();
            parser.parse(in, handler, metadata, new ParseContext());
            String title = metadata.get(TikaCoreProperties.TITLE);
            if (title == null || title.isBlank()) {
                title = url; // 无 <title> 的页面用 URL 兜底，保证文档标题不为空
            }
            return new Extracted(title.trim(), handler.toString());
        } catch (WriteLimitReachedException e) {
            throw new IllegalArgumentException("网页正文超过 2MB 上限，请换更聚焦的链接", e);
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalStateException("网页解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * SSRF 防护（简单前缀黑名单版）：拦 localhost / 回环 / RFC1918 私网 / 链路本地。
     * 这是字符串前缀判断而非真实 DNS 解析——rebinding 等绕过手段不在 MVP 防线内，
     * 生产化时应在独立代理层做出口 IP 白名单。
     */
    private void assertPublicHost(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        // IPv6 字面量在 URI 里带方括号（[::1]），统一去括号后按文本前缀比对
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()
                || host.equals("localhost") || host.endsWith(".localhost")
                || host.startsWith("127.") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.startsWith("169.254.")
                || host.equals("::1") || host.startsWith("::1:")) {
            throw new IllegalArgumentException("不允许抓取内网/本机地址：" + host);
        }
        // 172.x 单独判断：只有第二段落在 16~31 才是私网（172.16.0.0/12），
        // 其余 172.x（如 172.217.x 的公网服务）要放行
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            try {
                int second = Integer.parseInt(parts[1]);
                if (second >= 16 && second <= 31) {
                    throw new IllegalArgumentException("不允许抓取内网/本机地址：" + host);
                }
            } catch (NumberFormatException ignored) {
                // 非数字第二段交给后续真实请求去失败，这里不误伤
            }
        }
    }

    /** 边读边限流：超过 5MB 立即中断连接，而不是下载完再检查 */
    private byte[] readLimited(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buffer)) != -1) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IllegalArgumentException("网页内容超过 5MB 上限");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
