package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongToIntFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuotationPostbackSigner {

	private static final String ALGORITHM = "HmacSHA256";
	private static final int MINIMUM_SECRET_BYTES = 32;
	private static final int OWNER_TAG_BYTES = 12;
	private static final int SIGNATURE_BYTES = 16;
	private static final int MAXIMUM_DATA_LENGTH = 300;
	private static final Set<String> PAYLOAD_FIELDS = Set.of("v", "d", "r", "a", "e", "u");
	private static final Set<String> RESOURCE_PAYLOAD_FIELDS = Set.of("v", "d", "r", "a", "e", "u", "i");

	private final byte[] secret;
	private final Clock clock;

	// 方法：從環境密鑰建立正式 postback 簽章器。
	@Autowired
	public QuotationPostbackSigner(@Value("${QUOTATION_POSTBACK_SECRET:}") String secret) {
		this(secret, Clock.systemUTC());
	}

	// 方法：建立可注入固定時間的簽章器，供期限測試使用。
	QuotationPostbackSigner(String secret, Clock clock) {
		this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
	}

	// 方法：判斷環境密鑰是否達到最低安全長度。
	public boolean isConfigured() {
		return secret.length >= MINIMUM_SECRET_BYTES;
	}

	// 方法：簽發綁定草稿、版本、動作、期限與使用者的短 postback 資料。
	public String sign(
		long draftId,
		int revision,
		QuotationPostbackAction action,
		Instant expiresAt,
		String ownerId
	) {
		return sign(draftId, revision, action, expiresAt, ownerId, null);
	}

	// 方法：簽署含候選資源識別的 postback，避免圖片識別遭竄改或跨草稿重放。
	public String sign(
		long draftId,
		int revision,
		QuotationPostbackAction action,
		Instant expiresAt,
		String ownerId,
		String resourceId
	) {
		requireConfigured();
		if (draftId <= 0) throw error("INVALID_POSTBACK", "草稿識別必須大於零");

		if (revision <= 0) throw error("INVALID_POSTBACK", "草稿版本必須大於零");

		if (action == null) throw error("INVALID_POSTBACK", "報價操作不可留空");

		if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
			throw error("INVALID_POSTBACK", "postback 有效期限必須晚於目前時間");
		}
		requireOwner(ownerId);
		if (requiresResource(action)
			&& (resourceId == null || resourceId.isBlank())) {
			throw error("INVALID_POSTBACK", "圖片操作缺少必要資源識別。");
		}
		if (!requiresResource(action) && resourceId != null) {
			throw error("INVALID_POSTBACK", "此 postback 動作不可攜帶資源識別。");
		}

		String ownerTag = encode(ownerTag(ownerId));
		String payload = "v=1&d=" + draftId
			+ "&r=" + revision
			+ "&a=" + action.code()
			+ "&e=" + expiresAt.getEpochSecond()
			+ "&u=" + ownerTag;
		if (resourceId != null) payload += "&i=" + encode(resourceId.getBytes(StandardCharsets.UTF_8));

		String signature = encode(Arrays.copyOf(hmac(payload), SIGNATURE_BYTES));
		String data = payload + "&s=" + signature;
		if (data.length() > MAXIMUM_DATA_LENGTH) throw error("POSTBACK_TOO_LONG", "postback 資料超過 LINE 限制");

		return data;
	}

	// 方法：驗證簽章、期限、使用者所有權與目前草稿版本。
	public QuotationVerifiedPostback verify(String data, String expectedOwnerId, int currentRevision) {
		QuotationVerifiedPostback verified = verifyAuthenticated(data, expectedOwnerId);
		if (verified.revision() != currentRevision) throw error("STALE_REVISION", "草稿已更新，請使用最新按鈕");

		return verified;
	}

	// 方法：完成驗簽與使用者綁定後，才以受信任草稿編號查詢目前 revision。
	public QuotationVerifiedPostback verify(
		String data,
		String expectedOwnerId,
		LongToIntFunction revisionLookup
	) {
		if (revisionLookup == null) throw error("INVALID_POSTBACK", "缺少草稿版本查詢方式");

		QuotationVerifiedPostback verified = verifyAuthenticated(data, expectedOwnerId);
		int currentRevision = revisionLookup.applyAsInt(verified.draftId());
		if (verified.revision() != currentRevision) throw error("STALE_REVISION", "草稿已更新，請使用最新按鈕");

		return verified;
	}

	// 方法：驗證 HMAC、期限與使用者綁定，不執行資料庫或業務操作。
	private QuotationVerifiedPostback verifyAuthenticated(String data, String expectedOwnerId) {
		requireConfigured();
		requireOwner(expectedOwnerId);
		if (data == null || data.isBlank() || data.length() > MAXIMUM_DATA_LENGTH) {
			throw error("INVALID_POSTBACK", "無效的 postback 資料");
		}

		int signatureSeparator = data.lastIndexOf("&s=");
		if (signatureSeparator <= 0 || data.indexOf("&s=") != signatureSeparator) {
			throw error("INVALID_POSTBACK", "無效的 postback 資料");
		}

		String payload = data.substring(0, signatureSeparator);
		byte[] providedSignature = decode(data.substring(signatureSeparator + 3));
		byte[] expectedSignature = Arrays.copyOf(hmac(payload), SIGNATURE_BYTES);
		if (!MessageDigest.isEqual(providedSignature, expectedSignature)) {
			throw error("INVALID_SIGNATURE", "postback 簽章驗證失敗");
		}

		Map<String, String> fields = parsePayload(payload);
		long draftId = positiveLong(fields.get("d"));
		int revision = positiveRevision(fields.get("r"));
		QuotationPostbackAction action = QuotationPostbackAction.fromCode(fields.get("a"));
		Instant expiresAt = epochSecond(fields.get("e"));
		byte[] ownerTag = decode(fields.get("u"));
		if (!MessageDigest.isEqual(ownerTag, ownerTag(expectedOwnerId))) {
			throw error("OWNER_MISMATCH", "此操作不屬於目前使用者");
		}
		if (!clock.instant().isBefore(expiresAt)) throw error("POSTBACK_EXPIRED", "此操作已過期");

		String resourceId = fields.containsKey("i")
			? new String(decode(fields.get("i")), StandardCharsets.UTF_8)
			: null;
		if (requiresResource(action)
			&& (resourceId == null || resourceId.isBlank())) {
			throw error("INVALID_POSTBACK", "圖片操作缺少必要資源識別。");
		}
		if (!requiresResource(action) && resourceId != null) {
			throw error("INVALID_POSTBACK", "此 postback 動作不可攜帶資源識別。");
		}

		return new QuotationVerifiedPostback(draftId, revision, action, expiresAt, resourceId);
	}

	// 方法：判斷 postback 動作是否必須攜帶候選圖片、頁碼或報價格式等資源識別。
	private boolean requiresResource(QuotationPostbackAction action) {
		return action == QuotationPostbackAction.SELECT_IMAGE
			|| action == QuotationPostbackAction.IMAGE_OPTIONS_PAGE
			|| action == QuotationPostbackAction.SELECT_SCHEME;
	}

	// 方法：解析簽章保護的固定欄位並拒絕重複或額外資料。
	private Map<String, String> parsePayload(String payload) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (String part : payload.split("&", -1)) {
			int separator = part.indexOf('=');
			if (separator <= 0 || separator != part.lastIndexOf('=')) {
				throw error("INVALID_POSTBACK", "無效的 postback 資料");
			}

			String key = part.substring(0, separator);
			String value = part.substring(separator + 1);
			if (value.isEmpty() || fields.putIfAbsent(key, value) != null) {
				throw error("INVALID_POSTBACK", "無效的 postback 資料");
			}
		}
		if ((!fields.keySet().equals(PAYLOAD_FIELDS)
			&& !fields.keySet().equals(RESOURCE_PAYLOAD_FIELDS))
			|| !"1".equals(fields.get("v"))) {
			throw error("INVALID_POSTBACK", "不支援的 postback 格式");
		}
		return fields;
	}

	// 方法：產生不暴露原始 LINE 使用者識別的所有權指紋。
	private byte[] ownerTag(String ownerId) {
		return Arrays.copyOf(hmac("owner:" + ownerId), OWNER_TAG_BYTES);
	}

	// 方法：以環境密鑰計算 HMAC-SHA256 完整摘要。
	private byte[] hmac(String value) {
		try {
			// 密碼學 API：建立每次呼叫獨立的 Mac，避免跨執行緒共用可變狀態。
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new IllegalStateException("無法建立 postback 簽章", exception);
		}
	}

	// 方法：將二進位資料編碼為不需 URL 跳脫的短字串。
	private String encode(byte[] value) {
		// 編碼 API：使用無填充 Base64 URL 格式縮短 LINE postback 資料。
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	// 方法：解碼並拒絕非 Base64 URL 格式的 postback 欄位。
	private byte[] decode(String value) {
		try {
			// 編碼 API：只接受 Base64 URL 格式，其他輸入統一視為無效資料。
			return Base64.getUrlDecoder().decode(value);
		}
		catch (IllegalArgumentException exception) {
			throw error("INVALID_POSTBACK", "無效的 postback 資料");
		}
	}

	// 方法：解析必須大於零的草稿識別。
	private long positiveLong(String value) {
		try {
			long parsed = Long.parseLong(value);
			if (parsed <= 0) throw error("INVALID_POSTBACK", "無效的草稿識別");

			return parsed;
		}
		catch (NumberFormatException exception) {
			throw error("INVALID_POSTBACK", "無效的草稿識別");
		}
	}

	// 方法：解析必須大於零的草稿版本。
	private int positiveRevision(String value) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed <= 0) throw error("INVALID_POSTBACK", "無效的草稿版本");

			return parsed;
		}
		catch (NumberFormatException exception) {
			throw error("INVALID_POSTBACK", "無效的草稿版本");
		}
	}

	// 方法：將 epoch 秒數解析為 postback 有效期限。
	private Instant epochSecond(String value) {
		try {
			return Instant.ofEpochSecond(Long.parseLong(value));
		}
		catch (RuntimeException exception) {
			throw error("INVALID_POSTBACK", "無效的 postback 有效期限");
		}
	}

	// 方法：確保簽章密鑰至少具有 256-bit 原始長度。
	private void requireConfigured() {
		if (!isConfigured()) throw error("POSTBACK_NOT_CONFIGURED", "尚未設定安全的報價 postback 密鑰");
	}

	// 方法：確保所有權驗證具有非空白的 LINE 使用者識別。
	private void requireOwner(String ownerId) {
		if (ownerId == null || ownerId.isBlank()) throw error("INVALID_OWNER", "使用者識別不可留空");
	}

	// 方法：建立不洩漏內部密鑰與簽章內容的錯誤。
	private QuotationPostbackException error(String code, String message) {
		return new QuotationPostbackException(code, message);
	}
}
