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
