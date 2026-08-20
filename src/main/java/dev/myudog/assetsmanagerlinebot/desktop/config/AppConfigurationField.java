package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 定義桌面設定欄位的環境變數、Spring 屬性、群組與安全分類。
 */
public enum AppConfigurationField {

	SERVER_PORT("SERVER_PORT", "server.port", "本機服務埠", Group.SYSTEM, false, false, "8088", Format.PORT),
	SYSTEM_ROOT_PATH("SYSTEM_ROOT_PATH", "app.system.root", "資料根目錄", Group.SYSTEM, false, true, "", Format.ABSOLUTE_PATH),
	PUBLIC_BASE_URL("PUBLIC_BASE_URL", "app.public-base-url", "公開 HTTPS 網址", Group.SYSTEM, false, false, "", Format.HTTPS_URL),
	QUERY_MAX_RESULTS("QUERY_MAX_RESULTS", "app.query.max-results", "單次圖片上限", Group.SYSTEM, false, false, "4", Format.POSITIVE_INTEGER),
	LINE_BOT_CHANNEL_TOKEN("LINE_BOT_CHANNEL_TOKEN", "line.bot.channel-token", "LINE Channel Token", Group.LINE, true, true, "", Format.NONE),
	LINE_BOT_CHANNEL_SECRET("LINE_BOT_CHANNEL_SECRET", "line.bot.channel-secret", "LINE Channel Secret", Group.LINE, true, true, "", Format.NONE),
	ASSETS_SYNC_ENABLED("ASSETS_SYNC_ENABLED", "app.storage.sync-enabled", "啟用資產同步", Group.LINE, false, false, "false", Format.BOOLEAN),
	ASSETS_SYNC_INTERVAL_MS("ASSETS_SYNC_INTERVAL_MS", "app.storage.sync-interval-ms", "資產同步週期", Group.LINE, false, false, "30000", Format.POSITIVE_INTEGER),
	ASSETS_SYNC_TOKEN("ASSETS_SYNC_TOKEN", "app.storage.sync-token", "資產同步 Token", Group.LINE, true, false, "", Format.NONE),
	AI_API_URL("AI_API_URL", "app.ai.api-url", "AI API 網址", Group.AI, false, false, "https://api.openai.com/v1", Format.HTTP_URL),
	AI_API_KEY("AI_API_KEY", "app.ai.api-key", "AI API Key", Group.AI, true, false, "", Format.NONE),
	AI_MODEL("AI_MODEL", "app.ai.model", "AI Model", Group.AI, false, false, "", Format.NONE),
	AI_REQUIRED_FIELDS("AI_REQUIRED_FIELDS", "app.ai.required-fields", "AI 必要欄位", Group.AI, false, false, "", Format.NONE),
	AI_TIMEOUT_SECONDS("AI_TIMEOUT_SECONDS", "app.ai.timeout-seconds", "AI Timeout 秒數", Group.AI, false, false, "60", Format.POSITIVE_INTEGER),
	AI_PRICE_CURRENCY("AI_PRICE_CURRENCY", "app.ai.pricing.currency", "AI 計價幣別", Group.AI, false, false, "USD", Format.NONE),
	AI_INPUT_RATE_PER_MILLION("AI_INPUT_RATE_PER_MILLION", "app.ai.pricing.input-per-million", "AI 輸入單價", Group.AI, false, false, "", Format.NON_NEGATIVE_DECIMAL),
	AI_CACHED_INPUT_RATE_PER_MILLION("AI_CACHED_INPUT_RATE_PER_MILLION", "app.ai.pricing.cached-input-per-million", "AI 快取輸入單價", Group.AI, false, false, "", Format.NON_NEGATIVE_DECIMAL),
	AI_OUTPUT_RATE_PER_MILLION("AI_OUTPUT_RATE_PER_MILLION", "app.ai.pricing.output-per-million", "AI 輸出單價", Group.AI, false, false, "", Format.NON_NEGATIVE_DECIMAL),
	VOICE_COMMANDS_ENABLED("VOICE_COMMANDS_ENABLED", "app.voice.enabled", "啟用語音命令", Group.VOICE, false, false, "false", Format.BOOLEAN),
	VOICE_MCP_SERVER_URL("VOICE_MCP_SERVER_URL", "app.voice.mcp-server-url", "語音 MCP 網址", Group.VOICE, false, false, "", Format.HTTP_URL),
	VOICE_MCP_AUTH_TOKEN("VOICE_MCP_AUTH_TOKEN", "app.voice.mcp-auth-token", "語音 MCP Token", Group.VOICE, true, false, "", Format.NONE),
	QUOTATION_POSTBACK_SECRET("QUOTATION_POSTBACK_SECRET", "QUOTATION_POSTBACK_SECRET", "報價 Postback Secret", Group.QUOTATION, true, false, "", Format.NONE),
	QUOTATION_IMAGE_LINK_SECRET("QUOTATION_IMAGE_LINK_SECRET", "QUOTATION_IMAGE_LINK_SECRET", "報價圖片連結 Secret", Group.QUOTATION, true, false, "", Format.NONE),
	QUOTATION_DELIVERY_LINK_TTL_HOURS("QUOTATION_DELIVERY_LINK_TTL_HOURS", "app.quotation.delivery-link-ttl-hours", "交付連結有效小時", Group.QUOTATION, false, false, "168", Format.POSITIVE_INTEGER),
	QUOTATION_PDF_TIMEOUT_SECONDS("QUOTATION_PDF_TIMEOUT_SECONDS", "app.quotation.pdf-timeout-seconds", "PDF Timeout 秒數", Group.QUOTATION, false, false, "90", Format.POSITIVE_INTEGER),
	QUOTATION_GENERATION_QUEUE_CAPACITY("QUOTATION_GENERATION_QUEUE_CAPACITY", "app.quotation.generation-queue-capacity", "報價佇列容量", Group.QUOTATION, false, false, "20", Format.POSITIVE_INTEGER),
	QUOTATION_GENERATION_LEASE_SECONDS("QUOTATION_GENERATION_LEASE_SECONDS", "app.quotation.generation-lease-seconds", "報價租約秒數", Group.QUOTATION, false, false, "120", Format.POSITIVE_INTEGER),
	QUOTATION_GENERATION_BATCH_SIZE("QUOTATION_GENERATION_BATCH_SIZE", "app.quotation.generation-batch-size", "報價批次數量", Group.QUOTATION, false, false, "20", Format.POSITIVE_INTEGER),
	QUOTATION_GENERATION_POLL_INTERVAL_MS("QUOTATION_GENERATION_POLL_INTERVAL_MS", "app.quotation.generation-poll-interval-ms", "報價輪詢週期", Group.QUOTATION, false, false, "5000", Format.POSITIVE_INTEGER),
	QUOTATION_GENERATION_POLL_INITIAL_DELAY_MS("QUOTATION_GENERATION_POLL_INITIAL_DELAY_MS", "app.quotation.generation-poll-initial-delay-ms", "報價首次輪詢延遲", Group.QUOTATION, false, false, "5000", Format.NON_NEGATIVE_INTEGER),
	QUOTATION_GENERATION_WORKER_ENABLED("QUOTATION_GENERATION_WORKER_ENABLED", "app.quotation.generation-worker-enabled", "啟用報價背景工作", Group.QUOTATION, false, false, "true", Format.BOOLEAN),
	METHOD_TRACING_ENABLED("METHOD_TRACING_ENABLED", "app.observability.method-tracing-enabled", "啟用方法追蹤", Group.LOG, false, false, "true", Format.BOOLEAN),
	LOG_LEVEL_ROOT("LOG_LEVEL_ROOT", "logging.level.root", "Log Level", Group.LOG, false, false, "INFO", Format.LOG_LEVEL),
	LOG_MAX_FILE_SIZE("LOG_MAX_FILE_SIZE", "LOG_MAX_FILE_SIZE", "Log 單檔大小", Group.LOG, false, false, "20MB", Format.DATA_SIZE),
	LOG_MAX_HISTORY("LOG_MAX_HISTORY", "LOG_MAX_HISTORY", "Log 保留天數", Group.LOG, false, false, "30", Format.POSITIVE_INTEGER),
	LOG_TOTAL_SIZE_CAP("LOG_TOTAL_SIZE_CAP", "LOG_TOTAL_SIZE_CAP", "Log 總容量", Group.LOG, false, false, "2GB", Format.DATA_SIZE),
	RESOURCE_LOG_INTERVAL_MS("RESOURCE_LOG_INTERVAL_MS", "app.observability.resource-interval-ms", "資源記錄週期", Group.LOG, false, false, "60000", Format.POSITIVE_INTEGER),
	NGROK_ENABLED("NGROK_ENABLED", "app.desktop.ngrok.enabled", "使用 ngrok", Group.NGROK, false, false, "false", Format.BOOLEAN),
	NGROK_AGENT_PATH("NGROK_AGENT_PATH", "app.desktop.ngrok.agent-path", "ngrok 執行檔", Group.NGROK, false, false, "", Format.ABSOLUTE_PATH),
	NGROK_AUTHTOKEN("NGROK_AUTHTOKEN", "app.desktop.ngrok.authtoken", "ngrok Authtoken", Group.NGROK, true, false, "", Format.NONE);

