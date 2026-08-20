package dev.myudog.assetsmanagerlinebot.config;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationSchemaMigratorTest {

	@TempDir
	Path temporaryDirectory;

	// 測試：舊版資料庫遷移後允許第 100 張報價，且既有明細外鍵與資料不遺失。
	@Test
	void expandsSequenceCapacityWithoutBreakingQuotationChildren() throws Exception {
		SQLiteConfig config = new SQLiteConfig();
		config.enforceForeignKeys(true);
		SQLiteDataSource dataSource = new SQLiteDataSource(config);
		dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("sequence-capacity.db"));
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE quotation_draft (id INTEGER PRIMARY KEY, company_name TEXT)");
			statement.execute("CREATE TABLE quotation_scheme (id INTEGER PRIMARY KEY)");
			statement.execute("CREATE TABLE quotation_template (id INTEGER PRIMARY KEY)");
			statement.execute("CREATE TABLE quotation_request (id INTEGER PRIMARY KEY)");
			statement.execute("""
				CREATE TABLE quotation_request_image (
					id INTEGER PRIMARY KEY,
					request_id INTEGER,
					UNIQUE (id, request_id)
				)
				""");
			statement.execute("INSERT INTO quotation_draft (id, company_name) VALUES (1, 'Existing')");
			statement.execute("INSERT INTO quotation_scheme (id) VALUES (1)");
			statement.execute("INSERT INTO quotation_template (id) VALUES (1)");
			statement.execute("""
				CREATE TABLE quotation (
					id INTEGER PRIMARY KEY AUTOINCREMENT, request_id INTEGER, draft_id INTEGER UNIQUE,
					revision INTEGER NOT NULL DEFAULT 1, quotation_no TEXT UNIQUE,
					quotation_name TEXT NOT NULL, sequence_date TEXT,
					sequence_number INTEGER CHECK (sequence_number BETWEEN 1 AND 99),
					company_name TEXT, work_name TEXT, quotation_date TEXT, valid_until TEXT,
					scheme_id INTEGER NOT NULL, template_id INTEGER NOT NULL,
					selected_request_image_id INTEGER, customer_name TEXT, customer_phone TEXT,
					customer_fax TEXT, customer_email TEXT, contact_name TEXT,
					project_location TEXT, sales_representative TEXT,
					currency TEXT NOT NULL DEFAULT 'TWD', subtotal NUMERIC NOT NULL DEFAULT 0,
					tax_rate NUMERIC NOT NULL DEFAULT 0.05, tax_amount NUMERIC NOT NULL DEFAULT 0,
					total_amount NUMERIC NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'CONFIRMED',
					output_path TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, exported_at TEXT,
					UNIQUE (sequence_date, sequence_number),
					FOREIGN KEY (draft_id) REFERENCES quotation_draft (id),
					FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id),
					FOREIGN KEY (template_id) REFERENCES quotation_template (id)
				)
				""");
			statement.execute("""
				INSERT INTO quotation (
					id, draft_id, quotation_no, quotation_name, sequence_date, sequence_number,
					scheme_id, template_id, status
				)
				VALUES (1, 1, '2026081199', 'Existing quotation', '2026-08-11', 99, 1, 1, 'CONFIRMED')
				""");
			statement.execute("""
				CREATE TABLE quotation_line (
					id INTEGER PRIMARY KEY,
					quotation_id INTEGER NOT NULL,
					FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE
				)
				""");
			statement.execute("INSERT INTO quotation_line (id, quotation_id) VALUES (1, 1)");
			statement.execute("""
				CREATE TABLE quotation_daily_sequence (
					sequence_date TEXT PRIMARY KEY,
					last_sequence INTEGER NOT NULL CHECK (last_sequence BETWEEN 0 AND 99),
					updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
				)
				""");
			statement.execute("INSERT INTO quotation_daily_sequence (sequence_date, last_sequence) VALUES ('2026-08-11', 99)");
		}

		QuotationSchemaMigrator.migrate(dataSource);

		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("UPDATE quotation SET sequence_number = 100 WHERE id = 1");
			statement.execute("UPDATE quotation_daily_sequence SET last_sequence = 100 WHERE sequence_date = '2026-08-11'");
			try (ResultSet resultSet = statement.executeQuery("SELECT quotation_id FROM quotation_line WHERE id = 1")) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getLong("quotation_id")).isEqualTo(1);
			}
			try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
				assertThat(resultSet.next()).isFalse();
			}
		}
	}

	// 驗證真實舊版 SQLite 草稿升級後仍保留資料，並可新增空白數量與臨時品項。
	@Test
	void migratesLegacyDraftWithoutLosingData() throws Exception {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("legacy-assets.db"));
		// 外部呼叫：建立最小但結構真實的舊版報價資料庫。
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE quotation_scheme (
					id INTEGER PRIMARY KEY,
					code TEXT NOT NULL UNIQUE
				)
				""");
			statement.execute("""
				CREATE TABLE quotation_item (
					id INTEGER PRIMARY KEY,
					code TEXT NOT NULL UNIQUE,
					name TEXT NOT NULL
				)
				""");
			statement.execute("""
				CREATE TABLE quotation_scheme_item (
					id INTEGER PRIMARY KEY,
					scheme_id INTEGER NOT NULL,
					item_id INTEGER NOT NULL,
					specification TEXT,
					unit TEXT NOT NULL,
					unit_price NUMERIC NOT NULL,
					remark TEXT
				)
				""");
			statement.execute("""
				CREATE TABLE quotation_draft (
					id INTEGER PRIMARY KEY AUTOINCREMENT,
					draft_key TEXT NOT NULL UNIQUE,
					source_type TEXT NOT NULL,
					source_id TEXT NOT NULL,
					requester_id TEXT,
					quotation_name TEXT,
					scheme_id INTEGER,
					status TEXT NOT NULL DEFAULT 'COLLECTING'
						CHECK (status IN ('COLLECTING', 'REVIEW_REQUIRED', 'READY', 'CONFIRMED', 'CANCELLED')),
					revision INTEGER NOT NULL DEFAULT 1,
					expires_at TEXT,
					created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
					updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
				)
				""");
			statement.execute("""
				CREATE TABLE quotation_draft_item (
					id INTEGER PRIMARY KEY AUTOINCREMENT,
					draft_id INTEGER NOT NULL,
					item_id INTEGER NOT NULL,
					quantity NUMERIC NOT NULL CHECK (quantity > 0),
					source_text TEXT,
					confidence NUMERIC,
					display_order INTEGER NOT NULL DEFAULT 0,
					created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
					updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
					UNIQUE (draft_id, item_id)
				)
				""");
			statement.execute("INSERT INTO quotation_scheme (id, code) VALUES (1, 'GENERAL')");
			statement.execute("INSERT INTO quotation_item (id, code, name) VALUES (1, 'EXTERNAL_SCAFFOLD', '外部鷹架')");
			statement.execute("""
				INSERT INTO quotation_scheme_item (
					id, scheme_id, item_id, specification, unit, unit_price, remark
				)
				VALUES (1, 1, 1, '(一般料)', 'm2', 180, '(實做實算)')
				""");
			statement.execute("""
				INSERT INTO quotation_draft (
					id, draft_key, source_type, source_id, requester_id, quotation_name,
					scheme_id, status
				)
				VALUES (1, 'legacy-draft', 'user', 'U001', 'U001', '舊案件', 1, 'READY')
				""");
			statement.execute("""
				INSERT INTO quotation_draft_item (
					id, draft_id, item_id, quantity, source_text, confidence, display_order
				)
				VALUES (1, 1, 1, 3, '外架三平方', 0.98, 1)
				""");
		}

		QuotationSchemaMigrator.migrate(dataSource);

		// 外部呼叫：讀取遷移後資料並新增新版才允許的兩種明細。
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			try (ResultSet resultSet = statement.executeQuery("""
				SELECT d.status, di.item_kind, di.item_name_snapshot, di.quantity,
					di.unit_snapshot, di.unit_price_snapshot
				FROM quotation_draft d
				JOIN quotation_draft_item di ON di.draft_id = d.id
				WHERE d.draft_key = 'legacy-draft'
				""")) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getString("status")).isEqualTo("READY_FOR_PREVIEW");
				assertThat(resultSet.getString("item_kind")).isEqualTo("STANDARD");
				assertThat(resultSet.getString("item_name_snapshot")).isEqualTo("外部鷹架");
				assertThat(resultSet.getBigDecimal("quantity")).isEqualByComparingTo("3");
				assertThat(resultSet.getString("unit_snapshot")).isEqualTo("m2");
				assertThat(resultSet.getBigDecimal("unit_price_snapshot")).isEqualByComparingTo("180");
			}

			statement.execute("""
				INSERT INTO quotation_draft_item (
					draft_id, item_kind, item_id, client_item_id, item_name_snapshot,
					unit_snapshot, unit_price_snapshot, quantity, display_order
				)
				VALUES (1, 'CUSTOM', NULL, 'temporary-1', '臨時品項', NULL, NULL, NULL, 2)
				""");
			try (ResultSet resultSet = statement.executeQuery(
				"SELECT version FROM app_schema_version WHERE component = 'quotation'"
			)) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getInt("version")).isEqualTo(8);
			}
		}
	}

	// 測試：遷移會保留重複草稿，僅取消較舊者，並由資料庫阻止再次建立第二份進行中草稿。
	@Test
	void preservesDuplicateDraftsAndEnforcesOneActiveDraftPerUser() throws Exception {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("active-draft-uniqueness.db"));
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE quotation_draft (
					id INTEGER PRIMARY KEY AUTOINCREMENT,
					draft_key TEXT NOT NULL UNIQUE,
					source_type TEXT NOT NULL,
					source_id TEXT NOT NULL,
					requester_id TEXT,
					company_name TEXT,
					status TEXT NOT NULL,
					revision INTEGER NOT NULL DEFAULT 1,
					cancelled_at TEXT,
					created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
					updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
				)
				""");
			statement.execute("""
				INSERT INTO quotation_draft (
					id, draft_key, source_type, source_id, requester_id, status, updated_at
				)
				VALUES
					(1, 'older', 'user', 'U001', 'U001', 'COLLECTING_ITEMS', '2026-08-11 10:00:00'),
					(2, 'newer', 'user', 'U001', 'U001', 'READY_FOR_PREVIEW', '2026-08-11 11:00:00')
				""");
		}

		QuotationSchemaMigrator.migrate(dataSource);

		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			try (ResultSet resultSet = statement.executeQuery("""
				SELECT draft_key, status, cancelled_at
				FROM quotation_draft
				ORDER BY id
				""")) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getString("draft_key")).isEqualTo("older");
				assertThat(resultSet.getString("status")).isEqualTo("CANCELLED");
				assertThat(resultSet.getString("cancelled_at")).isNotBlank();
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getString("draft_key")).isEqualTo("newer");
				assertThat(resultSet.getString("status")).isEqualTo("READY_FOR_PREVIEW");
				assertThat(resultSet.next()).isFalse();
			}

			assertThatThrownBy(() -> statement.execute("""
				INSERT INTO quotation_draft (
					draft_key, source_type, source_id, requester_id, status
				)
				VALUES ('third-active', 'user', 'U001', 'U001', 'COLLECTING_BASE_INFO')
				"""))
				.isInstanceOf(java.sql.SQLException.class);

			statement.execute("""
				INSERT INTO quotation_draft (
					draft_key, source_type, source_id, requester_id, status
				)
				VALUES ('terminal', 'user', 'U001', 'U001', 'CANCELLED')
				""");
			statement.execute("UPDATE quotation_draft SET status = 'CANCELLED' WHERE draft_key = 'newer'");
		}

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Future<Boolean>> attempts = List.of(
				executor.submit(() -> insertConcurrentActiveDraft(dataSource, "parallel-a", ready, start)),
				executor.submit(() -> insertConcurrentActiveDraft(dataSource, "parallel-b", ready, start))
			);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(attempts.stream().map(future -> get(future)).filter(Boolean::booleanValue))
				.hasSize(1);
		}
	}

	// 方法：從獨立連線同時嘗試建立進行中草稿，唯一索引應只允許一筆成功。
	private static boolean insertConcurrentActiveDraft(
		SQLiteDataSource dataSource,
		String draftKey,
		CountDownLatch ready,
		CountDownLatch start
	) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) return false;

		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("""
				INSERT INTO quotation_draft (
					draft_key, source_type, source_id, requester_id, status
				)
				VALUES ('%s', 'user', 'U001', 'U001', 'COLLECTING_BASE_INFO')
				""".formatted(draftKey));
			return true;
		}
		catch (SQLException exception) {
			return false;
		}
	}

	// 方法：取得並行測試結果，將非同步例外轉成測試失敗。
	private static boolean get(Future<Boolean> future) {
		try {
			return future.get(5, TimeUnit.SECONDS);
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}
}
