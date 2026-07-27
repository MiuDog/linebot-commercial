package dev.myudog.assetsmanagerlinebot.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【職責】把規格圖／資訊圖送給 AI 模型，並把回應整理成結構化欄位。
 *
 * <p>只做兩件事：呼叫模型、處理結果。它不知道報價公式，也不知道 PDF 長什麼樣，
 * 那些屬於 {@code QuotationCalculator} 與 {@code QuotationPdfService}。
 *
 * <p><b>設定全部留空，需由使用者填入：</b>
 * <pre>
 *   AI_API_URL         模型端點（OpenAI 相容的 /chat/completions）
 *   AI_API_KEY         金鑰
 *   AI_MODEL           模型名稱
 *   AI_REQUIRED_FIELDS 必要欄位，以逗號分隔；缺任何一項就報錯
 * </pre>
 * 請求格式採用 OpenAI 相容的 chat completions（圖片以 base64 data URL 內嵌），
 * 這是目前相容性最廣的一種；若最終選用的服務格式不同，只需要改
 * {@link #buildRequestBody} 與 {@link #extractContent} 兩個方法。
 */
@Service
public class AiExtractionService {

    /**
     * 提示詞。要求模型只輸出 JSON，後續解析才不必處理自然語言。
     * 實際要提取哪些欄位由 {@code app.ai.required-fields} 帶入。
     */
    private static final String PROMPT_TEMPLATE = """
            你是一個規格資料擷取工具。請閱讀這張規格圖／資訊圖，
            擷取出下列欄位並「只」回傳一個 JSON 物件，不要任何說明文字或程式碼區塊標記。

            需要擷取的欄位：%s

            規則：
            1. 找不到的欄位，值請填 null，不要自行推測或編造。
            2. 數字請保留原始單位文字，例如 "1200 mm"。
            3. 回傳格式範例：{"欄位A": "值", "欄位B": null}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${app.ai.api-url:}")
    private String apiUrl;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${app.ai.model:}")
    private String model;

    /** 必要欄位；留空代表不做欄位檢查，任何回應都算成功。 */
    @Value("${app.ai.required-fields:}")
    private String requiredFieldsRaw;

    @Value("${app.ai.timeout-seconds:60}")
    private int timeoutSeconds;

    /**
     * 建立 HTTP 用戶端。連線逾時固定 15 秒，讀取逾時另由每個請求指定。
     */
    public AiExtractionService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 設定是否齊全。指令入口應先問過這個方法，才不會讓使用者等到逾時才知道沒設定。
     *
     * @return 端點、金鑰、模型三者都有值時為 true
     */
    public boolean isConfigured() {
        return notBlank(apiUrl) && notBlank(apiKey) && notBlank(model);
    }

    /**
     * 把圖片送給模型並取回結構化欄位。
     *
     * <p>流程：組請求 → 呼叫 → 取出回應文字 → 解析 JSON → 檢查必要欄位。
     * 任何一步失敗都拋出 {@link AiExtractionException}，由呼叫端轉成群組訊息。
     *
     * @param imageBytes  圖片位元組
     * @param contentType 圖片 MIME 型態，例如 image/jpeg
     * @return 擷取結果
     * @throws AiExtractionException 未設定、呼叫失敗、回應無法解析、或必要欄位缺漏
     */
    public ExtractedSpec extract(byte[] imageBytes, String contentType) {
        if (!isConfigured()) {
            throw new AiExtractionException("AI 服務尚未設定（AI_API_URL／AI_API_KEY／AI_MODEL）", (Throwable) null);
        }

        List<String> requiredFields = requiredFields();
        String responseBody = callModel(imageBytes, contentType, requiredFields);
        String content = extractContent(responseBody);
        Map<String, Object> fields = parseJsonObject(content);
        validateRequiredFields(fields, requiredFields);
        return new ExtractedSpec(fields, content);
    }

    /**
     * 解析設定字串成必要欄位清單。
     *
     * @return 欄位名稱；未設定時為空集合，代表不檢查
     */
    private List<String> requiredFields() {
        if (!notBlank(requiredFieldsRaw)) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (String part : requiredFieldsRaw.split("[,，]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                fields.add(trimmed);
            }
        }
        return fields;
    }

    /**
     * 實際發出 HTTP 請求。
     *
     * @param imageBytes     圖片位元組
     * @param contentType    圖片 MIME 型態
     * @param requiredFields 要擷取的欄位，寫進提示詞
     * @return 回應本文
     * @throws AiExtractionException 連線失敗或狀態碼非 2xx
     */
    private String callModel(byte[] imageBytes, String contentType, List<String> requiredFields) {
        try {
            String payload = objectMapper.writeValueAsString(
                    buildRequestBody(imageBytes, contentType, requiredFields));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new AiExtractionException(
                        "模型回應狀態碼 " + response.statusCode() + "：" + truncate(response.body()),
                        (Throwable) null);
            }
            return response.body();
        } catch (AiExtractionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiExtractionException("呼叫模型時被中斷", e);
        } catch (Exception e) {
            throw new AiExtractionException("呼叫模型失敗：" + e.getMessage(), e);
        }
    }

    /**
     * 組出 OpenAI 相容的 chat completions 請求本文。
     *
     * <p>圖片以 base64 data URL 內嵌，避免還要先把圖片上傳到某個公開位置。
     * 換成其他廠商的 API 時，改這個方法即可。
     *
     * @param imageBytes     圖片位元組
     * @param contentType    圖片 MIME 型態
     * @param requiredFields 要擷取的欄位
     * @return 可直接序列化成 JSON 的請求本文
     */
    private Map<String, Object> buildRequestBody(byte[] imageBytes, String contentType,
                                                 List<String> requiredFields) {
        String fieldList = requiredFields.isEmpty()
                ? "（未設定，請擷取圖片中所有可辨識的規格欄位）"
                : String.join("、", requiredFields);
        String dataUrl = "data:" + (notBlank(contentType) ? contentType : "image/jpeg")
                + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", PROMPT_TEMPLATE.formatted(fieldList));

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUrl);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(message));
        // 擷取工作要的是穩定而不是創意，溫度壓到 0
        body.put("temperature", 0);
        return body;
    }

    /**
     * 從模型回應中取出真正的文字內容。
     *
     * @param responseBody 回應本文
     * @return 模型輸出的文字
     * @throws AiExtractionException 回應結構不符預期
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new AiExtractionException(
                        "模型回應結構不符預期：" + truncate(responseBody), (Throwable) null);
            }
            return content.textValue();
        } catch (AiExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new AiExtractionException("模型回應不是合法 JSON：" + truncate(responseBody), e);
        }
    }

    /**
     * 把模型輸出的文字解析成欄位對應。
     *
     * <p>即使提示詞要求只回 JSON，模型仍常常包上 ```json 區塊或加一句開場白，
     * 因此這裡先剝掉程式碼區塊標記，再擷取第一個大括號到最後一個大括號之間的內容。
     *
     * @param content 模型輸出文字
     * @return 欄位對應
     * @throws AiExtractionException 內容不含合法的 JSON 物件
     */
    private Map<String, Object> parseJsonObject(String content) {
        String cleaned = content.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiExtractionException("模型沒有回傳 JSON 物件：" + truncate(content), (Throwable) null);
        }

        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
            Map<String, Object> fields = new LinkedHashMap<>();
            node.propertyNames().forEach(name -> {
                JsonNode value = node.get(name);
                fields.put(name, value == null || value.isNull() ? null : toPlainValue(value));
            });
            return fields;
        } catch (Exception e) {
            throw new AiExtractionException("解析模型輸出失敗：" + truncate(content), e);
        }
    }

    /**
     * 把 JSON 節點轉成單純的 Java 值，方便公式端直接使用。
     *
     * @param value JSON 節點
     * @return 字串、數字或原始文字表示
     */
    private Object toPlainValue(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.isTextual() ? value.textValue() : value.toString();
    }

    /**
     * 檢查必要欄位是否都有值。
     *
     * <p>「欄位不存在」與「欄位存在但值是 null／空字串」都算缺漏——
     * 模型被要求找不到就填 null，所以後者才是常見情況。
     *
     * @param fields         解析出的欄位
     * @param requiredFields 必要欄位
     * @throws AiExtractionException 任何必要欄位缺漏
     */
    private void validateRequiredFields(Map<String, Object> fields, List<String> requiredFields) {
        if (requiredFields.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            Object value = fields.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new AiExtractionException("必要欄位缺漏", missing);
        }
    }

    /**
     * 截短過長的內容，避免把整包回應塞進日誌或群組訊息。
     *
     * @param text 原始文字，可為 null
     * @return 最多 300 字的片段
     */
    private static String truncate(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    /**
     * 字串是否有實質內容。
     *
     * @param value 待檢查字串，可為 null
     * @return 非 null 且非空白時為 true
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
