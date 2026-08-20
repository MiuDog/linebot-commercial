package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.domain.Asset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetRepositoryVoiceSearchTest {

	SingleConnectionDataSource dataSource;
	AssetRepository repository;

	@BeforeEach
	void setUp() throws SQLException {
		dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
		ScriptUtils.executeSqlScript(dataSource.getConnection(), new ClassPathResource("schema.sql"));
		repository = new AssetRepository(JdbcClient.create(dataSource));
	}

	@AfterEach
	void tearDown() {
		dataSource.destroy();
	}

	@Test
	void limitsVoiceRetrievalToTheSameGroupDepartmentAndFormalDateFolder() {
		insert("M1", "C1", "ZD12345/20260810/20260810-01.jpg", "zd12345");
		insert("M2", "C1", "ZD12345/20260809/20260809-01.jpg", "zd12345");
		insert("M3", "C2", "ZD12345/20260810/20260810-02.jpg", "zd12345");
		insert("M4", "C1", "YJ123456/20260810/20260810-03.jpg", "yj123456");

		List<Asset> found = repository.searchByDepartmentAndDate(
			"C1",
			"zd12345",
			"20260810",
			4
		);

		assertThat(found).extracting(Asset::messageId).containsExactly("M1");
	}

	// 方法：建立並標記一張供語音查詢隔離測試使用的圖片。
	private void insert(String messageId, String sourceId, String filePath, String tag) {
		Long assetId = repository.insert(new Asset(
			null,
			messageId,
			"share" + messageId,
			"group",
			sourceId,
			"U1",
			filePath,
			"image/jpeg",
			100L,
			Instant.parse("2026-08-10T01:00:00Z"),
			List.of()
		));
		repository.linkTag(assetId, repository.upsertTag(tag));
	}
}
