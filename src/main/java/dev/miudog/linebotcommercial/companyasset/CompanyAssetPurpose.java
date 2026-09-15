package dev.miudog.linebotcommercial.companyasset;

/**
 * 商用機可由公司自行維護、但絕不進入程式映像的資產用途。
 */
public enum CompanyAssetPurpose {
	LOGO("image/"),
	SEAL("image/"),
	TEMPLATE_BLANK("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	TEMPLATE_CNS("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	TEMPLATE_GENERAL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	TEMPLATE_MARINE("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	TEMPLATE_SALES("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	TEMPLATE_DEFINITIONS("application/json"),
	ITEM_MASTER("text/csv"),
	CONTACT_DATA("application/json");

	private final String contentTypePrefix;

	// 方法：執行此方法定義的受控處理流程。
	CompanyAssetPurpose(String contentTypePrefix) {
		this.contentTypePrefix = contentTypePrefix;
	}

	// 方法：執行此方法定義的受控處理流程。
	public boolean accepts(String contentType) {
		return contentType != null && contentType.startsWith(contentTypePrefix);
	}
}
