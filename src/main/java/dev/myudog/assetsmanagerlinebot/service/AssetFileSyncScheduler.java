package dev.myudog.assetsmanagerlinebot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 【職責】在啟動完成及固定間隔觸發可重跑的資產檔案同步。
 */
@Component
@ConditionalOnProperty(
	prefix = "app.storage",
	name = "sync-enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class AssetFileSyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(AssetFileSyncScheduler.class);

	private final AssetFileReconciliationService reconciliationService;

	// 方法：初始化資產檔案同步排程器。
	public AssetFileSyncScheduler(AssetFileReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	// 方法：應用程式啟動完成後立即建立既有資產的檔案身分資料。
	@EventListener(ApplicationReadyEvent.class)
	public void synchronizeAfterStartup() {
		synchronizeSafely();
	}

	// 方法：依設定間隔重新檢查 Explorer 造成的檔案異動。
	@Scheduled(
		fixedDelayString = "${app.storage.sync-interval-ms:30000}",
		initialDelayString = "${app.storage.sync-interval-ms:30000}"
	)
	public void synchronizePeriodically() {
		synchronizeSafely();
	}

	// 方法：執行同步並將失敗隔離在本次排程，避免停止後續檢查。
	private void synchronizeSafely() {
		try {
			AssetFileReconciliationService.SyncResult result =
				reconciliationService.synchronize();
			if (
				result.updated() == 0
				&& result.deleted() == 0
				&& result.localized() == 0
				&& result.imported() == 0
				&& result.ambiguous() == 0
			) return;

			// 日誌：記錄同步實際修正數量，不輸出檔名或使用者資料。
			log.info(
				"event=asset_file_sync_completed updated={} deleted={} localized={} imported={} ambiguous={}",
				result.updated(),
				result.deleted(),
				result.localized(),
				result.imported(),
				result.ambiguous()
			);
		}
		catch (IOException | RuntimeException e) {
			// 日誌：記錄同步失敗類型，下一輪排程仍會再次嘗試。
			log.error(
				"event=asset_file_sync_failed errorType={}",
				e.getClass().getSimpleName()
			);
		}
	}
}
