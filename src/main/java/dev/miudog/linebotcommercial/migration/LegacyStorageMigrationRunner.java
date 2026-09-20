package dev.miudog.linebotcommercial.migration;

import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.storage.ObjectStorage;
import dev.miudog.linebotcommercial.storage.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 將舊 SQLite 與本機檔案複製到 PostgreSQL／S3；預設只執行 dry-run。
 */
@Component
@ConditionalOnProperty(name = "app.migration.enabled", havingValue = "true")
public class LegacyStorageMigrationRunner implements ApplicationRunner {

	private static final Set<String> SKIPPED_TABLES = Set.of(
		"flyway_schema_history",
		"storage_migration_ledger"
	);
	private static final ObjectMapper JSON = new ObjectMapper();

	private final DataSource targetDataSource;
	private final ObjectStorage objectStorage;
	private final CompanyProperties company;
	private final ConfigurableApplicationContext context;
	private final Path legacyDatabase;
	private final Path assetRoot;
	private final Path outputRoot;
	private final Path reportPath;
	private final boolean execute;

	// 方法：執行此方法定義的受控處理流程。
	public LegacyStorageMigrationRunner(
		DataSource targetDataSource,
		ObjectStorage objectStorage,
		CompanyProperties company,
		ConfigurableApplicationContext context,
		@Value("${app.migration.legacy-database:}") String legacyDatabase,
		@Value("${app.migration.asset-root:}") String assetRoot,
		@Value("${app.migration.output-root:}") String outputRoot,
		@Value("${app.migration.report-path:${java.io.tmpdir}/legacy-migration-report.json}") String reportPath,
		@Value("${app.migration.execute:false}") boolean execute
	) {
		this.targetDataSource = targetDataSource;
		this.objectStorage = objectStorage;
		this.company = company;
		this.context = context;
		this.legacyDatabase = requiredPath(legacyDatabase, "MIGRATION_LEGACY_DATABASE");
		this.assetRoot = requiredPath(assetRoot, "MIGRATION_ASSET_ROOT");
		this.outputRoot = optionalPath(outputRoot);
		this.reportPath = requiredPath(reportPath, "MIGRATION_REPORT_PATH");
		this.execute = execute;
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public void run(ApplicationArguments arguments) {
		MigrationReport report;
		try {
			report = migrate();
		}
		catch (Exception exception) {
			report = new MigrationReport(
				execute ? "EXECUTE" : "DRY_RUN",
				"FAILED",
				Map.of(),
				0,
				0,
				List.of(safeCode(exception))
			);
		}
		writeReport(report);
		int exitCode = "SUCCEEDED".equals(report.status()) ? 0 : 2;
		SpringApplication.exit(context, () -> exitCode);
	}

	// 方法：執行此方法定義的受控處理流程。
	private MigrationReport migrate() throws SQLException, IOException {
		if (!Files.isRegularFile(legacyDatabase)) throw failure("LEGACY_DATABASE_NOT_FOUND");

		if (!Files.isDirectory(assetRoot)) throw failure("LEGACY_ASSET_ROOT_NOT_FOUND");

		try (
			Connection source = openReadOnlySource();
			Connection target = targetDataSource.getConnection()
		) {
			List<TablePlan> tables = tablePlans(source, target.getMetaData());
			Map<String, Long> counts = sourceCounts(source, tables);
			List<FilePlan> files = filePlans(source);
			if (!execute) return new MigrationReport("DRY_RUN", "SUCCEEDED", counts, files.size(), 0, List.of());

			target.setAutoCommit(false);
			List<String> createdObjects = new ArrayList<>();
			try {
				copyTables(source, target, tables);
				int copiedFiles = copyFiles(target, files, createdObjects);
				verifyCounts(target, counts);
				resetSequences(target, tables);
				target.commit();
				return new MigrationReport(
					"EXECUTE",
					"SUCCEEDED",
					counts,
					files.size(),
					copiedFiles,
					List.of()
				);
			}
			catch (Exception exception) {
				target.rollback();
				for (String key : createdObjects) {
					try {
						objectStorage.delete(key);
					}
					catch (RuntimeException compensationFailure) {
						exception.addSuppressed(compensationFailure);
					}
				}
				if (exception instanceof SQLException sqlException) throw sqlException;

				throw failure(safeCode(exception), exception);
			}
		}
	}

	// 方法：在建立 SQLite 連線時即使用唯讀 URI，避免驅動拒絕事後切換 read-only。
	private Connection openReadOnlySource() throws SQLException {
		String sourceUri = legacyDatabase.toUri().toASCIIString();
		return java.sql.DriverManager.getConnection("jdbc:sqlite:" + sourceUri + "?mode=ro");
	}

	// 方法：執行此方法定義的受控處理流程。
	private List<TablePlan> tablePlans(Connection source, DatabaseMetaData targetMetadata) throws SQLException {
		Set<String> targetTables = new HashSet<>();
		try (ResultSet result = targetMetadata.getTables(null, "public", "%", new String[] { "TABLE" })) {
			while (result.next()) targetTables.add(result.getString("TABLE_NAME").toLowerCase());
		}

		List<TablePlan> plans = new ArrayList<>();
		try (
			PreparedStatement statement = source.prepareStatement("""
				SELECT name FROM sqlite_master
				WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
				ORDER BY name
				""");
			ResultSet rows = statement.executeQuery()
		) {
			while (rows.next()) {
				String table = identifier(rows.getString(1));
				if (!targetTables.contains(table) || SKIPPED_TABLES.contains(table)) continue;

				Set<String> targetColumns = new HashSet<>();
				try (ResultSet columns = targetMetadata.getColumns(null, "public", table, "%")) {
					while (columns.next()) targetColumns.add(columns.getString("COLUMN_NAME").toLowerCase());
				}
				List<String> intersection = new ArrayList<>();
				try (
					Statement statementColumns = source.createStatement();
					ResultSet columns = statementColumns.executeQuery("PRAGMA table_info(" + table + ")")
				) {
					while (columns.next()) {
						String column = identifier(columns.getString("name"));
						if (targetColumns.contains(column)) intersection.add(column);
					}
				}
				if (!intersection.isEmpty()) plans.add(new TablePlan(table, List.copyOf(intersection)));
			}
		}
		return plans;
	}

	// 方法：執行此方法定義的受控處理流程。
	private Map<String, Long> sourceCounts(Connection source, List<TablePlan> tables) throws SQLException {
		Map<String, Long> result = new LinkedHashMap<>();
		for (TablePlan table : tables) result.put(table.name(), count(source, table.name()));
		return result;
	}

	// 方法：執行此方法定義的受控處理流程。
	private void copyTables(
		Connection source,
		Connection target,
		List<TablePlan> tables
	) throws SQLException {
		Set<TablePlan> remaining = new LinkedHashSet<>(tables);
		SQLException lastFailure = null;
		while (!remaining.isEmpty()) {
			boolean progressed = false;
			for (TablePlan table : List.copyOf(remaining)) {
				Savepoint savepoint = target.setSavepoint();
				try {
					copyTable(source, target, table);
					target.releaseSavepoint(savepoint);
					remaining.remove(table);
					progressed = true;
				}
				catch (SQLException exception) {
					target.rollback(savepoint);
					lastFailure = exception;
				}
			}
			if (!progressed) throw lastFailure == null ? new SQLException("無法決定資料表順序") : lastFailure;
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void copyTable(Connection source, Connection target, TablePlan plan) throws SQLException {
		String columns = String.join(", ", plan.columns());
		String placeholders = String.join(", ", java.util.Collections.nCopies(plan.columns().size(), "?"));
		try (
			Statement select = source.createStatement();
			ResultSet rows = select.executeQuery("SELECT " + columns + " FROM " + plan.name());
			PreparedStatement insert = target.prepareStatement(
				"INSERT INTO " + plan.name() + " (" + columns + ") VALUES ("
					+ placeholders + ") ON CONFLICT DO NOTHING"
			)
		) {
			int batchSize = 0;
			while (rows.next()) {
				for (int index = 0; index < plan.columns().size(); index++) {
					insert.setObject(index + 1, rows.getObject(index + 1));
				}
				insert.addBatch();
				if (++batchSize == 500) {
					insert.executeBatch();
					batchSize = 0;
				}
			}
			if (batchSize > 0) insert.executeBatch();
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private List<FilePlan> filePlans(Connection source) throws SQLException, IOException {
		List<FilePlan> plans = new ArrayList<>();
		collectFiles(source, plans, "asset", "id", "file_path", "content_type", assetRoot, "assets");
		collectFiles(
			source,
			plans,
			"pending_image",
			"message_id",
			"staging_path",
			"content_type",
			assetRoot,
			"pending"
		);
		if (outputRoot != null) {
			collectFiles(
				source,
				plans,
				"quotation_file",
				"id",
				"relative_path",
				"content_type",
				outputRoot,
				"quotations"
			);
		}
		plans.sort(Comparator.comparing(FilePlan::sourceIdentity));
		return plans;
	}

	// 方法：執行此方法定義的受控處理流程。
	private void collectFiles(
		Connection source,
		List<FilePlan> plans,
		String table,
		String idColumn,
		String locatorColumn,
		String contentTypeColumn,
		Path root,
		String category
	) throws SQLException, IOException {
		if (!tableExists(source, table) || !columnExists(source, table, locatorColumn)) return;

		String sql = "SELECT " + idColumn + ", " + locatorColumn + ", " + contentTypeColumn
			+ " FROM " + table + " WHERE " + locatorColumn + " IS NOT NULL";
		try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				String identity = safeIdentity(rows.getString(1));
				Path path = safeSource(root, rows.getString(2));
				byte[] content = Files.readAllBytes(path);
				String hash = sha256(content);
				plans.add(new FilePlan(
					table,
					identity,
					rows.getString(3),
					path,
					"migration/" + category + "/" + identity + "/" + hash + extension(path),
					hash,
					content.length
				));
			}
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private int copyFiles(
		Connection target,
		List<FilePlan> files,
		List<String> createdObjects
	) throws SQLException, IOException {
		int copied = 0;
		for (FilePlan file : files) {
			if (alreadyVerified(target, file)) continue;

			byte[] content = Files.readAllBytes(file.path());
			StoredObject stored = objectStorage.put(
				file.objectKey(),
				content,
				file.contentType(),
				Map.of("migration-source", file.table())
			);
			createdObjects.add(file.objectKey());
			StoredObject verified = objectStorage.metadata(file.objectKey());
			if (!file.sha256().equals(verified.sha256()) || file.size() != verified.contentLength()) {
				throw failure("OBJECT_VERIFICATION_FAILED");
			}
			updateTarget(target, file, stored);
			updateLedger(target, file, stored);
			copied++;
		}
		return copied;
	}

	// 方法：執行此方法定義的受控處理流程。
	private boolean alreadyVerified(Connection target, FilePlan file) throws SQLException {
		try (PreparedStatement statement = target.prepareStatement("""
			SELECT COUNT(*) FROM storage_migration_ledger
			WHERE company_id = ? AND source_type = ? AND source_identity = ?
			  AND status = 'VERIFIED' AND content_hash = ?
			""")) {
			statement.setString(1, company.id());
			statement.setString(2, file.table());
			statement.setString(3, file.sourceIdentity());
			statement.setString(4, file.sha256());
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getLong(1) == 1;
			}
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void updateTarget(Connection target, FilePlan file, StoredObject stored) throws SQLException {
		String sql = switch (file.table()) {
			case "asset" -> """
				UPDATE asset SET company_id = ?, object_key = ?, object_version = ?, content_hash = ?
				WHERE id = ?
				""";
			case "pending_image" -> "UPDATE pending_image SET staging_object_key = ? WHERE message_id = ?";
			case "quotation_file" -> """
				UPDATE quotation_file SET object_key = ?, object_version = ?, content_hash = ?
				WHERE id = ?
				""";
			default -> throw failure("UNSUPPORTED_FILE_TABLE");

		};
		try (PreparedStatement statement = target.prepareStatement(sql)) {
			if ("asset".equals(file.table())) {
				statement.setString(1, company.id());
				statement.setString(2, file.objectKey());
				statement.setString(3, stored.versionId());
				statement.setString(4, file.sha256());
				statement.setString(5, file.sourceIdentity());
			}
			else if ("pending_image".equals(file.table())) {
				statement.setString(1, file.objectKey());
				statement.setString(2, file.sourceIdentity());
			}
			else {
				statement.setString(1, file.objectKey());
				statement.setString(2, stored.versionId());
				statement.setString(3, file.sha256());
				statement.setString(4, file.sourceIdentity());
			}
			if (statement.executeUpdate() != 1) throw failure("TARGET_FILE_ROW_NOT_FOUND");
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void updateLedger(Connection target, FilePlan file, StoredObject stored) throws SQLException {
		try (PreparedStatement statement = target.prepareStatement("""
			INSERT INTO storage_migration_ledger (
				company_id, source_type, source_identity, target_identity, content_hash, status
			) VALUES (?, ?, ?, ?, ?, 'VERIFIED')
			ON CONFLICT (company_id, source_type, source_identity) DO UPDATE SET
				target_identity = excluded.target_identity,
				content_hash = excluded.content_hash,
				status = excluded.status,
				error_code = NULL,
				updated_at = CAST(CURRENT_TIMESTAMP AS TEXT)
			""")) {
			statement.setString(1, company.id());
			statement.setString(2, file.table());
			statement.setString(3, file.sourceIdentity());
			statement.setString(4, file.objectKey());
			statement.setString(5, stored.sha256());
			statement.executeUpdate();
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void verifyCounts(Connection target, Map<String, Long> sourceCounts) throws SQLException {
		for (Map.Entry<String, Long> entry : sourceCounts.entrySet()) {
			if (count(target, entry.getKey()) < entry.getValue()) {
				throw failure("ROW_COUNT_MISMATCH_" + entry.getKey().toUpperCase());
			}
		}
	}

	// 方法：顯式保留舊主鍵後，將 PostgreSQL serial sequence 推進到目前最大值。
	private void resetSequences(Connection target, List<TablePlan> tables) throws SQLException {
		for (TablePlan table : tables) {
			if (!table.columns().contains("id")) continue;

			try (PreparedStatement query = target.prepareStatement(
				"SELECT pg_get_serial_sequence(?, 'id')"
			)) {
				query.setString(1, table.name());
				try (ResultSet result = query.executeQuery()) {
					if (!result.next() || result.getString(1) == null) continue;

					String sequence = result.getString(1).replace("'", "''");
					try (Statement update = target.createStatement()) {
						update.execute("""
							SELECT setval(
								'%s',
								COALESCE((SELECT MAX(id) FROM %s), 1),
								EXISTS (SELECT 1 FROM %s)
							)
							""".formatted(sequence, table.name(), table.name()));
					}
				}
			}
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private long count(Connection connection, String table) throws SQLException {
		try (
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + identifier(table))
		) {
			if (!result.next()) throw failure("COUNT_FAILED");

			return result.getLong(1);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private boolean tableExists(Connection connection, String table) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?
			""")) {
			statement.setString(1, table);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getLong(1) == 1;
			}
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private boolean columnExists(Connection connection, String table, String column) throws SQLException {
		try (
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("PRAGMA table_info(" + identifier(table) + ")")
		) {
			while (result.next()) {
				if (column.equalsIgnoreCase(result.getString("name"))) return true;
			}
			return false;
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private Path safeSource(Path root, String locator) {
		if (locator == null || locator.isBlank()) throw failure("EMPTY_FILE_LOCATOR");

		Path relative = Path.of(locator);
		if (relative.isAbsolute()) throw failure("ABSOLUTE_FILE_LOCATOR");

		Path resolved = root.resolve(relative).normalize();
		if (!resolved.startsWith(root) || !Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
			throw failure("SOURCE_FILE_NOT_FOUND");
		}
		return resolved;
	}

	// 方法：執行此方法定義的受控處理流程。
	private String identifier(String value) {
		if (value == null || !value.matches("[a-z_][a-z0-9_]*")) {
			throw failure("UNSAFE_DATABASE_IDENTIFIER");
		}
		return value;
	}

	// 方法：執行此方法定義的受控處理流程。
	private String safeIdentity(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
			throw failure("UNSAFE_SOURCE_IDENTITY");
		}
		return value;
	}

	// 方法：執行此方法定義的受控處理流程。
	private String extension(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		if (dot < 0) return "";

		String extension = name.substring(dot).toLowerCase();
		return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
	}

	// 方法：執行此方法定義的受控處理流程。
	private String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境不支援 SHA-256", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void writeReport(MigrationReport report) {
		try {
			Path parent = reportPath.getParent();
			if (parent != null) Files.createDirectories(parent);
			Files.write(reportPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
		}
		catch (IOException exception) {
			throw failure("REPORT_WRITE_FAILED", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private String safeCode(Exception exception) {
		if (exception instanceof MigrationException migrationException) return migrationException.code;

		return exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
	}

	// 方法：執行此方法定義的受控處理流程。
	private static Path requiredPath(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不可留空");

		return Path.of(value).toAbsolutePath().normalize();
	}

	// 方法：執行此方法定義的受控處理流程。
	private static Path optionalPath(String value) {
		return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
	}

	// 方法：執行此方法定義的受控處理流程。
	private MigrationException failure(String code) {
		return new MigrationException(code, null);
	}

	// 方法：執行此方法定義的受控處理流程。
	private MigrationException failure(String code, Throwable cause) {
		return new MigrationException(code, cause);
	}

	private record TablePlan(String name, List<String> columns) {}

	private record FilePlan(
		String table,
		String sourceIdentity,
		String contentType,
		Path path,
		String objectKey,
		String sha256,
		long size
	) {}

	public record MigrationReport(
		String mode,
		String status,
		Map<String, Long> sourceTableCounts,
		int discoveredFiles,
		int copiedFiles,
		List<String> errorCodes
	) {}

	private static final class MigrationException extends RuntimeException {

		private final String code;

		// 方法：執行此方法定義的受控處理流程。
		private MigrationException(String code, Throwable cause) {
			super(code, cause);
			this.code = code;
		}
	}
}
