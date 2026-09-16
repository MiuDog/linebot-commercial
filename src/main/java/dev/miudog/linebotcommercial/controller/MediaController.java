package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.domain.Asset;
import dev.miudog.linebotcommercial.service.AssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * LINE 伺服器抓圖用的對外端點。
 *
 * <p>這個端點必然對公網開放（LINE 才抓得到圖），所以路徑用的是每筆資產獨立、
 * 不可預測的 shareToken，而不是流水號或檔名——否則等於把整個資產庫公開。
 *
 * <p><b>事件呼叫鏈：</b>
 * {@code LINE 圖片伺服器 → RequestCorrelationFilter
 * → GET /media/{shareToken} → serve
 * → AssetService.findByShareToken → AssetRepository.findByShareToken
 * → FileStorageService.resolve → FileSystemResource}。
 * 查無索引或實體檔案不可讀時都回傳 404。
 */
@RestController
public class MediaController {

	private static final Logger log = LoggerFactory.getLogger(MediaController.class);

	private final AssetService assetService;

	// 方法：初始化 MediaController。
	public MediaController(AssetService assetService) {
		this.assetService = assetService;
	}

	// 方法：執行 serve 方法的處理流程。
	@GetMapping("/media/{shareToken}")
	public ResponseEntity<Resource> serve(@PathVariable String shareToken) {
		// 步驟 1：依公開權杖查詢資產，查無資料時透過 Spring HTTP API 回覆 404。
		Optional<Asset> found = assetService.findByShareToken(shareToken);

		// 外部呼叫：透過 Spring HTTP 回應 API 表達找不到對應資產。
		if (found.isEmpty()) return ResponseEntity.notFound().build();

		Asset asset = found.get();
		byte[] content;
		try {
			content = assetService.contentOf(asset);
		}
		catch (IOException | RuntimeException exception) {
			// 日誌：記錄不含敏感內容的受控操作結果。
			log.warn("event=media_file_missing assetId={}", asset.id());
			return ResponseEntity.notFound().build();
		}

		// 步驟 3：使用 Spring MediaType API 將資料庫 MIME 型態轉成 HTTP 內容型態。
		MediaType contentType = asset.contentType() == null
			? MediaType.IMAGE_JPEG
			: MediaType.parseMediaType(asset.contentType().split(";")[0].trim());

		// 步驟 4：使用 Spring Resource 與 HTTP API 回傳具私有快取設定的圖片。
		return ResponseEntity.ok()
			.contentType(contentType)
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
			.body(new ByteArrayResource(content));
	}
}
