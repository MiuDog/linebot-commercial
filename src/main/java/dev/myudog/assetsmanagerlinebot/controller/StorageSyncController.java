package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.AssetFileReconciliationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 提供同步腳本使用的內部單次同步入口。
 */
@RestController
@RequestMapping("/internal/storage")
public class StorageSyncController {

	private final AssetFileReconciliationService reconciliationService;

	@Value("${app.storage.sync-token:}")
	private String syncToken;

	// 方法：建立圖片資料庫同步入口。
	public StorageSyncController(
		AssetFileReconciliationService reconciliationService
	) {
		this.reconciliationService = reconciliationService;
	}

	// 方法：驗證腳本權杖後執行一次完整圖片與資料庫同步。
	@PostMapping("/synchronize")
	public ResponseEntity<SyncResponse> synchronize(
		@RequestHeader(
			value = "X-Sync-Token",
			required = false
		) String providedToken
	) throws IOException {
		if (syncToken == null || syncToken.isBlank()) {
			return ResponseEntity
				.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(SyncResponse.unavailable());
		}
		if (!tokenMatches(providedToken)) {
			return ResponseEntity
				.status(HttpStatus.UNAUTHORIZED)
				.body(SyncResponse.unauthorized());
		}

		AssetFileReconciliationService.SyncResult result =
			reconciliationService.synchronize();
		return ResponseEntity.ok(SyncResponse.completed(result));
	}

	// 方法：以固定時間比較同步權杖，降低由比較時間推測內容的風險。
	private boolean tokenMatches(String providedToken) {
		if (providedToken == null) return false;

		// 外部 API：使用 Java 密碼 API 執行固定時間位元組比較。
		return MessageDigest.isEqual(
			syncToken.getBytes(StandardCharsets.UTF_8),
			providedToken.getBytes(StandardCharsets.UTF_8)
		);
	}

	public record SyncResponse(
		String status,
		int updated,
		int deleted,
		int localized,
		int imported,
		int ambiguous
	) {

		// 方法：建立同步完成回應。
		private static SyncResponse completed(
			AssetFileReconciliationService.SyncResult result
		) {
			return new SyncResponse(
				"completed",
				result.updated(),
				result.deleted(),
				result.localized(),
				result.imported(),
				result.ambiguous()
			);
		}

		// 方法：建立未設定同步權杖回應。
		private static SyncResponse unavailable() {
			return new SyncResponse("unavailable", 0, 0, 0, 0, 0);
		}

		// 方法：建立權杖驗證失敗回應。
		private static SyncResponse unauthorized() {
			return new SyncResponse("unauthorized", 0, 0, 0, 0, 0);
		}
	}
}
