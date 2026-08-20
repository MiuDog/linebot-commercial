package dev.miudog.linebotcommercial.service;

import dev.miudog.linebotcommercial.domain.Asset;
import dev.miudog.linebotcommercial.service.quotation.QuotationOutputDirectoryService;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 依受信任的資料庫關聯選擇一般資產或正式報價資產根目錄。
 */
@Service
public class AssetPathResolver {

	private final JdbcTemplate jdbc;
	private final FileStorageService storage;
	private final QuotationOutputDirectoryService quotationOutputs;

	// 方法：建立不依賴使用者路徑前綴判斷儲存範圍的資產路徑解析器。
	public AssetPathResolver(
		JdbcTemplate jdbc,
		FileStorageService storage,
		QuotationOutputDirectoryService quotationOutputs
	) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.quotationOutputs = quotationOutputs;
	}

	// 方法：由 quotation_asset 關聯決定正式報價根目錄，其餘只允許一般資產根目錄。
	public Path resolve(Asset asset) {
		if (asset == null || asset.id() == null) throw new IllegalArgumentException("資產不可留空");

		// 外部呼叫：只以資料庫正式關聯選擇儲存範圍，不採信 file_path 的文字前綴。
		Integer quotationLinks = jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_asset WHERE asset_id = ?",
			Integer.class,
			asset.id()
		);
		return quotationLinks != null && quotationLinks > 0
			? quotationOutputs.resolveLocator(asset.filePath())
			: storage.resolve(asset.filePath());
	}
}
