package dev.miudog.linebotcommercial.companyasset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CompanyTemplateRegistrationTest {

	@Autowired CompanyAssetRepository repository;
	@Autowired JdbcTemplate jdbc;

	// 方法：驗證產生的版本 ID 能連結資產物件，避免驅動回傳多欄主鍵時中斷匯入。
	@Test
	void createsStagedVersionAndLinksItsObjects() throws Exception {
		// 資料庫：載入正式資產表定義，僅轉換 SQLite 的自增主鍵型別。
		String schema = Files.readString(Path.of("src/main/resources/db/migration/V2__company_assets_and_object_storage.sql"))
			.split("CREATE TABLE storage_migration_ledger")[0].replace("BIGSERIAL PRIMARY KEY", "INTEGER PRIMARY KEY");
		for (String statement : schema.split(";")) {
			if (!statement.isBlank()) jdbc.execute(statement);
		}

		var manifest = new CompanyAssetManifest("1", "test-company", "staging-key-test", java.util.List.of());
		var object = new CompanyAssetRepository.StoredManifestObject(
			CompanyAssetPurpose.LOGO, "test/logo.png", null, "image/png", 8, "a".repeat(64)
		);
		long id = repository.createStaged(manifest, "b".repeat(64), "test-installer", java.util.List.of(object));
		assertThat(repository.required("staging-key-test").id()).isEqualTo(id);
		assertThat(repository.required("staging-key-test").status()).isEqualTo("STAGED");
		assertThat(repository.objects(id)).containsExactly(object);
	}

	// 方法：以真實資料庫驗證五種範本登錄、版本切換及回復，不改寫舊版中繼資料。
	@Test
	void registersEverySchemeAndReactivatesOriginalTemplateIds() throws Exception {
		var definitions = new ObjectMapper().readTree(Files.readString(Path.of("src/test/resources/quotation/template-definitions.json")));
		repository.registerTemplates(9001, definitions);
		var original = jdbc.queryForList("SELECT id FROM quotation_template WHERE is_active = 1 ORDER BY id", Long.class);
		assertThat(original).hasSize(5);
		repository.registerTemplates(9002, definitions);
		assertThat(jdbc.queryForList("SELECT id FROM quotation_template WHERE is_active = 1 ORDER BY id", Long.class))
			.hasSize(5).doesNotContainAnyElementsOf(original);
		repository.registerTemplates(9001, definitions);
		assertThat(jdbc.queryForList("SELECT id FROM quotation_template WHERE is_active = 1 ORDER BY id", Long.class)).isEqualTo(original);
	}
}
