package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.ai.AiExtractionException;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【職責】群組文字訊息的指令解析與回覆組裝。
 *
 * <p>本類別是「使用者說的話」與「領域服務」之間唯一的翻譯層：
 * 它只負責看懂文字、決定要呼叫哪個服務、以及把結果講回群組，
 * 不碰檔案系統也不碰資料庫。
 *
 * <p>支援的兩種輸入型態：
 * <pre>
 *   ① 引用某張圖片 + 「zd12345」   → 該圖歸檔到 zd12345 資料夾（資料夾不存在會自動建立）
 *      引用某張圖片 + 「zd12345 台北 機房」→ 額外的字詞會一併存成標籤
 *
 *   ② 井字號指令
 *      #查 zd12345      → 取出該編號的圖片並貼回群組
 *      #標籤            → 列出本群組所有編號／標籤與數量
 *      #說明            → 用法
 *      #報價            → 引用一張規格圖後下此指令，跑 AI 提取 → 計算 → 產報價單
 * </pre>
 *
 * <p>井字號開頭一律當指令，因此需求 ① 的資產編號刻意不加井字號，兩者不會互相誤判。
 */
@Service
public class CommandService {

    /**
     * 資產編號格式：zd + 一組數字。大小寫皆可輸入，內部一律正規化成小寫，
     * 避免 ZD123 與 zd123 在 Linux 上變成兩個不同的資料夾。
     */
    private static final Pattern ASSET_CODE = Pattern.compile("(?i)\\bzd\\d+\\b");

    private static final String HELP = """
            📦 資產管理機器人用法

            ① 收錄：直接把圖片傳進群組，會先存到「未分類」。
            ② 歸檔：長按該張圖片 →「引用」→ 輸入資產編號，例如
               zd12345
               系統會自動建立 zd12345 資料夾並把圖片搬進去。
               後面可再加字詞當額外標籤：zd12345 台北 機房
            ③ 取用：#查 zd12345
               （給多個關鍵字時是「同時符合」的意思）
            ④ 盤點：#標籤 列出目前所有編號與數量
            ⑤ 報價：引用一張規格圖 →「#報價」
               會先用 AI 讀出規格，再套公式與模板產出報價單。
            """;

    private final AssetService assetService;
    private final LineStorageService lineService;
    private final QuotationService quotationService;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    @Value("${app.query.max-results:4}")
    private int maxResults;

    /**
     * @param assetService     資產的收錄、歸檔與查詢
     * @param lineService      對 LINE Messaging API 的發送管道
     * @param quotationService 報價流程（AI 提取 → 計算 → 產 PDF）
     */
    public CommandService(AssetService assetService, LineStorageService lineService,
                          QuotationService quotationService) {
        this.assetService = assetService;
        this.lineService = lineService;
        this.quotationService = quotationService;
    }

