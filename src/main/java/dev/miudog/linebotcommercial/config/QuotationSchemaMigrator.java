package dev.miudog.linebotcommercial.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 在 Spring 執行 schema.sql 前，將早期報價資料表升級為正式流程可用的結構。
 * 遷移只會補欄位或重建受舊版 NOT NULL 限制的明細表，既有主鍵與資料均保留。
 */
public final class QuotationSchemaMigrator {

	private static final String COMPONENT = "quotation";
	private static final int CURRENT_VERSION = 8;

	// 方法：禁止建立只有靜態遷移入口的工具類別。
	private QuotationSchemaMigrator() {
	}

	// 方法：以單一交易執行可重入的報價資料庫升級。
	public static void migrate(DataSource dataSource) throws SQLException {
		// 外部呼叫：向 HikariCP 取得 SQLite 連線以執行啟動遷移。
		try (Connection connection = dataSource.getConnection()) {
			boolean originalAutoCommit = connection.getAutoCommit();
			boolean originalForeignKeys = foreignKeysEnabled(connection);
			if (originalForeignKeys) execute(connection, "PRAGMA foreign_keys = OFF");
			// 外部呼叫：關閉自動提交，避免只完成一半的資料表重建。
			connection.setAutoCommit(false);

			try {
				ensureVersionTable(connection);

				if (currentVersion(connection) >= CURRENT_VERSION) {
					// 外部呼叫：沒有待套用版本時結束空交易。
					connection.rollback();
					return;
				}

				if (tableExists(connection, "quotation_draft")) {
					migrateDraft(connection);
					migrateActiveDraftUniqueness(connection);
					migrateEventReceipts(connection);
					migrateQuotationSnapshot(connection);
					migrateDurableGenerationSnapshot(connection);
					migrateSequenceCapacity(connection);
					migrateDraftItemMatchedName(connection);
				}

				verifyForeignKeys(connection);
				recordCurrentVersion(connection);
				// 外部呼叫：所有結構與資料轉換成功後一次提交。
				connection.commit();
			}
			catch (SQLException exception) {
				// 外部呼叫：遷移失敗時回復原資料庫狀態。
				connection.rollback();
				throw exception;
			}
			finally {
				// 外部呼叫：恢復連線原本的交易模式供連線池重用。
				connection.setAutoCommit(originalAutoCommit);
				if (originalForeignKeys) execute(connection, "PRAGMA foreign_keys = ON");
			}
		}
	}

	// 方法：保留所有歷史草稿，取消同一使用者較舊的進行中草稿，並建立資料庫唯一性約束。
	private static void migrateActiveDraftUniqueness(Connection connection) throws SQLException {
		if (!columnExists(connection, "quotation_draft", "source_type")
			|| !columnExists(connection, "quotation_draft", "source_id")
			|| !columnExists(connection, "quotation_draft", "status")
			|| !columnExists(connection, "quotation_draft", "updated_at")
			|| !columnExists(connection, "quotation_draft", "cancelled_at")) return;

		execute(connection, """
			WITH ranked_active_drafts AS (
				SELECT id,
					ROW_NUMBER() OVER (
						PARTITION BY source_id
						ORDER BY updated_at DESC, id DESC
					) AS active_position
				FROM quotation_draft
				WHERE source_type = 'user'
					AND status IN (
						'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
						'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION'
					)
			)
			UPDATE quotation_draft
			SET status = 'CANCELLED',
				cancelled_at = COALESCE(cancelled_at, CURRENT_TIMESTAMP),
				updated_at = CURRENT_TIMESTAMP
			WHERE id IN (
				SELECT id FROM ranked_active_drafts WHERE active_position > 1
			)
			""");
		execute(connection, """
			CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_draft_active_user
			ON quotation_draft (source_id)
			WHERE source_type = 'user'
				AND status IN (
					'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
					'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION'
				)
			""");
	}

	// 方法：為草稿明細加入 AI 名稱近似對應的使用者原文，供預覽向使用者確認。
	private static void migrateDraftItemMatchedName(Connection connection) throws SQLException {
		if (!tableExists(connection, "quotation_draft_item")) return;

		if (!columnExists(connection, "quotation_draft_item", "matched_name")) {
			execute(connection, "ALTER TABLE quotation_draft_item ADD COLUMN matched_name TEXT");
		}
	}

