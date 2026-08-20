package dev.miudog.linebotcommercial.service.quotation;

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
 * → {共同系統根目錄}/報價單/{安全案件名稱} → Excel／PDF 輸出器}。
 *
 * <p>本服務已供本機管理頁的 Excel 產生器使用；正式 LINE {@code #報價} 事件仍待草稿流程接入。
 */
@Service
public class QuotationOutputDirectoryService {

	private static final String QUOTATION_FOLDER = "報價單";
	private static final int MAX_NAME_LENGTH = 100;
	private static final Pattern INVALID_WINDOWS_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
	private static final Pattern REPEATED_DOTS = Pattern.compile("\\.{2,}");
	private static final Pattern TRAILING_DOTS_OR_SPACES = Pattern.compile("[. ]+$");
	private static final Pattern FORMAL_FOLDER = Pattern.compile("\\d{8}-\\d{2,}");
	private static final Set<String> FORMAL_EXTENSIONS = Set.of(".xlsx", ".pdf");
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

		// 步驟 2：建立父目錄後解析實體路徑，拒絕報價根目錄或案件目錄 symlink。
		Files.createDirectories(configuredRoot);
		if (Files.isSymbolicLink(quotationRoot)) {
			throw new IllegalArgumentException("報價單根目錄形成了不安全的輸出路徑");
		}

		Files.createDirectories(quotationRoot);
		Path realConfiguredRoot = configuredRoot.toRealPath();
		Path realQuotationRoot = quotationRoot.toRealPath();
		if (!realConfiguredRoot.equals(realQuotationRoot.getParent())) {
			throw new IllegalArgumentException("報價單根目錄形成了不安全的輸出路徑");
		}
		if (Files.isSymbolicLink(target)) {
			throw new IllegalArgumentException("報價單名稱形成了不安全的輸出路徑");
		}

		// 步驟 3：使用 Java NIO 建立通過安全檢查的報價單目錄並再次確認實體位置。
		Files.createDirectories(target);
		Path realTarget = target.toRealPath();
		if (!realQuotationRoot.equals(realTarget.getParent())) {
			throw new IllegalArgumentException("報價單名稱形成了不安全的輸出路徑");
		}

		return realTarget;
	}

	// 方法：在已配置的每日流水號資料夾中解析正式 XLSX 或 PDF 檔案路徑。
	public Path resolveFormalFile(
		String folderName,
		String fileBaseName,
		String extension
	) throws IOException {
		if (!isConfigured()) throw new IllegalStateException("尚未設定報價單根目錄");

		if (folderName == null || !FORMAL_FOLDER.matcher(folderName).matches()) {
			throw new IllegalArgumentException("正式報價資料夾必須是 YYYYMMDD-至少二位流水號");
		}
		if (!FORMAL_EXTENSIONS.contains(extension)) {
			throw new IllegalArgumentException("正式報價副檔名只允許 .xlsx 或 .pdf");
		}

		Path directory = createDirectory(folderName);
		String safeBaseName = sanitizeName(fileBaseName);
		Path target = directory.resolve(safeBaseName + extension).normalize();
		if (!target.startsWith(directory) || !directory.equals(target.getParent())) {
			throw new IllegalArgumentException("正式報價檔名形成了不安全的輸出路徑");
		}
		if (Files.isSymbolicLink(target)) {
			throw new IllegalArgumentException("正式報價檔名形成了不安全的輸出路徑");
		}

		return target;
	}

	// 方法：只解析每日流水號正式資料夾，不在預檢完成前建立實體目錄。
	public Path resolveFormalDirectory(String folderName) {
		if (!isConfigured()) throw new IllegalStateException("尚未設定報價單根目錄");

		if (folderName == null || !FORMAL_FOLDER.matcher(folderName).matches()) {
			throw new IllegalArgumentException("正式報價資料夾必須是 YYYYMMDD-至少二位流水號");
		}
		Path quotationRoot = configuredRoot.resolve(QUOTATION_FOLDER).normalize();
		Path directory = quotationRoot.resolve(folderName).normalize();
		if (!directory.startsWith(quotationRoot) || !quotationRoot.equals(directory.getParent())) {
			throw new IllegalArgumentException("正式報價資料夾形成了不安全的輸出路徑");
		}
		return directory;
	}

	// 方法：將正式報價檔案轉成相對於共同系統根目錄的安全資料庫定位字串。
	public String relativeLocator(Path path) {
		if (!isConfigured() || path == null) throw new IllegalStateException("尚未設定報價單根目錄");

		Path normalized = path.toAbsolutePath().normalize();
		Path quotationRoot = configuredRoot.resolve(QUOTATION_FOLDER).normalize();
		if (!normalized.startsWith(quotationRoot)) {
			throw new IllegalArgumentException("正式報價檔案不在報價單資料夾內");
		}
		return configuredRoot.relativize(normalized).toString().replace('\\', '/');
	}

	// 方法：解析資料庫中的正式報價相對定位，供圖片預覽及下載服務安全讀取。
	public Path resolveLocator(String locator) {
		if (!isConfigured() || locator == null || locator.isBlank()) {
			throw new IllegalStateException("正式報價檔案定位不可留空");
		}
		Path resolved = configuredRoot.resolve(locator).normalize();
		Path quotationRoot = configuredRoot.resolve(QUOTATION_FOLDER).normalize();
		if (!resolved.startsWith(quotationRoot)) {
			throw new IllegalArgumentException("正式報價檔案定位超出報價單資料夾");
		}
		try {
			Path realRoot = quotationRoot.toRealPath();
			Path realFile = resolved.toRealPath();
			if (!realFile.startsWith(realRoot)) {
				throw new IllegalArgumentException("正式報價檔案定位超出報價單資料夾");
			}

			return realFile;
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("正式報價檔案不存在或無法安全解析", exception);
		}
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
