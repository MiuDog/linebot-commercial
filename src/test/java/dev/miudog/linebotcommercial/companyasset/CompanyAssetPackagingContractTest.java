package dev.miudog.linebotcommercial.companyasset;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止公司母檔再次混入正式 resources 或 Docker context。
 */
class CompanyAssetPackagingContractTest {

	@Test
	void productionResourcesContainNoBundledCompanyTemplatesOrCatalog() throws Exception {
		Path resources = Path.of("src/main/resources");
		assertThat(resources.resolve("quotation/template-definitions.json")).doesNotExist();
		try (var paths = Files.walk(resources)) {
			assertThat(paths.filter(Files::isRegularFile).map(path -> path.toString().toLowerCase()).toList())
				.noneMatch(path -> path.endsWith(".xlsx") || path.endsWith(".xlsm"));
		}
		String baseline = Files.readString(resources.resolve("db/migration/V1__baseline.sql"));
		assertThat(baseline).doesNotContain("INSERT INTO quotation_template", "excel_master", "EXTERNAL_SCAFFOLD");
	}

	@Test
	void privateAssetPackagesAreExcludedFromGitAndDocker() throws Exception {
		assertThat(Files.readString(Path.of(".gitignore"))).contains("/company-assets-local/");
		assertThat(Files.readString(Path.of(".dockerignore"))).contains("company-assets-local", "dist", "templates");
	}
}