	// 方法：為 webhook 收件紀錄加入可在程序中斷後回收的租約與嘗試次數。
	private static void migrateEventReceipts(Connection connection) throws SQLException {
		if (!tableExists(connection, "quotation_event_receipt")) return;

		if (!columnExists(connection, "quotation_event_receipt", "lease_until")) {
			execute(connection, "ALTER TABLE quotation_event_receipt ADD COLUMN lease_until TEXT");
		}
		if (!columnExists(connection, "quotation_event_receipt", "attempt_count")) {
			execute(connection, "ALTER TABLE quotation_event_receipt ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0)");
		}
	}

	// 方法：建立各元件共用的 schema 版本紀錄表。
	private static void ensureVersionTable(Connection connection) throws SQLException {
		execute(connection, """
			CREATE TABLE IF NOT EXISTS app_schema_version (
				component  TEXT PRIMARY KEY,
				version    INTEGER NOT NULL CHECK (version > 0),
				applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
			)
			""");
	}

	// 方法：讀取目前已套用的報價 schema 版本。
	private static int currentVersion(Connection connection) throws SQLException {
		// 外部呼叫：查詢報價元件目前的資料庫版本。
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT version FROM app_schema_version WHERE component = ?"
		)) {
			statement.setString(1, COMPONENT);
			// 外部呼叫：執行版本查詢。
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getInt("version") : 0;
			}
		}
	}

	// 方法：升級草稿抬頭、狀態欄位與可空數量明細。
	private static void migrateDraft(Connection connection) throws SQLException {
		if (!columnExists(connection, "quotation_draft", "company_name")) {
			execute(connection, "DROP INDEX IF EXISTS idx_quotation_draft_owner");
			execute(connection, "ALTER TABLE quotation_draft RENAME COLUMN status TO legacy_status");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN company_name TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN work_name TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN contact_name TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN customer_phone TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN customer_email TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN project_location TEXT");
			execute(connection, """
				ALTER TABLE quotation_draft ADD COLUMN status TEXT NOT NULL DEFAULT 'COLLECTING_BASE_INFO'
				CHECK (status IN (
					'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
					'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION', 'CONFIRMED',
					'CANCELLED', 'EXPIRED'
				))
				""");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN image_declined INTEGER NOT NULL DEFAULT 0 CHECK (image_declined IN (0, 1))");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN confirmation_revision INTEGER");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN confirmed_at TEXT");
			execute(connection, "ALTER TABLE quotation_draft ADD COLUMN cancelled_at TEXT");
			execute(connection, """
				UPDATE quotation_draft
				SET status = CASE legacy_status
					WHEN 'REVIEW_REQUIRED' THEN 'COLLECTING_ITEMS'
					WHEN 'READY' THEN 'READY_FOR_PREVIEW'
					WHEN 'CONFIRMED' THEN 'CONFIRMED'
					WHEN 'CANCELLED' THEN 'CANCELLED'
					ELSE 'COLLECTING_BASE_INFO'
				END
				""");
		}

		if (tableExists(connection, "quotation_draft_item")
			&& !columnExists(connection, "quotation_draft_item", "item_kind")) {
			rebuildDraftItems(connection);
		}
		if (tableExists(connection, "quotation_draft_item")
			&& !columnExists(connection, "quotation_draft_item", "client_item_id")) {
			rebuildDraftItemsWithClientIdentity(connection);
		}
	}

	// 方法：重建草稿明細以移除舊版 item_id 與 quantity 的 NOT NULL 限制。
	private static void rebuildDraftItems(Connection connection) throws SQLException {
		execute(connection, "ALTER TABLE quotation_draft_item RENAME TO quotation_draft_item_legacy_v1");
		execute(connection, """
			CREATE TABLE quotation_draft_item (
				id                     INTEGER PRIMARY KEY AUTOINCREMENT,
				draft_id               INTEGER NOT NULL,
				item_kind              TEXT NOT NULL DEFAULT 'STANDARD'
				                         CHECK (item_kind IN ('STANDARD', 'CUSTOM', 'SUMMARY')),
				item_id                 INTEGER,
				client_item_id          TEXT,
				item_code_snapshot      TEXT,
				item_name_snapshot      TEXT,
				specification_snapshot TEXT,
				quantity                NUMERIC CHECK (quantity IS NULL OR quantity > 0),
				unit_snapshot           TEXT,
				unit_price_snapshot     NUMERIC CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0),
				remark_snapshot         TEXT,
				source_text             TEXT,
				confidence              NUMERIC CHECK (confidence BETWEEN 0 AND 1),
				is_removed              INTEGER NOT NULL DEFAULT 0 CHECK (is_removed IN (0, 1)),
				display_order           INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
				created_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				updated_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				CHECK (
					(item_kind = 'STANDARD' AND item_id IS NOT NULL AND client_item_id IS NULL)
					OR (item_kind IN ('CUSTOM', 'SUMMARY') AND item_id IS NULL AND client_item_id IS NOT NULL)
				),
				UNIQUE (draft_id, item_id),
				UNIQUE (draft_id, client_item_id),
				FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE,
				FOREIGN KEY (item_id) REFERENCES quotation_item (id) ON DELETE RESTRICT
			)
			""");
		execute(connection, """
			INSERT INTO quotation_draft_item (
				id, draft_id, item_kind, item_id, item_code_snapshot, item_name_snapshot,
				specification_snapshot, quantity, unit_snapshot, unit_price_snapshot,
				remark_snapshot, source_text, confidence, display_order, created_at, updated_at
			)
			SELECT
				di.id, di.draft_id, 'STANDARD', di.item_id, i.code, i.name,
				si.specification, di.quantity, COALESCE(si.unit, ''), COALESCE(si.unit_price, 0),
				si.remark, di.source_text, di.confidence, di.display_order, di.created_at, di.updated_at
			FROM quotation_draft_item_legacy_v1 di
			JOIN quotation_item i ON i.id = di.item_id
			LEFT JOIN quotation_draft d ON d.id = di.draft_id
			LEFT JOIN quotation_scheme_item si
				ON si.scheme_id = d.scheme_id AND si.item_id = di.item_id
			""");
		execute(connection, "DROP TABLE quotation_draft_item_legacy_v1");
	}

	// 方法：重建現行草稿明細，加入可跨訊息合併的 clientItemId 並允許未完成動態品項。
	private static void rebuildDraftItemsWithClientIdentity(Connection connection) throws SQLException {
		execute(connection, "ALTER TABLE quotation_draft_item RENAME TO quotation_draft_item_legacy_v3");
		execute(connection, """
			CREATE TABLE quotation_draft_item (
				id                     INTEGER PRIMARY KEY AUTOINCREMENT,
				draft_id               INTEGER NOT NULL,
				item_kind              TEXT NOT NULL DEFAULT 'STANDARD'
				                         CHECK (item_kind IN ('STANDARD', 'CUSTOM', 'SUMMARY')),
				item_id                 INTEGER,
				client_item_id          TEXT,
				item_code_snapshot      TEXT,
				item_name_snapshot      TEXT,
				specification_snapshot TEXT,
				quantity                NUMERIC CHECK (quantity IS NULL OR quantity > 0),
				unit_snapshot           TEXT,
				unit_price_snapshot     NUMERIC CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0),
				remark_snapshot         TEXT,
				source_text             TEXT,
				confidence              NUMERIC CHECK (confidence BETWEEN 0 AND 1),
				is_removed              INTEGER NOT NULL DEFAULT 0 CHECK (is_removed IN (0, 1)),
				display_order           INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
				created_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				updated_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				CHECK (
					(item_kind = 'STANDARD' AND item_id IS NOT NULL AND client_item_id IS NULL)
					OR (item_kind IN ('CUSTOM', 'SUMMARY') AND item_id IS NULL AND client_item_id IS NOT NULL)
				),
				UNIQUE (draft_id, item_id),
				UNIQUE (draft_id, client_item_id),
				FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE,
				FOREIGN KEY (item_id) REFERENCES quotation_item (id) ON DELETE RESTRICT
			)
			""");
		execute(connection, """
			INSERT INTO quotation_draft_item (
				id, draft_id, item_kind, item_id, client_item_id, item_code_snapshot,
				item_name_snapshot, specification_snapshot, quantity, unit_snapshot,
				unit_price_snapshot, remark_snapshot, source_text, confidence, is_removed,
				display_order, created_at, updated_at
			)
			SELECT
				id, draft_id, item_kind, item_id,
				CASE WHEN item_kind IN ('CUSTOM', 'SUMMARY') THEN 'legacy-' || id ELSE NULL END,
				item_code_snapshot, item_name_snapshot, specification_snapshot, quantity,
				unit_snapshot, unit_price_snapshot, remark_snapshot, source_text, confidence,
				is_removed, display_order, created_at, updated_at
			FROM quotation_draft_item_legacy_v3
			""");
		execute(connection, "DROP TABLE quotation_draft_item_legacy_v3");
	}

	// 方法：升級正式報價快照與明細，保留舊報價內容與識別碼。
	private static void migrateQuotationSnapshot(Connection connection) throws SQLException {
		if (!tableExists(connection, "quotation") || columnExists(connection, "quotation", "draft_id")) return;

		execute(connection, "ALTER TABLE quotation_line RENAME TO quotation_line_legacy_v1");
		execute(connection, "ALTER TABLE quotation RENAME TO quotation_legacy_v1");
		createQuotationTable(connection);
		copyLegacyQuotations(connection);
		createQuotationLineTable(connection);
		copyLegacyQuotationLines(connection);
		execute(connection, "DROP TABLE quotation_line_legacy_v1");
		execute(connection, "DROP TABLE quotation_legacy_v1");
	}

	// 方法：建立新版正式報價快照表。
	private static void createQuotationTable(Connection connection) throws SQLException {
		execute(connection, """
			CREATE TABLE quotation (
				id INTEGER PRIMARY KEY AUTOINCREMENT, request_id INTEGER, draft_id INTEGER UNIQUE,
				revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0), quotation_no TEXT UNIQUE,
				quotation_name TEXT NOT NULL, sequence_date TEXT,
				sequence_number INTEGER CHECK (sequence_number IS NULL OR sequence_number >= 1),
				company_name TEXT, work_name TEXT, quotation_date TEXT, valid_until TEXT,
				scheme_id INTEGER NOT NULL, template_id INTEGER NOT NULL, selected_request_image_id INTEGER,
				customer_name TEXT, customer_phone TEXT, customer_fax TEXT, customer_email TEXT,
				contact_name TEXT, project_location TEXT, sales_representative TEXT,
				additional_header TEXT,
				currency TEXT NOT NULL DEFAULT 'TWD', subtotal NUMERIC NOT NULL DEFAULT 0,
				tax_rate NUMERIC NOT NULL DEFAULT 0.05 CHECK (tax_rate >= 0),
				tax_amount NUMERIC NOT NULL DEFAULT 0, total_amount NUMERIC NOT NULL DEFAULT 0,
				status TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN (
					'CONFIRMED', 'GENERATING_EXCEL', 'GENERATING_PDF', 'PDF_FAILED',
					'READY', 'SENDING', 'SENT', 'CANCELLED', 'FAILED'
				)),
				output_path TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, exported_at TEXT,
				UNIQUE (request_id, revision), UNIQUE (sequence_date, sequence_number),
				FOREIGN KEY (request_id) REFERENCES quotation_request (id) ON DELETE RESTRICT,
				FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE RESTRICT,
				FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT,
				FOREIGN KEY (template_id) REFERENCES quotation_template (id) ON DELETE RESTRICT,
				FOREIGN KEY (selected_request_image_id, request_id)
					REFERENCES quotation_request_image (id, request_id) ON DELETE RESTRICT
			)
			""");
	}

	// 方法：補齊背景工作者重啟後重建報表所需的不可變抬頭欄位。
	private static void migrateDurableGenerationSnapshot(Connection connection) throws SQLException {
		if (!tableExists(connection, "quotation")) return;

		if (!columnExists(connection, "quotation", "customer_fax")) {
			execute(connection, "ALTER TABLE quotation ADD COLUMN customer_fax TEXT");
		}
		if (!columnExists(connection, "quotation", "contact_name")) {
			execute(connection, "ALTER TABLE quotation ADD COLUMN contact_name TEXT");
		}
		if (!columnExists(connection, "quotation", "sales_representative")) {
			execute(connection, "ALTER TABLE quotation ADD COLUMN sales_representative TEXT");
		}
		if (!columnExists(connection, "quotation", "additional_header")) {
			execute(connection, "ALTER TABLE quotation ADD COLUMN additional_header TEXT");
		}
	}

	// 方法：將舊版正式報價映射為新版可重試狀態。
	// 方法：移除每日兩位數流水號的資料庫上限，並保留既有正式報價與外鍵關聯。
	private static void migrateSequenceCapacity(Connection connection) throws SQLException {
		String quotationSql = tableSql(connection, "quotation");
		if (quotationSql != null && quotationSql.contains("BETWEEN 1 AND 99")) {
			execute(connection, "PRAGMA legacy_alter_table = ON");
			try {
				execute(connection, "ALTER TABLE quotation RENAME TO quotation_legacy_v5");
				createQuotationTable(connection);
				execute(connection, """
					INSERT INTO quotation (
						id, request_id, draft_id, revision, quotation_no, quotation_name,
						sequence_date, sequence_number, company_name, work_name, quotation_date,
						valid_until, scheme_id, template_id, selected_request_image_id,
						customer_name, customer_phone, customer_fax, customer_email,
						contact_name, project_location, sales_representative, additional_header,
						currency, subtotal, tax_rate, tax_amount, total_amount, status,
						output_path, created_at, exported_at
					)
					SELECT
						id, request_id, draft_id, revision, quotation_no, quotation_name,
						sequence_date, sequence_number, company_name, work_name, quotation_date,
						valid_until, scheme_id, template_id, selected_request_image_id,
						customer_name, customer_phone, customer_fax, customer_email,
						contact_name, project_location, sales_representative, additional_header,
						currency, subtotal, tax_rate, tax_amount, total_amount, status,
						output_path, created_at, exported_at
					FROM quotation_legacy_v5
					""");
				execute(connection, "DROP TABLE quotation_legacy_v5");
				execute(connection, "CREATE INDEX IF NOT EXISTS idx_quotation_request_revision ON quotation (request_id, revision)");
			}
			finally {
				execute(connection, "PRAGMA legacy_alter_table = OFF");
			}
		}

		String sequenceSql = tableSql(connection, "quotation_daily_sequence");
		if (sequenceSql != null && sequenceSql.contains("BETWEEN 0 AND 99")) {
			execute(connection, "ALTER TABLE quotation_daily_sequence RENAME TO quotation_daily_sequence_legacy_v5");
			execute(connection, """
				CREATE TABLE quotation_daily_sequence (
					sequence_date TEXT PRIMARY KEY,
					last_sequence INTEGER NOT NULL CHECK (last_sequence >= 0),
					updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
				)
				""");
			execute(connection, """
				INSERT INTO quotation_daily_sequence (sequence_date, last_sequence, updated_at)
				SELECT sequence_date, last_sequence, updated_at
				FROM quotation_daily_sequence_legacy_v5
				""");
			execute(connection, "DROP TABLE quotation_daily_sequence_legacy_v5");
		}
	}

	// 方法：將第一版正式報價資料複製到新版不可變快照表。
	private static void copyLegacyQuotations(Connection connection) throws SQLException {
		execute(connection, """
			INSERT INTO quotation (
				id, request_id, revision, quotation_no, quotation_name, scheme_id, template_id,
				selected_request_image_id, customer_name, customer_phone, customer_email,
				project_location, currency, subtotal, tax_rate, tax_amount, total_amount,
				status, output_path, created_at, exported_at
			)
			SELECT
				id, request_id, revision, quotation_no, quotation_name, scheme_id, template_id,
				selected_request_image_id, customer_name, customer_phone, customer_email,
				project_location, currency, subtotal, tax_rate, tax_amount, total_amount,
				CASE status WHEN 'EXPORTED' THEN 'READY' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE 'CONFIRMED' END,
				output_path, created_at, exported_at
			FROM quotation_legacy_v1
			""");
	}

	// 方法：建立可保留空白數量與空白複價的新版報價明細表。
	private static void createQuotationLineTable(Connection connection) throws SQLException {
		execute(connection, """
			CREATE TABLE quotation_line (
				id INTEGER PRIMARY KEY AUTOINCREMENT, quotation_id INTEGER NOT NULL,
				line_number INTEGER NOT NULL CHECK (line_number > 0),
				line_kind TEXT NOT NULL DEFAULT 'STANDARD'
					CHECK (line_kind IN ('STANDARD', 'CUSTOM', 'ADJUSTMENT', 'SUMMARY')),
				visibility TEXT NOT NULL DEFAULT 'CUSTOMER' CHECK (visibility IN ('CUSTOMER', 'INTERNAL')),
				source_scheme_item_id INTEGER, source_rule_id INTEGER, item_code_snapshot TEXT,
				item_name_snapshot TEXT NOT NULL, specification_snapshot TEXT,
				quantity NUMERIC CHECK (quantity IS NULL OR quantity > 0), unit_snapshot TEXT NOT NULL,
				unit_price_snapshot NUMERIC NOT NULL, line_amount NUMERIC, remark_snapshot TEXT,
				source_text TEXT, calculation_detail_json TEXT
					CHECK (calculation_detail_json IS NULL OR json_valid(calculation_detail_json)),
				created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				UNIQUE (quotation_id, line_number),
				FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE,
				FOREIGN KEY (source_scheme_item_id) REFERENCES quotation_scheme_item (id) ON DELETE SET NULL,
				FOREIGN KEY (source_rule_id) REFERENCES quotation_rule (id) ON DELETE SET NULL
			)
			""");
	}

	// 方法：複製舊報價明細並轉換列種類名稱。
	private static void copyLegacyQuotationLines(Connection connection) throws SQLException {
		execute(connection, """
			INSERT INTO quotation_line (
				id, quotation_id, line_number, line_kind, visibility, source_scheme_item_id,
				source_rule_id, item_code_snapshot, item_name_snapshot, specification_snapshot,
				quantity, unit_snapshot, unit_price_snapshot, line_amount, remark_snapshot,
				source_text, calculation_detail_json, created_at
			)
			SELECT
				id, quotation_id, line_number,
				CASE line_kind WHEN 'ITEM' THEN 'STANDARD' ELSE line_kind END,
				visibility, source_scheme_item_id, source_rule_id, item_code_snapshot,
				item_name_snapshot, specification_snapshot, quantity, unit_snapshot,
				unit_price_snapshot, line_amount, remark_snapshot, source_text,
				calculation_detail_json, created_at
			FROM quotation_line_legacy_v1
			""");
	}

	// 方法：寫入目前報價 schema 版本。
	private static void recordCurrentVersion(Connection connection) throws SQLException {
		// 外部呼叫：以 UPSERT 記錄已成功套用的報價 schema 版本。
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO app_schema_version (component, version, applied_at)
			VALUES (?, ?, CURRENT_TIMESTAMP)
			ON CONFLICT (component) DO UPDATE SET
				version = excluded.version,
				applied_at = excluded.applied_at
			""")) {
			statement.setString(1, COMPONENT);
			statement.setInt(2, CURRENT_VERSION);
			// 外部呼叫：更新版本紀錄。
			statement.executeUpdate();
		}
	}

	// 方法：判斷指定資料表是否存在。
	// 方法：讀取連線目前是否啟用 SQLite 外鍵檢查。
	private static boolean foreignKeysEnabled(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
			return resultSet.next() && resultSet.getInt(1) == 1;
		}
	}

	// 方法：遷移提交前驗證所有外鍵仍指向有效資料。
	private static void verifyForeignKeys(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (resultSet.next()) {
				throw new SQLException("Quotation schema migration produced an invalid foreign key");
			}
		}
	}

	// 方法：讀取 SQLite 資料表的原始建表 SQL，以判斷是否仍帶有舊限制。
	private static String tableSql(Connection connection, String tableName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?"
		)) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getString("sql") : null;
			}
		}
	}

	// 方法：確認指定 SQLite 資料表是否存在。
	private static boolean tableExists(Connection connection, String tableName) throws SQLException {
		// 外部呼叫：查詢 SQLite 系統目錄中的資料表名稱。
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
		)) {
			statement.setString(1, tableName);
			// 外部呼叫：執行資料表存在性查詢。
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	// 方法：判斷指定資料表是否已有目標欄位。
	private static boolean columnExists(
		Connection connection,
		String tableName,
		String columnName
	) throws SQLException {
		String safeTableName = tableName.replace("'", "''");
		// 外部呼叫：讀取 SQLite 資料表欄位資訊。
		try (Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("PRAGMA table_info('" + safeTableName + "')")) {
			while (resultSet.next()) {
				if (columnName.equals(resultSet.getString("name"))) return true;
			}
			return false;
		}
	}

	// 方法：執行不含使用者輸入的固定遷移敘述。
	private static void execute(Connection connection, String sql) throws SQLException {
		// 外部呼叫：執行受版本控制的固定 SQLite DDL 或資料轉換敘述。
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
