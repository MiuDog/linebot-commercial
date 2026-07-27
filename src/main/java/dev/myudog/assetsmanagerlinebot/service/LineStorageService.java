package dev.myudog.assetsmanagerlinebot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 與 LINE Messaging API 的所有往來：下載訊息內容、回覆訊息。
 */
@Service
public class LineStorageService {

    @Value("${line.bot.channel-token}")
    private String channelToken;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** LINE 回傳的原始內容與其 Content-Type，副檔名要靠後者決定。 */
    public record LineContent(InputStream stream, String contentType) {}

    public LineContent downloadContent(String messageId) {
        String url = "https://api-data.line.me/v2/bot/message/" + messageId + "/content";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + channelToken)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                System.err.println("[LINE] 下載內容失敗，messageId=" + messageId + " 狀態碼=" + response.statusCode());
                response.body().close();
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("image/jpeg");
            return new LineContent(response.body(), contentType);
        } catch (Exception e) {
            System.err.println("[LINE] 下載內容時發生例外，messageId=" + messageId);
            e.printStackTrace();
            return null;
        }
    }

    public void replyText(String replyToken, String text) {
        reply(replyToken, List.of(textMessage(text)));
    }

    /**
     * 回覆一組訊息。LINE 單次 reply 最多 5 則，超過的部分會被官方直接退回。
     */
    public void reply(String replyToken, List<Map<String, Object>> messages) {
        if (replyToken == null || messages.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replyToken", replyToken);
        body.put("messages", messages.size() > 5 ? messages.subList(0, 5) : messages);
        post("https://api.line.me/v2/bot/message/reply", body);
    }

    public static Map<String, Object> textMessage(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", text);
        return message;
    }

    public static Map<String, Object> imageMessage(String originalUrl, String previewUrl) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "image");
        message.put("originalContentUrl", originalUrl);
        message.put("previewImageUrl", previewUrl);
        return message;
    }

    /**
     * 訊息內容含中文與使用者自由輸入，一律交給 Jackson 序列化，
     * 不用字串拼接，避免引號或換行把 JSON 打壞。
     */
    private void post(String url, Map<String, Object> body) {
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + channelToken)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[LINE] 發送失敗，狀態碼=" + response.statusCode() + " 原因=" + response.body());
            }
        } catch (Exception e) {
            System.err.println("[LINE] 發送訊息時發生例外");
            e.printStackTrace();
        }
    }
}
