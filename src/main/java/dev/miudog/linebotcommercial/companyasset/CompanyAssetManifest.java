package dev.miudog.linebotcommercial.companyasset;

import java.util.List;

/**
 * 公司資產包的可攜式 manifest。
 */
public record CompanyAssetManifest(
	String schemaVersion,
	String companyId,
	String assetSetVersion,
	List<AssetObject> objects
) {

	public record AssetObject(
		CompanyAssetPurpose purpose,
		String fileName,
		String contentType,
		long size,
		String sha256
	) {
	}
}
