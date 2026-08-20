package dev.myudog.assetsmanagerlinebot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 【職責】處理群組文字訊息中與報價無關的通用指令。
 *
 * <p>報價本身只在一對一聊天室進行，由 {@code QuotationLineWorkflowService} 負責；
 * 本服務只負責群組可用的說明與連線檢查，並在群組看到 {@code #報價} 時引導改用私訊，
 * 避免客戶名稱與價格出現在群組。
 *
 * <p>圖片資產收錄、查詢與語音任務屬於文書機器人，不在本產品範圍。
 */
@Service
public class CommandService {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(CommandService.class);

	/**
	 * 標記機器人後只輸入 ping（不分大小寫）才算連線檢查，避免誤判一般對話。
	 */
	private static final Pattern PING_COMMAND =
		Pattern.compile("^ping$", Pattern.CASE_INSENSITIVE);

	private static final String QUOTATION_GROUP_HINT =
		"報價只在一對一聊天室進行。請直接私訊我並輸入 #報價，"
			+ "以免客戶名稱與價格出現在群組。";

	private static final String HELP = """
            🧾 報價機器人用法

            ① 報價：私訊我並輸入「#報價」，再依提示補齊資料。
               我會用 AI 讀規格、產生完整預覽，確認後才產出報價單。
            ② 連線檢查：標記機器人並輸入 ping
               會回覆 pong 與本次事件的延遲毫秒數。

            群組不會顯示任何客戶或價格資料。
            """;

	private final LineStorageService lineService;

	//#endregion

	//#region 建構子

	// 方法：初始化 CommandService。
	public CommandService(LineStorageService lineService) {
		this.lineService = lineService;
	}

	//#endregion

	//#region 方法

	/**
	 * 連線自我檢查：使用者標記機器人並輸入 ping 時，回覆 pong 與本次事件的延遲毫秒數。
	 *
	 * <p>延遲以「LINE 產生事件的時間」到「本服務處理到這一行的時間」相減求得，
	 * 涵蓋 LINE 送出、網路傳輸與本服務排隊處理的總時間。兩端時鐘不同步時可能算出負數，一律夾為 0。
	 *
	 * @param mentionText          去掉自身標記後剩下的文字；機器人未被標記時為 null
	 * @param eventTimestampMillis LINE 事件時間戳記（毫秒）；取不到時為 0 以下
	 * @param replyToken           回覆權杖
	 * @return 已當成 ping 處理並回覆時為 true，呼叫端據此略過後續文字流程
	 */
	// 方法：回覆標記機器人後的 ping 連線檢查。
	public boolean handleMentionPing(String mentionText, long eventTimestampMillis, String replyToken) {
		if (mentionText == null || !PING_COMMAND.matcher(mentionText.trim()).matches()) return false;

		if (eventTimestampMillis <= 0) {
			lineService.replyText(replyToken, "🏓 pong！（本次事件缺少時間戳記，無法計算延遲）");
			return true;
		}

		long latencyMillis = Math.max(0, System.currentTimeMillis() - eventTimestampMillis);
		lineService.replyText(replyToken, "🏓 pong！延遲 " + latencyMillis + " ms");
		return true;
	}

	/**
	 * 文字訊息的總入口。只處理井字號指令，其餘閒聊安靜忽略，不打擾群組。
	 *
	 * @param text            使用者輸入的原始文字
	 * @param quotedMessageId 被引用訊息的 id；本服務不使用，保留以維持呼叫端介面
	 * @param sourceId        群組／聊天室／使用者 id
	 * @param requesterId     發話者 id
	 * @param replyToken      本次事件的回覆權杖
	 */
	// 方法：執行 handleText 方法的處理流程。
	public void handleText(String text, String quotedMessageId, String sourceId, String requesterId, String replyToken) {
		if (text == null || text.isBlank()) return;

		String trimmed = text.trim();

		if (!trimmed.startsWith("#")) return;

		handleCommand(trimmed.substring(1).trim(), replyToken);
	}

	/**
	 * 解析井字號指令並分派。未知指令一律不回應，避免把群組洗版。
	 *
	 * @param body       去掉開頭井字號後的內容
	 * @param replyToken 回覆權杖
	 */
	// 方法：執行 handleCommand 方法的處理流程。
	private void handleCommand(String body, String replyToken) {
		String[] parts = body.split("\\s+");

		switch (parts[0]) {
			case "說明", "help", "?" -> lineService.replyText(replyToken, HELP);
			case "報價" -> replyQuotationHint(replyToken);
			default -> { /* 未知指令不回應 */ }
		}
	}

	// 方法：在群組收到報價指令時引導改用私訊，不在群組揭露任何報價內容。
	private void replyQuotationHint(String replyToken) {
		// 日誌：記錄群組報價引導次數，不含來源或訊息內容。
		log.info("event=quotation_group_hint_replied");
		lineService.replyText(replyToken, QUOTATION_GROUP_HINT);
	}

	//#endregion
}
