package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.ai.AiExtractionException;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 【職責】群組文字訊息的指令解析與回覆組裝。
 *
 * <p>本類別是「使用者說的話」與「領域服務」之間唯一的翻譯層：
 * 它只負責看懂文字、決定要呼叫哪個服務、以及把結果講回群組，
 * 不碰檔案系統也不碰資料庫。
 *
 * <p>支援的輸入型態：
 * <pre>
 *   ① 引用圖片組中的任一張 + 合法的大寫資料夾代碼 → 直接歸檔整組圖片
 *
 *   ② 井字號指令
 *      #查 ZD12345      → 取出該資料夾代碼的圖片並貼回群組
 *      #標籤            → 列出本群組所有編號／標籤與數量
 *      #說明            → 用法
 *      #報價            → 引用一張規格圖後下此指令，跑 AI 提取 → 計算 → 產報價單
 * </pre>
 *
 * <p>井字號開頭一律當指令，與圖片歸檔流程互不混淆。
 *
 * <p><b>共同呼叫鏈：</b>
 * {@code LineWebhookController.handleEvent → handleText → handleCommand／歸檔分支
 * → 領域 Service → LineStorageService.replyText／reply}。
 *
 * <p><b>各事件分支：</b>
 * <ul>
 *   <li>{@code 資料夾代碼 → ImageArchiveService.archive}</li>
 *   <li>{@code #標籤 → AssetService.tagCounts + countBySource}</li>
 *   <li>{@code #查 → AssetService.search → /media/{shareToken}}</li>
 *   <li>{@code #報價 → AssetService.contentOf → QuotationService.quote}</li>
 * </ul>
 * 完整方法級順序與所有狀態分支見 {@code docs/06-event-call-chains.md}。
 */
@Service
public class CommandService {

	private static final Logger log = LoggerFactory.getLogger(CommandService.class);

	/**
	 * 歸檔格式只接受大寫，資料夾名稱完整保留這裡匹配的代碼。
	 */
	private static final Pattern ARCHIVE_CODE =
		Pattern.compile("^(?:ZD\\d{5}[A-Z]?|ZD-JY\\d{5}|YJ\\d{6})$");

	private static final Pattern ARCHIVE_PREFIX =
		Pattern.compile("^(?:ZD|YJ).*");

	private static final String ARCHIVE_SYNTAX_ERROR =
		"檢測到語法錯誤，請修正後重新執行指令";

	private static final String HELP = """
            📦 資產管理機器人用法

            ① 上傳：把一張或一組圖片傳進群組。
            ② 歸檔：長按該組任一張圖片 →「引用」→ 輸入大寫資料夾代碼：
               ZD + 5 位數字 + 可選 1 位大寫英文字母
               ZD-JY + 5 位數字
               YJ + 6 位數字
               符合格式後會直接歸檔整組圖片。
            ③ 取用：#查 ZD12345
               （給多個關鍵字時是「同時符合」的意思）
            ④ 盤點：#標籤 列出目前所有編號與數量
            ⑤ 報價：引用一張規格圖 →「#報價」
               會先用 AI 讀出規格，再套公式與模板產出報價單。
            """;

	private final AssetService assetService;
	private final LineStorageService lineService;
	private final QuotationService quotationService;
	private final ImageArchiveService imageArchiveService;

	@Value("${app.public-base-url:}")
	private String publicBaseUrl;

	@Value("${app.query.max-results:4}")
	private int maxResults;

	/**
	 * @param assetService     資產的收錄、歸檔與查詢
	 * @param lineService      對 LINE Messaging API 的發送管道
	 * @param quotationService 報價流程（AI 提取 → 計算 → 產 PDF）
	 */
	//#region 初始化與指令路由

	// 方法：初始化 CommandService。
	public CommandService(
		AssetService assetService,
		LineStorageService lineService,
		QuotationService quotationService,
		ImageArchiveService imageArchiveService
	) {
		this.assetService = assetService;
		this.lineService = lineService;
		this.quotationService = quotationService;
		this.imageArchiveService = imageArchiveService;
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
	// 方法：執行 handleText 方法的處理流程。
	public void handleText(String text, String quotedMessageId, String sourceId, String requesterId, String replyToken) {
		if (text == null || text.isBlank()) return;

		String trimmed = text.trim();
		if (trimmed.startsWith("#")) {
			handleCommand(trimmed.substring(1).trim(), quotedMessageId, sourceId, replyToken);
			return;
		}
		if (!ARCHIVE_CODE.matcher(trimmed).matches()) {
			if (ARCHIVE_PREFIX.matcher(trimmed).matches()) {
				lineService.replyText(replyToken, ARCHIVE_SYNTAX_ERROR);
			}
			return;
		}
		if (quotedMessageId == null) {
			lineService.replyText(
				replyToken,
				"尚未回覆圖片，請回覆要歸檔的圖片組後重新執行指令"
			);
			return;
		}

		archive(quotedMessageId, sourceId, trimmed, replyToken);
	}

	/**
	 * 解析井字號指令並分派。未知指令一律不回應，避免把群組洗版。
	 *
	 * @param body            去掉開頭井字號後的內容，例如「查 zd12345」
	 * @param quotedMessageId 被引用訊息的 id；沒有引用時為 null
	 * @param sourceId        資料範圍
	 * @param replyToken      回覆權杖
	 */
	// 方法：執行 handleCommand 方法的處理流程。
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
	//#endregion

	//#region 報價

	// 方法：執行 replyQuotation 方法的處理流程。
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
		}
		catch (AiExtractionException e) {
			lineService.replyText(replyToken, "❌ " + e.userMessage());
		}
		catch (IOException e) {
			// 日誌：記錄報價規格圖讀取失敗。
			log.error("event=quotation_image_read_failed errorType={}", e.getClass().getSimpleName());
			lineService.replyText(replyToken, "讀取圖片失敗，請查看伺服器記錄。");
		}
	}

	/**
	 * 以被引用的圖片找出整個 LINE imageSet，檢查收齊後直接歸檔。
	 */
	//#endregion

	//#region 圖片歸檔

	// 方法：將被引用的完整圖片組直接歸檔。
	private void archive(
		String quotedMessageId,
		String sourceId,
		String folderName,
		String replyToken
	) {
		try {
			ImageArchiveService.ArchiveResult result =
				imageArchiveService.archive(quotedMessageId, sourceId, folderName);
			if (
				result.status()
				== ImageArchiveService.ArchiveStatus.INCOMPLETE_SET
			) {
				lineService.replyText(
					replyToken,
					"圖片抓取未完成：重複"
						+ result.duplicateCount()
						+ "張，最終抓取"
						+ result.imageCount()
						+ "張（預期"
						+ result.expectedCount()
						+ "張）"
				);
			}
			else if (
				result.status()
				== ImageArchiveService.ArchiveStatus.NOT_FOUND
			) {
				lineService.replyText(
					replyToken,
					"找不到被回覆圖片的待處理紀錄，請重新上傳後再試"
				);
			}
			else if (
				result.status()
				== ImageArchiveService.ArchiveStatus.WRONG_SOURCE
			) {
				lineService.replyText(
					replyToken,
					"被回覆圖片不屬於目前群組，無法歸檔"
				);
			}
			else if (
				result.status()
				== ImageArchiveService.ArchiveStatus.ARCHIVED
			) {
				lineService.replyText(
					replyToken,
					"已將"
						+ result.imageCount()
						+ "張圖片存入「"
						+ result.folderName()
						+ "」，流水號"
						+ result.firstSequence()
						+ "至"
						+ result.lastSequence()
				);
			}
		}
		catch (IOException | RuntimeException e) {
			// 日誌：記錄圖片直接歸檔失敗，保留暫存圖片以便重新操作。
			log.error(
				"event=image_archive_write_failed errorType={}",
				e.getClass().getSimpleName());
			lineService.replyText(
				replyToken,
				"圖片歸檔失敗，請稍後重新執行指令"
			);
		}
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
	//#endregion

	//#region 資產查詢

	// 方法：執行 replySearch 方法的處理流程。
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
			"🔍 「" + String.join("、", tags) + "」找到 " + results.size() + " 筆：")
		);

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
	// 方法：執行 replyTagList 方法的處理流程。
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

	//#endregion
}
