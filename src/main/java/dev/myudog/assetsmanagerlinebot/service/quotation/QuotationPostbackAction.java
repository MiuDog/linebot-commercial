package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.Arrays;

public enum QuotationPostbackAction {
	CONFIRM("c"),
	CANCEL("x"),
	DECLINE_IMAGE("n"),
	MODIFY("m"),
	SELECT_IMAGE("s"),
	REMOVE_IMAGE("r"),
	IMAGE_OPTIONS_PAGE("p"),
	SELECT_SCHEME("f");

	private final String code;

	// 方法：建立穩定且精簡的 postback 動作代碼。
	QuotationPostbackAction(String code) {
		this.code = code;
	}

	// 方法：取得 LINE postback 使用的短代碼。
	public String code() {
		return code;
	}

	// 方法：將已驗證的短代碼還原為允許的應用動作。
	public static QuotationPostbackAction fromCode(String code) {
		return Arrays.stream(values())
			.filter(action -> action.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new QuotationPostbackException("INVALID_POSTBACK", "無效的報價操作"));
	}
}
