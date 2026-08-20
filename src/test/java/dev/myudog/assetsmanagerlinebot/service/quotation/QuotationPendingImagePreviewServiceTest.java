package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationPendingImagePreviewServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-11T06:00:00Z");
	private static final String SECRET = "0123456789abcdef0123456789abcdef";

	@TempDir Path temporaryDirectory;
	Connection connection;
	JdbcTemplate jdbc;
	QuotationPendingImagePreviewService service;
	FileStorageService storage;

	@BeforeEach
	void setUp() throws Exception {
		connection = DriverManager.getConnection("jdbc:sqlite::memory:");
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
		jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		storage = new FileStorageService(temporaryDirectory.toString());
		service = new QuotationPendingImagePreviewService(
			jdbc,
			storage,
			"https://quotes.example.test/",
			SECRET,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		seedSelectedImage();
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	@Test
	void issuesHttpsOriginalAndThumbnailLinksWithoutChangingPendingOriginal() throws Exception {
		QuotationImagePreview preview = service.issueSelected(1, "U1");
		byte[] before = Files.readAllBytes(temporaryDirectory.resolve(".pending/original.png"));

		QuotationPendingImagePreviewService.ImageResource original = service.resolve(token(preview.originalUrl()));
		QuotationPendingImagePreviewService.ImageResource thumbnail = service.resolve(token(preview.thumbnailUrl()));

		assertThat(preview.originalUrl()).startsWith("https://quotes.example.test/quotation-pending-images/");
		assertThat(preview.thumbnailUrl()).startsWith("https://quotes.example.test/quotation-pending-images/");
		assertThat(original.bytes()).isEqualTo(before);
		BufferedImage reduced = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
		assertThat(Math.max(reduced.getWidth(), reduced.getHeight())).isEqualTo(1000);
		assertThat(Files.readAllBytes(temporaryDirectory.resolve(".pending/original.png"))).isEqualTo(before);
	}

	@Test
	void rejectsTamperedTokensAndNonHttpsConfiguration() {
		QuotationImagePreview preview = service.issueSelected(1, "U1");
		String tampered = token(preview.originalUrl()) + "x";

		assertThatThrownBy(() -> service.resolve(tampered))
			.isInstanceOf(QuotationLineWorkflowException.class);
		assertThat(new QuotationPendingImagePreviewService(
			jdbc,
			storage,
			"http://localhost:8080",
			SECRET,
			Clock.fixed(NOW, ZoneOffset.UTC)
		).issueSelected(1, "U1")).isNull();
	}

	@Test
	void rejectsAbsoluteAndEscapingPendingPaths() {
		QuotationImagePreview preview = service.issueSelected(1, "U1");
		String imageToken = token(preview.originalUrl());

		jdbc.update("UPDATE pending_image SET staging_path = ? WHERE message_id = 'M1'", temporaryDirectory.resolve("original.png").toString());
		assertThatThrownBy(() -> service.resolve(imageToken))
			.isInstanceOf(QuotationLineWorkflowException.class);

		jdbc.update("UPDATE pending_image SET staging_path = '../outside.png' WHERE message_id = 'M1'");
		assertThatThrownBy(() -> service.resolve(imageToken))
			.isInstanceOf(QuotationLineWorkflowException.class);
	}

	private void seedSelectedImage() throws Exception {
		BufferedImage image = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, Color.BLUE.getRGB());
			}
		}
		Path original = temporaryDirectory.resolve(".pending/original.png");
		Files.createDirectories(original.getParent());
		// 檔案系統：建立測試用原圖，供短效連結與縮圖驗證。
		ImageIO.write(image, "png", original.toFile());
		jdbc.update("""
			INSERT INTO quotation_draft (
				id, draft_key, source_type, source_id, requester_id, status
			) VALUES (1, 'draft-1', 'user', 'U1', 'U1', 'AWAITING_IMAGE')
			""");
		jdbc.update("""
			INSERT INTO pending_image (
				message_id, image_set_id, image_index, image_total, source_type,
				source_id, uploader_id, staging_path, content_type, file_size, received_at
			) VALUES ('M1', 'SET1', 1, 1, 'user', 'U1', 'U1', ?, 'image/png', ?, ?)
			""", ".pending/original.png", Files.size(original), NOW.toString());
		jdbc.update("""
			INSERT INTO quotation_draft_image (draft_id, message_id, is_selected)
			VALUES (1, 'M1', 1)
			""");
	}

	private String token(String url) {
		return url.substring(url.lastIndexOf('/') + 1);
	}
}