    /**
     * 文字訊息的總入口，依「有沒有引用圖片」決定要走歸檔還是走指令。
     *
     * <p>引用優先：使用者引用了一張圖片並輸入資產編號時，即使文字剛好以井字號開頭，
     * 也一律視為歸檔意圖。不符合任何形式的閒聊會被安靜忽略，不打擾群組。
     *
     * @param text            使用者輸入的原始文字
     * @param quotedMessageId 被引用訊息的 id；沒有引用時為 null
     * @param sourceId        群組／聊天室／使用者 id，用來把資料切開
     * @param replyToken      本次事件的回覆權杖
     */
    public void handleText(String text, String quotedMessageId, String sourceId, String replyToken) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.startsWith("#")) {
            handleCommand(text.substring(1).trim(), quotedMessageId, sourceId, replyToken);
            return;
        }
        if (quotedMessageId != null) {
            archiveQuotedImage(text, quotedMessageId, replyToken);
        }
    }

    /**
     * 解析井字號指令並分派。未知指令一律不回應，避免把群組洗版。
     *
     * @param body            去掉開頭井字號後的內容，例如「查 zd12345」
     * @param quotedMessageId 被引用訊息的 id；沒有引用時為 null
     * @param sourceId        資料範圍
     * @param replyToken      回覆權杖
     */
    private void handleCommand(String body, String quotedMessageId, String sourceId, String replyToken) {
        String[] parts = body.split("\\s+");
        switch (parts[0]) {
            case "說明", "help", "?" -> lineService.replyText(replyToken, HELP);
            case "標籤", "清單" -> replyTagList(sourceId, replyToken);
            case "查" -> replySearch(sourceId, Arrays.asList(parts).subList(1, parts.length), replyToken);
            case "報價" -> replyQuotation(quotedMessageId, replyToken);
            default -> { /* 未知指令不回應 */ }
        }
    }

    /**
     * 需求 ②：對被引用的規格圖跑報價流程。
     *
     * <p>三段流程只有 AI 提取是完成的，公式與模板尚未提供，
     * 因此這裡把「提取成功但後段未完成」與「提取本身就失敗」分開回報：
     * 前者仍會把 AI 讀到的欄位念出來，讓使用者確認辨識品質。
     *
     * @param quotedMessageId 被引用的規格圖訊息 id
     * @param replyToken      回覆權杖
     */
    private void replyQuotation(String quotedMessageId, String replyToken) {
        if (quotedMessageId == null) {
            lineService.replyText(replyToken, "請先引用一張規格圖，再輸入 #報價。");
            return;
        }
        if (!quotationService.isAiConfigured()) {
            lineService.replyText(replyToken,
                    "AI 服務尚未設定，請先填入 AI_API_URL、AI_API_KEY、AI_MODEL 三個環境變數。");
            return;
        }

        Optional<Asset> found = assetService.findByMessageId(quotedMessageId);
        if (found.isEmpty()) {
            lineService.replyText(replyToken, "找不到這張圖片的收錄紀錄，請重新上傳一次再試。");
            return;
        }

        try {
            Asset asset = found.get();
            byte[] image = assetService.contentOf(asset);
            QuotationService.QuotationResult result = quotationService.quote(image, asset.contentType());

            if (result.isComplete()) {
                lineService.replyText(replyToken, "✅ 報價單已產出：" + result.pdfPath().getFileName());
                return;
            }

            StringBuilder sb = new StringBuilder("🤖 AI 已讀出以下規格：\n");
            result.spec().fields().forEach((key, value) ->
                    sb.append("・").append(key).append("：").append(value == null ? "（未辨識）" : value).append('\n'));
            sb.append('\n').append("⚠️ ").append(result.blockedStep()).append("，尚無法產出報價單。");
            lineService.replyText(replyToken, sb.toString());

        } catch (AiExtractionException e) {
            lineService.replyText(replyToken, "❌ " + e.userMessage());
        } catch (IOException e) {
            System.err.println("[指令] 讀取規格圖失敗，quotedMessageId=" + quotedMessageId);
            e.printStackTrace();
            lineService.replyText(replyToken, "讀取圖片失敗，請查看伺服器記錄。");
        }
    }

    /**
     * 需求 ①：把被引用的圖片歸檔到資產編號對應的資料夾。
     *
     * <p>文字裡必須出現 {@code zd+數字} 才會動作，否則使用者只是在引用圖片閒聊。
     * 該編號同時成為實體資料夾名稱與第一個標籤，其餘字詞存為附加標籤。
     *
     * @param text            使用者輸入，例如「zd12345 台北 機房」
     * @param quotedMessageId 被引用的圖片訊息 id
     * @param replyToken      回覆權杖
     */
    private void archiveQuotedImage(String text, String quotedMessageId, String replyToken) {
        Matcher matcher = ASSET_CODE.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String assetCode = matcher.group().toLowerCase();

        // 資產編號永遠排在第一個，因為第一個標籤決定實體資料夾
        List<String> tags = new ArrayList<>();
        tags.add(assetCode);
        tags.addAll(extraTags(text, assetCode));

        try {
            Optional<Asset> archived = assetService.tag(quotedMessageId, tags);
            if (archived.isEmpty()) {
                lineService.replyText(replyToken,
                        "找不到這張圖片的收錄紀錄，可能是機器人加入群組之前傳的，請重新上傳一次。");
                return;
            }
            Asset asset = archived.get();
            StringBuilder reply = new StringBuilder("✅ 已歸檔到資料夾「" + asset.category() + "」");
            if (asset.tags().size() > 1) {
                reply.append("\n標籤：").append(String.join("、", asset.tags()));
            }
            lineService.replyText(replyToken, reply.toString());
        } catch (IOException e) {
            System.err.println("[指令] 歸檔時搬移檔案失敗，quotedMessageId=" + quotedMessageId);
            e.printStackTrace();
            lineService.replyText(replyToken, "編號已記錄，但檔案搬移失敗，請查看伺服器記錄。");
        }
    }

    /**
     * 取出資產編號以外的字詞當附加標籤，並濾掉會破壞路徑的字元。
     *
     * @param text      原始輸入
     * @param assetCode 已經認出來的資產編號（需排除，避免重複）
     * @return 去重後的附加標籤，順序與使用者輸入一致
     */
    private static List<String> extraTags(String text, String assetCode) {
        List<String> tags = new ArrayList<>();
        for (String token : text.split("[\\s,，、]+")) {
            String tag = FileStorageService.sanitize(token.replaceAll("^#+", ""));
            if (tag.isEmpty()
                    || tag.equalsIgnoreCase(assetCode)
                    || FileStorageService.UNCLASSIFIED.equals(tag)
                    || tags.contains(tag)) {
                continue;
            }
            tags.add(tag);
        }
        return tags;
    }

    /**
     * 依關鍵字取出資產並以圖片訊息貼回群組。
     *
     * <p>LINE 只接受公開 HTTPS 網址，所以這裡先擋掉未設定對外網址的情況，
     * 否則使用者只會看到一則沒有下文的空回應。
     *
     * @param sourceId   查詢範圍（限定同一群組）
     * @param tags       關鍵字，多個代表必須同時符合
     * @param replyToken 回覆權杖
     */
    private void replySearch(String sourceId, List<String> tags, String replyToken) {
        if (tags.isEmpty()) {
            lineService.replyText(replyToken, "請指定編號或關鍵字，例如：#查 zd12345");
            return;
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            lineService.replyText(replyToken,
                    "尚未設定 PUBLIC_BASE_URL，LINE 無法連回本服務抓圖，請先設定對外網址。");
            return;
        }

        List<String> normalized = tags.stream().map(String::toLowerCase).toList();
        List<Asset> results = assetService.search(sourceId, normalized, maxResults);
        if (results.isEmpty()) {
            lineService.replyText(replyToken, "查無符合「" + String.join("、", tags) + "」的資產。");
            return;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(LineStorageService.textMessage(
                "🔍 「" + String.join("、", tags) + "」找到 " + results.size() + " 筆："));
        for (Asset asset : results) {
            String url = publicBaseUrl.replaceAll("/+$", "") + "/media/" + asset.shareToken();
            messages.add(LineStorageService.imageMessage(url, url));
        }
        lineService.reply(replyToken, messages);
    }

    /**
     * 列出本群組用過的所有編號／標籤與各自的圖片數，供盤點使用。
     *
     * @param sourceId   統計範圍
     * @param replyToken 回覆權杖
     */
    private void replyTagList(String sourceId, String replyToken) {
        Map<String, Integer> counts = assetService.tagCounts(sourceId);
        int total = assetService.countBySource(sourceId);
        if (counts.isEmpty()) {
            lineService.replyText(replyToken, "本群組尚未有任何編號，目前共收錄 " + total + " 張圖片。");
            return;
        }
        StringBuilder sb = new StringBuilder("🏷 本群組編號／標籤（共收錄 " + total + " 張）\n");
        counts.forEach((name, count) -> sb.append("・").append(name).append("　").append(count).append(" 張\n"));
        lineService.replyText(replyToken, sb.toString().trim());
    }
}
