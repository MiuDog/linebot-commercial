package dev.miudog.linebotcommercial.migration;

import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegacyStorageMigrationRunnerTest {

	@TempDir
	Path temporaryDirectory;

	// 測試：預設 dry-run 只盤點來源並產生成功報告，不複製物件或修改 SQLite。
	@Test
	void dryRunProducesReportWithoutMutatingSource() throws Exception {
		Path source = temporaryDirectory.resolve("legacy.db");
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
			connection.createStatement().execute("CREATE TABLE legacy_only (id INTEGER PRIMARY KEY, value TEXT)");
			connection.createStatement().execute("INSERT INTO legacy_only VALUES (1, 'unchanged')");
		}
		Path target = temporaryDirectory.resolve("target.db");
		SQLiteDataSource targetDataSource = new SQLiteDataSource();
		targetDataSource.setUrl("jdbc:sqlite:" + target);
		Path assets = Files.createDirectory(temporaryDirectory.resolve("assets"));
		Path report = temporaryDirectory.resolve("migration-report.json");
		ObjectStorage storage = mock(ObjectStorage.class);
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();
		LegacyStorageMigrationRunner runner = new LegacyStorageMigrationRunner(
			targetDataSource,
			storage,
			new CompanyProperties("company-test"),
			context,
			source.toString(),
			assets.toString(),
			"",
			report.toString(),
			false
		);

		runner.run(new DefaultApplicationArguments(new String[0]));

		String result = Files.readString(report);
		assertThat(result).contains("\"mode\" : \"DRY_RUN\"", "\"status\" : \"SUCCEEDED\"");
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
			var rows = connection.createStatement().executeQuery("SELECT value FROM legacy_only WHERE id = 1");
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("unchanged");
		}
		verifyNoInteractions(storage);
	}
}
