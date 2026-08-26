package dev.miudog.linebotcommercial.desktop.config;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 驗證桌面設定的必要值與可安全解析格式。
 */
public final class AppConfigurationValidator {

	//#region 欄位

	private static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
	private static final Pattern DATA_SIZE = Pattern.compile("^[1-9][0-9]*(KB|MB|GB)$", Pattern.CASE_INSENSITIVE);

	//#endregion

	//#region 方法

	// 方法：依欄位中繼資料驗證完整設定快照。
	public List<Violation> validate(AppConfiguration configuration) {
		List<Violation> violations = new ArrayList<>();

		// 單一演算法：逐欄檢查必要值，再套用該欄位的格式規則。
		for (AppConfigurationField field : AppConfigurationField.values()) {
			String value = configuration.value(field);

			if (field.required() && value.isBlank()) {
				violations.add(new Violation(field, field.label() + "為必要欄位"));
				continue;
			}

			if (!isValid(field.format(), value)) {
				violations.add(new Violation(field, field.label() + "格式不正確"));
			}
		}

		// Cloudflare 啟用時必須綁定 Tunnel UUID，避免 Token 被誤用到另一個機器人。
		if (Boolean.parseBoolean(configuration.value(AppConfigurationField.CLOUDFLARE_ENABLED))
			&& configuration.value(AppConfigurationField.CLOUDFLARE_TUNNEL_ID).isBlank()) {
			violations.add(new Violation(AppConfigurationField.CLOUDFLARE_TUNNEL_ID, "使用 Cloudflare 時必須填寫綁定 Tunnel ID"));
		}

		return List.copyOf(violations);
	}

	// 方法：依格式種類選擇對應且無副作用的驗證規則。
	private boolean isValid(
		AppConfigurationField.Format format,
		String value
	) {
		return switch (format) {
			case NONE -> true;
			case BOOLEAN -> value.isBlank() || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
			case PORT -> value.isBlank() || isIntegerBetween(value, 1, 65535);
			case POSITIVE_INTEGER -> value.isBlank() || isIntegerBetween(value, 1, Integer.MAX_VALUE);
			case NON_NEGATIVE_INTEGER -> value.isBlank() || isIntegerBetween(value, 0, Integer.MAX_VALUE);
			case NON_NEGATIVE_DECIMAL -> value.isBlank() || isNonNegativeDecimal(value);
			case RATE -> value.isBlank() || isRate(value);
			case DAYS_1_TO_3650 -> value.isBlank() || isIntegerBetween(value, 1, 3650);
			case HTTP_URL -> value.isBlank() || isHttpUrl(value, false);
			case HTTPS_URL -> value.isBlank() || isHttpUrl(value, true);
			case ABSOLUTE_PATH -> value.isBlank() || isAbsolutePath(value);
			case LOG_LEVEL -> value.isBlank() || LOG_LEVELS.contains(value.toUpperCase(Locale.ROOT));
			case DATA_SIZE -> value.isBlank() || DATA_SIZE.matcher(value).matches();
			case UUID -> value.isBlank() || isUuid(value);
			case CLOUDFLARE_PROTOCOL -> value.isBlank()
				|| value.equalsIgnoreCase("auto")
				|| value.equalsIgnoreCase("http2")
				|| value.equalsIgnoreCase("quic");
		};
	}

	// 方法：驗證整數落在允許的閉區間內。
	private boolean isIntegerBetween(
		String value,
		int minimum,
		int maximum
	) {
		try {
			int parsed = Integer.parseInt(value);

			return parsed >= minimum && parsed <= maximum;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	// 方法：驗證十進位數值不是負數。
	private boolean isNonNegativeDecimal(String value) {
		try {
			return new BigDecimal(value).signum() >= 0;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	// 方法：驗證輸入為完整標準 UUID，避免 Java 寬鬆解析接受縮短格式。
	private boolean isUuid(String value) {
		try {
			return UUID.fromString(value).toString().equalsIgnoreCase(value);
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	// 方法：驗證稅率為 0 到 1 之間的十進位數值。
	private boolean isRate(String value) {
		try {
			BigDecimal rate = new BigDecimal(value);

			return rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	// 方法：驗證 HTTP URL 具有主機，並依欄位要求限制 HTTPS。
	private boolean isHttpUrl(
		String value,
		boolean httpsOnly
	) {
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			boolean supportedScheme = httpsOnly
				? "https".equalsIgnoreCase(scheme)
				: "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);

			return supportedScheme && uri.getHost() != null;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	// 方法：驗證檔案路徑為絕對路徑且可由目前平台解析。
	private boolean isAbsolutePath(String value) {
		try {
			return Path.of(value).isAbsolute();
		}
		catch (InvalidPathException exception) {
			return false;
		}
	}

	//#endregion

	/**
	 * 表示可定位至單一設定欄位的驗證錯誤。
	 */
	public record Violation(AppConfigurationField field, String message) {

		// 方法：建立不可包含 null 的設定驗證錯誤。
		public Violation {
			if (field == null) throw new IllegalArgumentException("設定欄位不可為 null");

			if (message == null || message.isBlank()) throw new IllegalArgumentException("錯誤訊息不可為空白");
		}
	}
}

