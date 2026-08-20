package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 建立與驗證只保存 SHA-256 雜湊的 PDF 限時下載權杖。
 */
@Service
public class QuotationDownloadService {

	private static final int TOKEN_BYTES = 32;
	private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
	private static final Duration MAXIMUM_TTL = Duration.ofDays(30);
	private static final String QUOTATION_DIRECTORY = "報價單";

	private final JdbcTemplate jdbc;
	private final Path outputRoot;
	private final String publicBaseUrl;
	private final SecureRandom secureRandom = new SecureRandom();

	// 方法：建立 PDF 安全下載服務並正規化可信設定路徑。
	public QuotationDownloadService(
		JdbcTemplate jdbc,
		@Value("${app.quotation.root-path:}") String outputRoot,
		@Value("${app.public-base-url:}") String publicBaseUrl
	) {
		this.jdbc = jdbc;
		// 外部呼叫：使用 Java NIO 正規化管理員設定的報價輸出根目錄。
		this.outputRoot = outputRoot == null || outputRoot.isBlank()
			? null
			: Paths.get(outputRoot).toAbsolutePath().normalize();
		this.publicBaseUrl = normalizedPublicBaseUrl(publicBaseUrl);
	}

	// 方法：為已完成的 PDF 建立一次可撤銷的限時下載連結。
	@Transactional
	public QuotationDownloadLink issuePdfLink(long quotationId, Duration timeToLive) {
		requireConfigured();
		Duration ttl = validTimeToLive(timeToLive);
		FileRecord file = requireReadyPdf(quotationId);
		String token = randomToken();
		Instant expiresAt = Instant.now().plus(ttl);

		// 外部呼叫：只保存不可逆權杖雜湊與期限，不保存原始下載權杖。
		jdbc.update("""
			INSERT INTO quotation_download_token (
				quotation_id, file_id, asset_id, purpose, token_hash, expires_at
			)
			VALUES (?, ?, NULL, 'PDF', ?, ?)
			""", quotationId, file.fileId(), hash(token), expiresAt.toString());

		return new QuotationDownloadLink(
			publicBaseUrl + "/quotation-downloads/" + token,
			expiresAt
		);
	}

	// 方法：驗證原始權杖並解析成仍位於報價根目錄內的可讀 PDF。
	public QuotationDownloadResource resolvePdf(String rawToken) {
		if (rawToken == null || !TOKEN_PATTERN.matcher(rawToken).matches()) return null;

		// 外部呼叫：以權杖雜湊查詢尚未撤銷的 PDF 與正式檔案狀態。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT dt.expires_at, qf.relative_path, qf.content_type, qf.file_size
			FROM quotation_download_token dt
			JOIN quotation_file qf ON qf.id = dt.file_id AND qf.quotation_id = dt.quotation_id
			WHERE dt.token_hash = ?
			  AND dt.purpose = 'PDF'
			  AND dt.revoked_at IS NULL
			  AND qf.file_kind = 'PDF'
			  AND qf.status = 'READY'
			""", hash(rawToken));
		if (rows.size() != 1) return null;

		Map<String, Object> row = rows.getFirst();
		Instant expiresAt = Instant.parse((String) row.get("expires_at"));
		if (!expiresAt.isAfter(Instant.now())) return null;

		Path path = safeOutputPath((String) row.get("relative_path"));
		// 外部呼叫：確認資料庫所指的 PDF 仍是報價根目錄內可讀取的一般檔案。
		if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) return null;

		long fileSize = ((Number) row.get("file_size")).longValue();
		return new QuotationDownloadResource(
			path,
			path.getFileName().toString(),
			(String) row.get("content_type"),
			fileSize
		);
	}

	// 方法：撤銷指定報價目前所有仍有效的 PDF 下載連結。
	@Transactional
	public void revokePdfLinks(long quotationId) {
		// 外部呼叫：以撤銷時間停用該報價所有 PDF 權杖。
		jdbc.update("""
			UPDATE quotation_download_token
			SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
			WHERE quotation_id = ? AND purpose = 'PDF'
			""", quotationId);
	}

	// 方法：取得指定報價唯一且已完成的 PDF 檔案紀錄。
	private FileRecord requireReadyPdf(long quotationId) {
		// 外部呼叫：查詢可以發行下載權杖的已完成 PDF。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT id, relative_path
			FROM quotation_file
			WHERE quotation_id = ? AND file_kind = 'PDF' AND status = 'READY'
			""", quotationId);
		if (rows.size() != 1) throw new IllegalStateException("報價 PDF 尚未完成");

		Map<String, Object> row = rows.getFirst();
		Path path = safeOutputPath((String) row.get("relative_path"));
		// 外部呼叫：確認發行權杖前 PDF 實體檔案仍存在且可讀取。
		if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
			throw new IllegalStateException("報價 PDF 檔案不存在");
		}

		return new FileRecord(((Number) row.get("id")).longValue());
	}

	// 方法：將資料庫相對路徑限制在設定根目錄的報價單資料夾內。
	private Path safeOutputPath(String relativePath) {
		if (outputRoot == null || relativePath == null || relativePath.isBlank()) return null;

		Path quotationRoot = outputRoot.resolve(QUOTATION_DIRECTORY).normalize();
		Path resolved = outputRoot.resolve(relativePath).normalize();
		if (!resolved.startsWith(quotationRoot)) return null;

		try {
			Path realRoot = quotationRoot.toRealPath();
			Path realFile = resolved.toRealPath();
			return realFile.startsWith(realRoot) ? realFile : null;
		}
		catch (IOException exception) {
			return null;
		}
	}

	// 方法：使用密碼學安全亂數建立 256-bit URL-safe 權杖。
	private String randomToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		// 外部呼叫：由作業系統安全亂數來源填入下載權杖位元組。
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// 方法：以 SHA-256 將權杖轉成固定長度十六進位雜湊。
	private String hash(String token) {
		try {
			// 外部呼叫：取得 JDK SHA-256 摘要實作並計算權杖雜湊。
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境缺少 SHA-256", exception);
		}
	}

	// 方法：驗證下載連結有效期間不超出允許範圍。
	private Duration validTimeToLive(Duration value) {
		if (value == null || value.isZero() || value.isNegative() || value.compareTo(MAXIMUM_TTL) > 0) {
			throw new IllegalArgumentException("下載連結期限必須介於 1 秒至 30 天");
		}

		return value;
	}

	// 方法：確認下載服務具備報價輸出根目錄與公開 HTTPS 網址。
	private void requireConfigured() {
		if (outputRoot == null) throw new IllegalStateException("尚未設定報價輸出根目錄");

		if (publicBaseUrl == null) throw new IllegalStateException("尚未設定公開 HTTPS 網址");
	}

	// 方法：只接受管理員設定的 HTTPS 公開基底網址並移除結尾斜線。
	private String normalizedPublicBaseUrl(String value) {
		if (value == null || value.isBlank()) return null;

		String normalized = value.trim().replaceAll("/+$", "");
		if (!normalized.startsWith("https://")) {
			throw new IllegalArgumentException("公開網址必須使用 HTTPS");
		}

		return normalized;
	}

	private record FileRecord(long fileId) {}
}
