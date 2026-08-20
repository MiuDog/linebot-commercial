package dev.myudog.assetsmanagerlinebot.service.voice;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.AssetService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** MCP 的「圖片取出」工具；一次完成查詢及 LINE 回覆。 */
@Service
public class VoiceImageRetrievalTool {

	private static final String ACTION = "圖片取出";
	private static final Pattern DEPARTMENT_PATTERN = Pattern.compile(
		"(?:ZD\\d{5}[A-Z]?|ZD-JY\\d{5}|YJ\\d{6})"
	);
	private static final Pattern SHARE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final AssetService assetService;
	private final LineStorageService lineService;
	private final String publicBaseUrl;
	private final int maxResults;

	// 方法：初始化圖片取出工具及 LINE 單次回覆上限。
	public VoiceImageRetrievalTool(
		AssetService assetService,
		LineStorageService lineService,
		@Value("${app.public-base-url:}") String publicBaseUrl,
		@Value("${app.query.max-results:4}") int maxResults
	) {
		this.assetService = assetService;
		this.lineService = lineService;
		this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
		this.maxResults = Math.max(1, Math.min(maxResults, 4));
	}

	// 方法：驗證 AI 收據後，依群組、部門及日期取出圖片並一次回覆 LINE。
	public ToolResult execute(
		VoiceMcpTicketStore.ExecutionContext context,
		VoiceTaskReceipt receipt
	) {
		String validationError = validate(context, receipt);
		if (validationError != null) return replyError(context, validationError);

		String departmentCode = receipt.departmentCode();
		String compactDate = receipt.date().format(COMPACT_DATE);
		List<Asset> assets = assetService.searchByDepartmentAndDate(
			context.sourceId(),
			departmentCode.toLowerCase(java.util.Locale.ROOT),
			compactDate,
			maxResults
		);

		if (assets.isEmpty()) {
			String message = "查無「%s」%s 的圖片。".formatted(
				departmentCode,
				receipt.date().format(DISPLAY_DATE)
			);
			lineService.replyText(context.replyToken(), message);
			return new ToolResult(false, message, 0, departmentCode, receipt.date().toString());
		}

		List<Map<String, Object>> messages = buildMessages(receipt, assets);
		if (messages == null) return replyError(
			context,
			"圖片連結建立失敗，請聯絡管理員檢查公開網址設定。"
		);


		lineService.reply(context.replyToken(), messages);
		String message = (String) messages.get(0).get("text");
		return new ToolResult(false, message, assets.size(), departmentCode, receipt.date().toString());
	}

	// 方法：建立一則使用者摘要與同一組圖片訊息。
	private List<Map<String, Object>> buildMessages(VoiceTaskReceipt receipt, List<Asset> assets) {
		if (publicBaseUrl.isBlank()) return null;

		String message = "已取出「%s」%s 的%d張圖片。".formatted(
			receipt.departmentCode(),
			receipt.date().format(DISPLAY_DATE),
			assets.size()
		);
		List<Map<String, Object>> messages = new ArrayList<>();
		messages.add(LineStorageService.textMessage(message));

		for (Asset asset : assets) {
			if (asset.shareToken() == null || !SHARE_TOKEN_PATTERN.matcher(asset.shareToken()).matches()) return null;

			String url = publicBaseUrl + "/media/" + asset.shareToken();
			messages.add(LineStorageService.imageMessage(url, url));
		}
		return messages;
	}

	// 方法：將模型輸出視為不受信任資料並完整驗證。
	private String validate(
		VoiceMcpTicketStore.ExecutionContext context,
		VoiceTaskReceipt receipt
	) {
		if (context == null || context.sourceId() == null || context.replyToken() == null) return "語音任務已失效，請重新傳送一次。";

		if (receipt == null || !ACTION.equals(receipt.action())) return "目前語音功能只支援圖片取出。";

		if (receipt.departmentCode() == null
			|| !DEPARTMENT_PATTERN.matcher(receipt.departmentCode()).matches()) {
			return "語音中的部門編號格式不正確，請重新說一次。";
		}

		if (receipt.date() == null) return "語音中缺少圖片日期，請重新說一次。";

		return null;
	}

	// 方法：讓工具錯誤同時成為 LINE 使用者訊息與 MCP 結果。
	private ToolResult replyError(
		VoiceMcpTicketStore.ExecutionContext context,
		String message
	) {
		if (context != null && context.replyToken() != null) {
			lineService.replyText(context.replyToken(), message);
		}
		return new ToolResult(true, message, 0, null, null);
	}

	// 方法：移除公開網址結尾斜線。
	private static String trimTrailingSlash(String value) {
		if (value == null) return "";

		return value.replaceFirst("/+$", "");
	}

	public record ToolResult(
		boolean isError,
		String message,
		int imageCount,
		String departmentCode,
		String date
	) {}
}
