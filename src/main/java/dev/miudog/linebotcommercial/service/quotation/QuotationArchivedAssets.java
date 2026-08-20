package dev.miudog.linebotcommercial.service.quotation;

import java.nio.file.Path;
import java.util.List;

/**
 * 正式報價的全部圖片資產，以及唯一提供給報表的選中原圖。
 */
public record QuotationArchivedAssets(
	List<QuotationArchivedAsset> assets,
	Path selectedImagePath
) {

	// 方法：建立不可變的正式圖片資產結果。
	public QuotationArchivedAssets {
		assets = assets == null ? List.of() : List.copyOf(assets);
	}
}