	//#region 欄位

	private final String environmentKey;
	private final String propertyKey;
	private final String label;
	private final Group group;
	private final boolean secret;
	private final boolean required;
	private final String defaultValue;
	private final Format format;

	//#endregion

	//#region 建構子

	// 方法：建立單一設定欄位的完整中繼資料。
	AppConfigurationField(
		String environmentKey,
		String propertyKey,
		String label,
		Group group,
		boolean secret,
		boolean required,
		String defaultValue,
		Format format
	) {
		this.environmentKey = environmentKey;
		this.propertyKey = propertyKey;
		this.label = label;
		this.group = group;
		this.secret = secret;
		this.required = required;
		this.defaultValue = defaultValue;
		this.format = format;
	}

	//#endregion

	//#region 方法

	// 方法：取得對應的環境變數名稱。
	public String environmentKey() {
		return environmentKey;
	}

	// 方法：取得啟動 Spring 時使用的屬性名稱。
	public String propertyKey() {
		return propertyKey;
	}

	// 方法：取得設定精靈顯示的中文名稱。
	public String label() {
		return label;
	}

	// 方法：取得設定精靈的欄位群組。
	public Group group() {
		return group;
	}

	// 方法：判斷欄位是否必須加密保存。
	public boolean secret() {
		return secret;
	}

