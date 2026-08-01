package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 【職責】在設定根目錄下，為單一報價案件建立不可跳脫的安全輸出資料夾。
 *
 * <p><b>預定呼叫鏈：</b>
 * {@code 報價解析完成 → createDirectory(quotationName)
 * → {QUOTATION_ROOT_PATH}/報價單/{安全案件名稱} → Excel／PDF 輸出器}。
 *
 * <p>本服務目前只由啟動狀態檢查與測試使用，尚未接入
 * {@link QuotationService} 的正式 {@code #報價} 事件。
 */
@Service
public class QuotationOutputDirectoryService {

	private static final String QUOTATION_FOLDER = "報價單";
	private static final int MAX_NAME_LENGTH = 100;
	private static final Pattern INVALID_WINDOWS_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
	private static final Pattern REPEATED_DOTS = Pattern.compile("\\.{2,}");
	private static final Pattern TRAILING_DOTS_OR_SPACES = Pattern.compile("[. ]+$");
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
		"CON",
		"PRN",
		"AUX",
		"NUL",
		"COM1",
		"COM2",
		"COM3",
		"COM4",
		"COM5",
		"COM6",
		"COM7",
		"COM8",
		"COM9",
		"LPT1",
		"LPT2",
		"LPT3",
		"LPT4",
		"LPT5",
		"LPT6",
		"LPT7",
		"LPT8",
		"LPT9"
	);

	private final Path configuredRoot;

	// 方法：初始化 QuotationOutputDirectoryService。
	public QuotationOutputDirectoryService(@Value("${app.quotation.root-path:}") String rootPath) {
		// 外部呼叫：使用 Java NIO 將報價根目錄正規化，避免不同路徑表示造成誤判。
		this.configuredRoot = rootPath == null || rootPath.isBlank()
			? null
			: Paths.get(rootPath).toAbsolutePath().normalize();
	}

	// 方法：執行 isConfigured 方法的處理流程。
	public boolean isConfigured() {
		return configuredRoot != null;
	}

	// 方法：執行 createDirectory 方法的處理流程。
	public Path createDirectory(String quotationName) throws IOException {
		if (!isConfigured()) {
			throw new IllegalStateException("尚未設定報價單根目錄");
		}

		// 步驟 1：清理使用者提供的名稱並限制輸出只能位於報價單根目錄下一層。
		String safeName = sanitizeName(quotationName);
		Path quotationRoot = configuredRoot.resolve(QUOTATION_FOLDER).normalize();
		Path target = quotationRoot.resolve(safeName).normalize();
		if (!target.startsWith(quotationRoot) || !quotationRoot.equals(target.getParent())) {
			throw new IllegalArgumentException("報價單名稱形成了不安全的輸出路徑");
		}

		// 步驟 2：使用 Java NIO 建立通過安全檢查的報價單目錄。
		Files.createDirectories(target);
		return target;
	}

	// 方法：執行 sanitizeName 方法的處理流程。
	private String sanitizeName(String quotationName) {
		if (quotationName == null || quotationName.isBlank()) {
			throw new IllegalArgumentException("報價單名稱不可空白");
		}

		String safeName = INVALID_WINDOWS_CHARACTERS.matcher(quotationName.trim()).replaceAll("_");
		safeName = REPEATED_DOTS.matcher(safeName).replaceAll("_");
		safeName = TRAILING_DOTS_OR_SPACES.matcher(safeName).replaceAll("");
		safeName = safeName.codePoints()
			.limit(MAX_NAME_LENGTH)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();

		if (safeName.isBlank()) {
			throw new IllegalArgumentException("報價單名稱沒有可用的資料夾字元");
		}
		String nameBeforeFirstDot = safeName.split("\\.", 2)[0];
		if (WINDOWS_RESERVED_NAMES.contains(nameBeforeFirstDot.toUpperCase(Locale.ROOT))) {
			safeName = "_" + safeName;
		}
		return safeName;
	}
}
