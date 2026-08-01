package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.repository.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 【職責】資產生命週期的協調者：收錄 → 歸檔 → 查詢。
 *
 * <p>目前 LINE 圖片的暫存與確認歸檔由 {@link ImageArchiveService} 負責；
 * 本類別負責正式資產的查詢、內容讀取，以及保留的直接收錄與掛標籤入口。
 * 上層的 {@link CommandService} 因此不需要知道檔案或 SQL 的實作細節。
 *
 * <p><b>目前事件呼叫鏈：</b>
 * <ul>
 *   <li>{@code #查／#標籤 → CommandService → AssetService → AssetRepository}</li>
 *   <li>{@code GET /media → MediaController → findByShareToken → AssetRepository}</li>
 *   <li>{@code #報價 → CommandService → contentOf → FileStorageService.resolve}</li>
 * </ul>
 *
 * <p>{@link #ingest} 與 {@link #tag} 仍可由 Java 程式直接呼叫，
 * 但目前沒有 Controller 使用；正式 LINE 上傳流程是
 * {@code LineWebhookController → ImageArchiveService.stage}。
 */
@Service
public class AssetService {

	private static final Logger log = LoggerFactory.getLogger(AssetService.class);

	private final AssetRepository repository;
	private final FileStorageService fileStorage;
	private final AssetFileReconciliationService reconciliationService;

	/**
	 * @param repository  資產索引的資料庫存取
	 * @param fileStorage 圖片本體的落地與搬移
	 */
	// 方法：初始化 AssetService。
	public AssetService(
		AssetRepository repository,
		FileStorageService fileStorage,
		AssetFileReconciliationService reconciliationService
	) {
		this.repository = repository;
		this.fileStorage = fileStorage;
		this.reconciliationService = reconciliationService;
	}

	/**
	 * 收錄一張從群組傳來的圖片：先寫檔，再建立索引。圖片依收錄日期落地，
	 * 之後不論怎麼打標籤都不會再搬動。
	 *
	 * <p>LINE 在未收到 200 回應時會重送 webhook，因此以 messageId 做冪等判斷，
	 * 重複事件直接略過，不會產生第二份檔案。
	 *
	 * @param messageId   LINE 訊息 id
	 * @param sourceType  來源型態：group／room／user
	 * @param sourceId    來源 id，決定這筆資產屬於哪個群組
	 * @param uploaderId  上傳者 LINE userId
	 * @param content     圖片內容串流，method 結束時一定會被關閉
	 * @param contentType 原始 MIME 型態，決定副檔名
	 * @return 新收錄的資產；重複事件則為空
	 * @throws IOException 寫入磁碟失敗
	 */
	// 方法：執行 ingest 方法的處理流程。
	@Transactional
	public Optional<Asset> ingest(
		String messageId,
		String sourceType,
		String sourceId,
		String uploaderId,
		InputStream content,
		String contentType
	) throws IOException {
		// 步驟 1：確認 LINE 訊息尚未收錄，避免 Webhook 重送造成重複資產。
		if (repository.findByMessageId(messageId).isPresent()) {
			// 日誌：記錄重複資產略過收錄。
			log.info("event=asset_ingest_skipped reason=duplicate");

			// 外部呼叫：關閉不再使用的圖片輸入串流，立即釋放連線資源。
			content.close();
			return Optional.empty();
		}

		// 步驟 2：保存圖片，並使用 UUID 與 Java 時間 API 建立資產索引資料。
		FileStorageService.StoredFile stored = fileStorage.save(content, contentType);
		Asset asset = new Asset(
			null,
			messageId,
			// 外部呼叫：使用 UUID 產生不可預測的媒體分享權杖。
			UUID.randomUUID().toString().replace("-", ""),
			sourceType,
			sourceId,
			uploaderId,
			stored.relativePath(),
			stored.contentType(),
			stored.size(),
			// 外部呼叫：使用 Java 時間 API 記錄資產收錄時間。
			Instant.now(),
			List.of()
		);

		// 步驟 3：寫入資產索引並重新讀取完整資料。
		Long id = repository.insert(asset);
		reconciliationService.register(id, stored.relativePath());

		// 日誌：記錄資產收錄完成。
		log.info("event=asset_ingested assetId={} fileSize={}", id, stored.size());
		return repository.findByMessageId(messageId);
	}

	/**
	 * 對被引用的那則圖片訊息掛上標籤。
	 *
	 * <p><b>不會搬動檔案。</b> 磁碟只依日期分層，分類完全由標籤承擔，
	 * 因此改標籤時檔案路徑永遠不變，備份與外部引用不會失效；
	 * 同一張圖也可以同時屬於多個資產編號，不需要在磁碟上複製。
	 *
	 * @param quotedMessageId 被引用的圖片訊息 id
	 * @param tags            要掛上的標籤，第一個視為主要資產編號
	 * @return 掛上標籤後的資產；找不到對應圖片或標籤為空時回傳空
	 */
	// 方法：執行 tag 方法的處理流程。
	@Transactional
	public Optional<Asset> tag(String quotedMessageId, List<String> tags) {
		// 步驟 1：確認被引用資產存在且至少提供一個標籤。
		Optional<Asset> found = repository.findByMessageId(quotedMessageId);
		if (found.isEmpty() || tags.isEmpty()) return Optional.empty();

		Asset asset = found.get();

		// 步驟 2：逐一建立標籤並連結至同一筆資產。
		for (String tag : tags) {
			repository.linkTag(asset.id(), repository.upsertTag(tag));
		}
		// 日誌：記錄資產標籤關聯完成。
		log.info("event=asset_tags_linked assetId={} tagCount={}", asset.id(), tags.size());

		return repository.findByMessageId(quotedMessageId);
	}

	/**
	 * 依關鍵字查出資產，多個關鍵字為「同時符合」。
	 *
	 * @param sourceId 查詢範圍，不同群組互不可見
	 * @param tags     關鍵字
	 * @param limit    最多回傳幾筆
	 * @return 符合條件的資產，新到舊排序
	 */
	// 方法：執行 search 方法的處理流程。
	public List<Asset> search(String sourceId, List<String> tags, int limit) {
		return repository.searchByTags(sourceId, tags, limit);
	}

	/**
	 * 列出某群組用過的編號／標籤與各自數量，供盤點使用。
	 *
	 * @param sourceId 統計範圍
	 * @return 標籤到數量的對應
	 */
	// 方法：執行 tagCounts 方法的處理流程。
	public Map<String, Integer> tagCounts(String sourceId) {
		return repository.tagCounts(sourceId);
	}

	/**
	 * 某群組目前收錄的圖片總數。
	 *
	 * @param sourceId 統計範圍
	 * @return 圖片張數
	 */
	// 方法：執行 countBySource 方法的處理流程。
	public int countBySource(String sourceId) {
		return repository.countBySource(sourceId);
	}

	/**
	 * 以對外取圖權杖找出資產，供 {@code MediaController} 回傳檔案。
	 *
	 * @param shareToken 對外權杖
	 * @return 對應的資產；查無則為空
	 */
	// 方法：執行 findByShareToken 方法的處理流程。
	public Optional<Asset> findByShareToken(String shareToken) {
		return repository.findByShareToken(shareToken);
	}

	/**
	 * 以 LINE 訊息 id 找出資產。
	 *
	 * @param messageId LINE 訊息 id
	 * @return 對應的資產；查無則為空
	 */
	// 方法：執行 findByMessageId 方法的處理流程。
	public Optional<Asset> findByMessageId(String messageId) {
		return repository.findByMessageId(messageId);
	}

	/**
	 * 讀出資產圖片的完整位元組，供報價流程送給 AI 辨識。
	 *
	 * <p>刻意一次讀進記憶體而非回傳串流：呼叫端需要把同一份位元組
	 * 先送給模型、之後再貼進 PDF，串流只能讀一次。
	 *
	 * @param asset 目標資產
	 * @return 圖片位元組
	 * @throws IOException 檔案不存在或讀取失敗
	 */
	// 方法：執行 contentOf 方法的處理流程。
	public byte[] contentOf(Asset asset) throws IOException {
		// 外部呼叫：使用 Java NIO 一次讀取圖片內容，供 AI 與 PDF 流程重複使用。
		return Files.readAllBytes(fileStorage.resolve(asset.filePath()));
	}
}
