package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QuotationPendingImagePreviewService {

	private static final String ALGORITHM = "HmacSHA256";
	private static final int MAXIMUM_IMAGE_BYTES = 20 * 1024 * 1024;
	private static final int THUMBNAIL_MAXIMUM_EDGE = 1000;
	private static final Duration LINK_LIFETIME = Duration.ofMinutes(15);

	private final JdbcTemplate jdbc;
	private final FileStorageService storage;
	private final String publicBaseUrl;
	private final byte[] secret;
	private final Clock clock;

	// 方法：初始化候選圖片短效 HTTPS 預覽服務。
	@Autowired
	public QuotationPendingImagePreviewService(
		JdbcTemplate jdbc,
		FileStorageService storage,
		@Value("${PUBLIC_BASE_URL:}") String publicBaseUrl,
		@Value("${QUOTATION_IMAGE_LINK_SECRET:${QUOTATION_POSTBACK_SECRET:}}") String secret
	) {
		this(jdbc, storage, publicBaseUrl, secret, Clock.systemUTC());
	}

	// 方法：提供可控制時間的測試建構介面。
	QuotationPendingImagePreviewService(
		JdbcTemplate jdbc,
		FileStorageService storage,
		String publicBaseUrl,
		String secret,
		Clock clock
	) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
		this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
	}

	// 方法：為草稿目前選取圖片簽發 LINE 可公開抓取的原圖與縮圖網址。
	public QuotationImagePreview issueSelected(long draftId, String ownerId) {
		if (!isConfigured()) return null;

		// 資料庫：草稿、擁有者與 selected 必須同時符合才簽發連結。
		List<String> messageIds = jdbc.query("""
			SELECT di.message_id FROM quotation_draft_image di
			JOIN quotation_draft d ON d.id = di.draft_id
			WHERE di.draft_id = ? AND di.is_selected = 1
				AND d.source_type = 'user' AND d.source_id = ?
			""", (result, rowNumber) -> result.getString(1), draftId, ownerId);
		if (messageIds.size() != 1) return null;

		String messageId = messageIds.getFirst();
		Instant expiresAt = clock.instant().plus(LINK_LIFETIME);
		return new QuotationImagePreview(
			messageId,
			publicBaseUrl + "/quotation-pending-images/" + token(draftId, messageId, expiresAt, false),
			publicBaseUrl + "/quotation-pending-images/" + token(draftId, messageId, expiresAt, true)
		);
	}

	// 方法：驗證短效 token 後讀取原圖或即時產生等比例縮圖。
	public ImageResource resolve(String token) {
		TokenPayload payload = verify(token);
		// 資料庫：token 中的草稿與訊息必須仍為該草稿候選圖。
		List<ImageRecord> records = jdbc.query("""
			SELECT p.staging_path, p.content_type FROM quotation_draft_image di
			JOIN pending_image p ON p.message_id = di.message_id
			WHERE di.draft_id = ? AND di.message_id = ?
			""", (result, rowNumber) -> new ImageRecord(result.getString(1), result.getString(2)),
			payload.draftId(), payload.messageId());
		if (records.size() != 1) throw error("IMAGE_NOT_FOUND", "圖片連結已失效。");

		ImageRecord record = records.getFirst();
		try {
			Path path = resolvePendingPath(record.path());
			long size = Files.size(path);
			if (size <= 0 || size > MAXIMUM_IMAGE_BYTES) throw error("IMAGE_SIZE_INVALID", "圖片連結已失效。");

			// 檔案系統：只讀取資料庫已綁定候選圖的明確檔案路徑。
			byte[] original = Files.readAllBytes(path);
			if (!payload.thumbnail()) return new ImageResource(original, record.contentType());

			return thumbnail(original);
		}
		catch (QuotationLineWorkflowException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw error("IMAGE_READ_FAILED", "圖片連結已失效。");
		}
	}

	// 方法：將資料庫內的資產相對路徑安全解析到「圖片資產」子路徑內。
	private Path resolvePendingPath(String stagingPath) throws Exception {
		if (stagingPath == null || stagingPath.isBlank() || Path.of(stagingPath).isAbsolute()) {
			throw error("INVALID_IMAGE_PATH", "圖片暫存路徑不合法");
		}

		try {
			Path resolved = storage.resolve(stagingPath);
			Path realRoot = storage.root().toRealPath();
			Path realFile = resolved.toRealPath();
			if (!realFile.startsWith(realRoot)) throw error("INVALID_IMAGE_PATH", "圖片暫存路徑不合法");

			return realFile;
		}
		catch (IllegalArgumentException exception) {
			throw error("INVALID_IMAGE_PATH", "圖片暫存路徑不合法");
		}
	}

	// 方法：確認公開 HTTPS 與至少 256-bit 密鑰均已設定。
	public boolean isConfigured() {
		return publicBaseUrl.startsWith("https://") && secret.length >= 32;
	}

	// 方法：以 HMAC 簽發不含路徑及個資的短效 token。
	private String token(long draftId, String messageId, Instant expiresAt, boolean thumbnail) {
		String payload = draftId + ":" + expiresAt.getEpochSecond() + ":" + (thumbnail ? "t" : "o")
			+ ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(messageId.getBytes(StandardCharsets.UTF_8));
		String encodedPayload = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		String signature = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(Arrays.copyOf(hmac(encodedPayload), 16));
		return encodedPayload + "." + signature;
	}

	// 方法：以常數時間比較驗證 token 完整性及有效期限。
	private TokenPayload verify(String token) {
		if (!isConfigured() || token == null || token.length() > 500) throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");

		String[] parts = token.split("\\.", -1);
		if (parts.length != 2) throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");

		byte[] provided = decode(parts[1]);
		byte[] expected = Arrays.copyOf(hmac(parts[0]), 16);
		if (!MessageDigest.isEqual(provided, expected)) throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");

		try {
			String decoded = new String(decode(parts[0]), StandardCharsets.UTF_8);
			String[] fields = decoded.split(":", 4);
			if (fields.length != 4) throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");

			long draftId = Long.parseLong(fields[0]);
			Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[1]));
			boolean thumbnail = switch (fields[2]) {
				case "t" -> true;
				case "o" -> false;
				default -> throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");

			};
			String messageId = new String(decode(fields[3]), StandardCharsets.UTF_8);
			if (draftId <= 0 || messageId.isBlank() || !clock.instant().isBefore(expiresAt)) {
				throw error("EXPIRED_IMAGE_TOKEN", "圖片連結已失效。");
			}
			return new TokenPayload(draftId, messageId, thumbnail);
		}
		catch (QuotationLineWorkflowException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");
		}
	}

	// 方法：在記憶體中產生不覆寫原圖的 JPEG 等比例縮圖。
	private ImageResource thumbnail(byte[] original) throws Exception {
		BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
		if (source == null) throw error("INVALID_IMAGE", "圖片連結已失效。");

		double scale = Math.min(
			1.0,
			(double) THUMBNAIL_MAXIMUM_EDGE / Math.max(source.getWidth(), source.getHeight())
		);
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.drawImage(source, 0, 0, width, height, null);
		}
		finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(result, "jpg", output);
		return new ImageResource(output.toByteArray(), "image/jpeg");
	}

	// 方法：計算 HMAC-SHA256。
	private byte[] hmac(String value) {
		try {
			// 密碼學 API：每次建立獨立 Mac，避免跨執行緒共用可變狀態。
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new IllegalStateException("無法建立圖片簽章。", exception);
		}
	}

	// 方法：嚴格解碼 URL-safe Base64。
	private byte[] decode(String value) {
		try {
			return Base64.getUrlDecoder().decode(value);
		}
		catch (IllegalArgumentException exception) {
			throw error("INVALID_IMAGE_TOKEN", "圖片連結已失效。");
		}
	}

	// 方法：建立安全的圖片流程錯誤。
	private QuotationLineWorkflowException error(String code, String message) {
		return new QuotationLineWorkflowException(code, message);
	}

	public record ImageResource(byte[] bytes, String contentType) {
		// 方法：防止呼叫端修改內部圖片位元組。
		public ImageResource {
			bytes = bytes == null ? new byte[0] : bytes.clone();
		}

		// 方法：回傳圖片位元組副本。
		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	private record TokenPayload(long draftId, String messageId, boolean thumbnail) {}

	private record ImageRecord(String path, String contentType) {}
}
