package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.AssetService;
import dev.myudog.assetsmanagerlinebot.service.CommandService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@RestController
@RequestMapping("/callback")
public class LineWebhookController {

    @Value("${line.bot.channel-secret}")
    private String channelSecret;

    private final AssetService assetService;
    private final CommandService commandService;
    private final LineStorageService lineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LineWebhookController(AssetService assetService, CommandService commandService,
                                 LineStorageService lineService) {
        this.assetService = assetService;
        this.commandService = commandService;
        this.lineService = lineService;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Line-Signature") String signature,
            @RequestBody String payload) {

        if (!verifySignature(payload, signature)) {
            System.out.println("[警告] 簽章驗證失敗");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode events = root.get("events");
            if (events != null && events.isArray()) {
                for (JsonNode event : events) {
                    // 單一事件出錯不該讓整批 webhook 回 500，否則 LINE 會不斷重送
                    try {
                        handleEvent(event);
                    } catch (Exception e) {
                        System.err.println("[事件] 處理單一事件失敗");
                        e.printStackTrace();
                    }
                }
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            System.err.println("[核心異常] 解析 Webhook 發生錯誤");
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    private void handleEvent(JsonNode event) throws Exception {
        if (!"message".equals(getSafeText(event, "type"))) {
            return;
        }
        JsonNode message = event.get("message");
        if (message == null) {
            return;
        }

        String replyToken = getSafeText(event, "replyToken");
        JsonNode source = event.get("source");
        String sourceType = getSafeText(source, "type");
        String sourceId = resolveSourceId(source);
        String uploaderId = getSafeText(source, "userId");
        String messageType = getSafeText(message, "type");

        switch (messageType) {
            case "image" -> handleImage(getSafeText(message, "id"), sourceType, sourceId, uploaderId, replyToken);
            case "text" -> commandService.handleText(
                    getSafeText(message, "text"),
                    getSafeText(message, "quotedMessageId"),
                    sourceId,
                    replyToken);
            default -> { /* 貼圖、影片、位置等目前不收錄 */ }
        }
    }

    private void handleImage(String messageId, String sourceType, String sourceId,
                             String uploaderId, String replyToken) throws Exception {
        if (messageId == null) {
            return;
        }
        LineStorageService.LineContent content = lineService.downloadContent(messageId);
        if (content == null) {
            lineService.replyText(replyToken, "圖片下載失敗，請再傳一次。");
            return;
        }
        assetService.ingest(messageId, sourceType, sourceId, uploaderId, content.stream(), content.contentType());
    }

    /** 群組、多人聊天室、一對一各有不同的識別欄位，統一成一個 sourceId 供查詢時分隔資料。 */
    private String resolveSourceId(JsonNode source) {
        if (source == null) {
            return null;
        }
        String groupId = getSafeText(source, "groupId");
        if (groupId != null) {
            return groupId;
        }
        String roomId = getSafeText(source, "roomId");
        return roomId != null ? roomId : getSafeText(source, "userId");
    }

    private String getSafeText(JsonNode parentNode, String fieldName) {
        if (parentNode == null) {
            return null;
        }
        JsonNode childNode = parentNode.get(fieldName);
        return childNode != null && childNode.isTextual() ? childNode.textValue() : null;
    }

    private boolean verifySignature(String payload, String headerSignature) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(rawHmac);
            // 用常數時間比對，避免以回應時間推敲出簽章
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    headerSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