	// 方法：判斷欄位是否為啟動必要值。
	public boolean required() {
		return required;
	}

	// 方法：取得欄位的安全預設值。
	public String defaultValue() {
		return defaultValue;
	}

	// 方法：取得欄位格式規則。
	public Format format() {
		return format;
	}

	// 方法：取得所有需要加密保存的欄位。
	public static Set<AppConfigurationField> secretFields() {
		return Arrays.stream(values())
			.filter(AppConfigurationField::secret)
			.collect(Collectors.toUnmodifiableSet());
	}

	// 方法：依環境變數名稱尋找已知設定欄位，讓新版程式安全忽略未知欄位。
	public static Optional<AppConfigurationField> fromEnvironmentKey(String environmentKey) {
		return Arrays.stream(values())
			.filter(field -> field.environmentKey.equals(environmentKey))
			.findFirst();
	}

	//#endregion

	/**
	 * 設定精靈的頁面群組。
	 */
	public enum Group {
		SYSTEM,
		LINE,
		AI,
		VOICE,
		QUOTATION,
		LOG,
		NGROK
	}

	/**
	 * 設定值的格式驗證種類。
	 */
	public enum Format {
		NONE,
		BOOLEAN,
		PORT,
		POSITIVE_INTEGER,
		NON_NEGATIVE_INTEGER,
		NON_NEGATIVE_DECIMAL,
		HTTP_URL,
		HTTPS_URL,
		ABSOLUTE_PATH,
		LOG_LEVEL,
		DATA_SIZE
	}
}
