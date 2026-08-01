package dev.myudog.assetsmanagerlinebot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【職責】圖片本體在磁碟上的落地與路徑安全。
 *
 * <p>對外只回傳「相對於資產庫根目錄、以 / 分隔」的路徑，資料庫也只存這個，
 * 因此整個資產庫連同 assets.db 可以整包搬到別台機器而不失效。
 *
 * <p><b>實體結構</b>：{@code {根目錄}/{yyyyMMdd}/{yyyyMMdd-HHmmssSSS}.jpg}
 *
 * <pre>
 * F:\資產庫\
 * ├─ assets.db
 * ├─ 20260727\
 * │  ├─ 20260727-224530123.jpg
 * │  └─ 20260727-224612456.jpg
 * └─ 20260728\
 *    └─ 20260728-091502001.jpg
 * </pre>
 *
 * <p><b>磁碟上只有日期，沒有資產編號。</b> 分類完全交給資料庫的標籤，
 * 檔案落地之後就不再搬動——這是「指標法」的核心：磁碟負責保存，
 * 資料庫負責組織，兩者職責不重疊。
 *
 * <p>好處是使用者改標籤時檔案路徑永遠不變，備份與外部引用不會失效；
 * 而且同一張圖可以同時屬於多個編號，不必在磁碟上複製或做連結。
 *
 * <p>根目錄由 {@code ASSETS_ROOT} 環境變數指定，可以是任意路徑
 * （例如 {@code F:/資產庫}），與專案目錄無關。
 *
 * <p><b>上游呼叫鏈：</b>
 * <ul>
 *   <li>{@code image webhook → ImageArchiveService.stage → savePending}</li>
 *   <li>{@code 資料夾代碼 → ImageArchiveService.archive → archivePending／delete}</li>
 *   <li>{@code GET /media → MediaController → resolve}</li>
 *   <li>{@code #報價 → AssetService.contentOf → resolve}</li>
 * </ul>
 * 本類別只處理位元組與安全路徑，不建立資產資料列或決定 LINE 回覆。
 */
@Service
public class FileStorageService {

	/** 日期資料夾，例如 20260727。 */
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

	/** 檔名時間戳，含毫秒以降低同秒多張照片的碰撞機率。 */
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

	/** 時區寫死台北：跟著容器時區跑的話，日期資料夾會在不同機器上跳動。 */
	private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

	private static final Pattern ARCHIVE_FILE = Pattern.compile("^\\d{8}-(\\d+)\\.[^.]+$");

	private final Path root;

	/**
	 * @param assetsRoot 資產庫根目錄，由 {@code ASSETS_ROOT} 指定，
	 *                   例如 {@code F:/資產庫} 或 {@code /data/assets}
	 */
	//#region 初始化與儲存

	// 方法：初始化 FileStorageService。
	public FileStorageService(@Value("${app.storage.root}") String assetsRoot) {
		// 外部呼叫：使用 Java NIO 將設定路徑轉成安全且一致的絕對路徑。
		this.root = Paths.get(assetsRoot).toAbsolutePath().normalize();
	}

	/**
	 * 落地結果。
	 *
	 * @param relativePath 相對於資產庫根目錄、以 / 分隔的路徑
	 * @param size         實際寫入的位元組數
	 * @param contentType  原始 MIME 型態
	 */
	public record StoredFile(String relativePath, long size, String contentType) {}

	/**
	 * 將 LINE 下載回來的串流寫入當天的日期資料夾，缺少的目錄會自動建立。
	 *
	 * <p>檔名採純時間戳，直接看資料夾就能依時間排序。毫秒仍碰撞時
	 * （同一毫秒兩張圖）由 {@link #uniquePath} 補上流水序號。
	 *
	 * @param inputStream 圖片內容，method 結束時一定會被關閉
	 * @param contentType 原始 MIME 型態，決定副檔名
	 * @return 落地結果，含相對路徑
	 * @throws IOException 建立目錄或寫檔失敗
	 */
	// 方法：執行 save 方法的處理流程。
	public StoredFile save(InputStream inputStream, String contentType) throws IOException {
		// 步驟 1：使用 Java 時間 API 取得台北日期，決定正式資產資料夾與檔名。
		ZonedDateTime now = ZonedDateTime.now(ZONE);
		String relativeDir = DAY.format(now);

		// 步驟 2：使用 Java NIO 建立日期目錄並產生不重複的目標路徑。
		Path directory = resolve(relativeDir);

		// 外部呼叫：使用 Java NIO 建立當日正式資產目錄。
		Files.createDirectories(directory);

		// 步驟 3：使用 Java NIO 將圖片串流寫入目標，並於完成後關閉串流。
		Path target = uniquePath(directory, STAMP.format(now), extensionFor(contentType));
		try (inputStream) {
			// 外部呼叫：使用 Java NIO 寫入圖片並回傳實際位元組數。
			long size = Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
			return new StoredFile(relativeDir + "/" + target.getFileName(), size, contentType);
		}
	}

	/**
	 * 暫存尚未經使用者確認的圖片。暫存檔不會出現在正式日期資料夾。
	 */
	// 方法：執行 savePending 方法的處理流程。
	public StoredFile savePending(InputStream inputStream, String contentType) throws IOException {
		// 步驟 1：使用 Java NIO 建立待確認目錄。
		Path directory = resolve(".pending");

		// 外部呼叫：使用 Java NIO 建立待確認圖片目錄。
		Files.createDirectories(directory);

		// 步驟 2：使用 UUID 與 Java NIO 產生不重複路徑並寫入圖片串流。
		Path target = uniquePath(directory, UUID.randomUUID().toString(), extensionFor(contentType));
		try (inputStream) {
			// 外部呼叫：使用 Java NIO 寫入待確認圖片並回傳實際位元組數。
			long size = Files.copy(inputStream, target);
			return new StoredFile(relativePath(target), size, contentType);
		}
	}

	/**
	 * 將暫存圖片複製到與完整指令相同名稱的資料夾。
	 */
	// 方法：執行 archivePending 方法的處理流程。
	public StoredFile archivePending(
		String pendingPath,
		String folderName,
		String contentType
	) throws IOException {
		// 步驟 1：使用 Java NIO 驗證待確認圖片確實存在於允許的路徑。
		Path source = resolve(pendingPath);
		if (!pendingPath.startsWith(".pending/") || !Files.isRegularFile(source)) {
			throw new IOException("找不到待確認圖片: " + pendingPath);
		}
		return archiveFile(source, folderName, contentType);
	}

	// 方法：將已歸檔圖片再次複製到指定代碼資料夾。
	public StoredFile archiveExisting(
		String sourcePath,
		String folderName,
		String contentType
	) throws IOException {
		Path source = resolve(sourcePath);
		if (!Files.isRegularFile(source)) {
			throw new IOException("找不到已歸檔圖片: " + sourcePath);
		}
		return archiveFile(source, folderName, contentType);
	}

	// 方法：將來源圖片複製為指定資料夾的下一個流水號。
	private StoredFile archiveFile(
		Path source,
		String folderName,
		String contentType
	) throws IOException {
		// 步驟 1：使用 Java NIO 建立完整代碼對應的正式資料夾。
		Path directory = resolve(folderName);

		// 外部呼叫：使用 Java NIO 建立指定代碼的正式歸檔目錄。
		Files.createDirectories(directory);

		// 步驟 2：依該資料夾既有檔案決定下一個獨立流水號。
		long sequence = nextArchiveSequence(directory);
		int width = Math.max(2, Long.toString(sequence).length());
		String date = DAY.format(ZonedDateTime.now(ZONE));
		String baseName = date + "-" + String.format("%0" + width + "d", sequence);
		Path target = directory.resolve(baseName + extensionFor(contentType));

		// 外部呼叫：使用 Java NIO 將來源圖片複製至正式歸檔位置。
		Files.copy(source, target);
		return new StoredFile(relativePath(target), Files.size(target), contentType);
	}

	//#endregion

	//#region 路徑管理

	// 方法：執行 delete 方法的處理流程。
	public void delete(String relativePath) throws IOException {
		// 外部呼叫：使用 Java NIO 刪除檔案；檔案不存在時視為已完成。
		Files.deleteIfExists(resolve(relativePath));
	}

	// 方法：執行 relativePath 方法的處理流程。
	private String relativePath(Path path) {
		return root.relativize(path).toString().replace('\\', '/');
	}

	/**
	 * 將相對路徑還原成實體路徑。
	 *
	 * <p>正規化後會檢查結果仍位於資產庫根目錄之內。使用者輸入現在完全不會
	 * 進入路徑，這道檢查是針對資料庫內容的防線——即使 assets.db 被竄改，
	 * 也讀不到資產庫以外的檔案。
	 *
	 * @param relativePath 相對路徑
	 * @return 對應的絕對路徑
	 * @throws IllegalArgumentException 路徑逃出資產庫根目錄時
	 */
	// 方法：執行 resolve 方法的處理流程。
	public Path resolve(String relativePath) {
		Path resolved = root.resolve(relativePath).normalize();
		if (!resolved.startsWith(root)) {
			throw new IllegalArgumentException("路徑逃逸資產庫根目錄: " + relativePath);
		}
		return resolved;
	}

	/**
	 * 資產庫根目錄的絕對路徑，供啟動時記錄與疑難排解使用。
	 *
	 * @return 根目錄
	 */
	// 方法：執行 root 方法的處理流程。
	public Path root() {
		return root;
	}

	/**
	 * 在目錄下找出一個尚未被占用的檔名。
	 *
	 * <p>時間戳已含毫秒，正常情況第一次就會命中；同一毫秒收到兩張圖時
	 * 依序嘗試 {@code -1}、{@code -2}，避免後者覆蓋前者。
	 *
	 * @param directory 目標目錄
	 * @param baseName  檔名主體（時間戳）
	 * @param extension 含點的副檔名
	 * @return 尚未存在的檔案路徑
	 */
	//#endregion

	//#region 檔名與格式

	// 方法：執行 uniquePath 方法的處理流程。
	private Path uniquePath(Path directory, String baseName, String extension) {
		Path candidate = directory.resolve(baseName + extension);
		int sequence = 1;

		// 演算法：使用 Java NIO 逐號探測檔名，直到找到尚未存在的路徑。
		while (Files.exists(candidate)) {
			candidate = directory.resolve(baseName + "-" + sequence + extension);
			sequence++;
		}
		return candidate;
	}

	// 方法：讀取指定資料夾現有歸檔檔名，取得下一個流水號。
	private static long nextArchiveSequence(Path directory) throws IOException {
		// 外部呼叫：使用 Java NIO 掃描資料夾內所有正式歸檔檔案。
		try (var files = Files.list(directory)) {
			return files
				.filter(Files::isRegularFile)
				.map(path -> ARCHIVE_FILE.matcher(path.getFileName().toString()))
				.filter(Matcher::matches)
				.mapToLong(matcher -> Long.parseLong(matcher.group(1)))
				.max()
				.orElse(0L) + 1L;
		}
	}

	/**
	 * 由 MIME 型態決定副檔名，未知型態一律當 JPEG——LINE 傳來的圖片絕大多數是 JPEG。
	 *
	 * @param contentType MIME 型態，可為 null
	 * @return 含點的副檔名
	 */
	// 方法：執行 extensionFor 方法的處理流程。
	private static String extensionFor(String contentType) {
		if (contentType == null) return ".jpg";

		return switch (contentType.split(";")[0].trim().toLowerCase()) {
			case "image/png" -> ".png";
			case "image/gif" -> ".gif";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}

	//#endregion
}
