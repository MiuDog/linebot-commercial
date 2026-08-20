package dev.myudog.assetsmanagerlinebot.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 管理報價類型、品項主檔與各類型固定品項資料的資料庫入口。
 */
@Repository
public class QuotationAdminRepository {

	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	// 方法：建立報價管理資料庫入口。
	public QuotationAdminRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	// 方法：列出五種報價類型及其範本是否已就緒。
	public List<SchemeSummary> findSchemes() {
		// 外部 API：透過 Spring JDBC 讀取報價類型與啟用中的範本狀態。
		return jdbc.sql("""
			SELECT s.code, s.name, s.calculation_visibility, s.is_active,
			       CASE
			           WHEN EXISTS (
			               SELECT 1
			               FROM quotation_template t
			               WHERE t.scheme_id = s.id
			                 AND t.is_active = 1
			           ) THEN 1
			           ELSE 0
			       END AS template_ready
			FROM quotation_scheme s
			ORDER BY s.id
			""")
			.query(QuotationAdminRepository::mapScheme)
			.list();
	}

	// 方法：依代碼取得報價類型流水號。
	public Optional<Long> findSchemeId(String schemeCode) {
		// 外部 API：透過 Spring JDBC 查詢報價類型主鍵。
		return jdbc.sql("SELECT id FROM quotation_scheme WHERE code = ?")
			.param(schemeCode)
			.query(Long.class)
			.optional();
	}

	// 方法：列出全部品項主檔。
	public List<Item> findItems() {
		// 外部 API：透過 Spring JDBC 讀取可供各報價類型共用的品項。
		return jdbc.sql("SELECT * FROM quotation_item ORDER BY name, code")
			.query(this::mapItem)
			.list();
	}

	// 方法：依品項代碼取得單一品項，供主檔匯入判斷新增或更新。
	public Optional<Item> findItemByCode(String code) {
		// 外部 API：透過 Spring JDBC 以唯一代碼讀取品項。
		return jdbc.sql("SELECT * FROM quotation_item WHERE code = ?")
			.param(code)
			.query(this::mapItem)
			.optional();
	}

	// 方法：主檔匯入前先整批停用，讓未出現在檔案中的資料自動失效但保留歷史關聯。
	public void deactivateAllMasterData() {
		// 外部 API：透過 Spring JDBC 停用全部格式品項與品項主檔，稍後由匯入內容重新啟用。
		jdbc.sql("UPDATE quotation_scheme_item SET is_active = 0, updated_at = CURRENT_TIMESTAMP WHERE is_active = 1")
			.update();
		jdbc.sql("UPDATE quotation_item SET is_active = 0, updated_at = CURRENT_TIMESTAMP WHERE is_active = 1")
			.update();
	}

	// 方法：依流水號取得單一品項。
	public Optional<Item> findItem(long itemId) {
		// 外部 API：透過 Spring JDBC 讀取指定品項。
		return jdbc.sql("SELECT * FROM quotation_item WHERE id = ?")
			.param(itemId)
			.query(this::mapItem)
			.optional();
	}

	// 方法：建立品項主檔並回傳完整資料。
	public Item createItem(String code, String name, List<String> aliases, boolean isActive) {
		KeyHolder keys = new GeneratedKeyHolder();

		// 外部 API：透過 Spring JDBC 寫入品項及 AI 可辨識的別名。
		jdbc.sql("""
			INSERT INTO quotation_item (code, name, aliases_json, is_active)
			VALUES (?, ?, ?, ?)
			""")
			.params(code, name, objectMapper.writeValueAsString(aliases), isActive ? 1 : 0)
			.update(keys);
		Number key = keys.getKey();
		if (key == null) throw new IllegalStateException("建立品項後未取得主鍵");

		return findItem(key.longValue()).orElseThrow();
	}

	// 方法：更新品項主檔並回傳更新結果。
	public Item updateItem(long itemId, String code, String name, List<String> aliases, boolean isActive) {
		// 外部 API：透過 Spring JDBC 更新品項固定資料。
		jdbc.sql("""
			UPDATE quotation_item
			SET code = ?, name = ?, aliases_json = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP
			WHERE id = ?
			""")
			.params(code, name, objectMapper.writeValueAsString(aliases), isActive ? 1 : 0, itemId)
			.update();

		return findItem(itemId).orElseThrow();
	}

	// 方法：列出指定報價類型的固定品項組合。
	public List<SchemeItem> findSchemeItems(String schemeCode) {
		// 外部 API：透過 Spring JDBC 讀取報價類型對應的規格、單位、單價與備註。
		return jdbc.sql("""
			SELECT si.id, si.scheme_id, si.item_id, i.code AS item_code, i.name AS item_name,
			       si.specification, si.unit, si.unit_price, si.remark, si.display_order,
			       si.calculation_mode, si.is_customer_visible, si.is_active
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			WHERE s.code = ?
			ORDER BY si.display_order, si.id
			""")
			.param(schemeCode)
			.query(QuotationAdminRepository::mapSchemeItem)
			.list();
	}

	// 方法：列出 AI 可使用的啟用中類型品項，排除停用類型、主檔與關聯。
	public List<SchemeItem> findActiveSchemeItems(String schemeCode) {
		// 外部 API：透過 Spring JDBC 只讀取可進入報價計算的固定品項資料。
		return jdbc.sql("""
			SELECT si.id, si.scheme_id, si.item_id, i.code AS item_code, i.name AS item_name,
			       si.specification, si.unit, si.unit_price, si.remark, si.display_order,
			       si.calculation_mode, si.is_customer_visible, si.is_active
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			WHERE s.code = ?
			  AND s.is_active = 1
			  AND i.is_active = 1
			  AND si.is_active = 1
			ORDER BY si.display_order, si.id
			""")
			.param(schemeCode)
			.query(QuotationAdminRepository::mapSchemeItem)
			.list();
	}

	// 方法：列出 AI 品項判讀可使用的啟用中目錄，刻意排除單價與備註。
	public List<AiCatalogEntry> findActiveAiCatalog() {
		// 外部 API：透過 Spring JDBC 讀取五種方案的允許品項及辨識線索。
		return jdbc.sql("""
			SELECT s.code AS scheme_code,
			       s.name AS scheme_name,
			       s.calculation_visibility,
			       i.code AS item_code,
			       i.name AS item_name,
			       i.aliases_json,
			       si.specification,
			       si.unit,
			       si.calculation_mode
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			WHERE s.is_active = 1
			  AND i.is_active = 1
			  AND si.is_active = 1
			ORDER BY s.id, si.display_order, si.id
			""")
			.query(this::mapAiCatalogEntry)
			.list();
	}

	// 方法：新增或更新報價類型中的固定品項資料。
	public SchemeItem upsertSchemeItem(
		long schemeId,
		long itemId,
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		int displayOrder,
		String calculationMode,
		boolean isCustomerVisible,
		boolean isActive
	) {
		// 外部 API：透過 Spring JDBC 以報價類型與品項的唯一關聯執行安全更新。
		jdbc.sql("""
			INSERT INTO quotation_scheme_item (
			    scheme_id, item_id, specification, unit, unit_price, remark,
			    display_order, calculation_mode, is_customer_visible, is_active
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (scheme_id, item_id) DO UPDATE SET
			    specification = excluded.specification,
			    unit = excluded.unit,
			    unit_price = excluded.unit_price,
			    remark = excluded.remark,
			    display_order = excluded.display_order,
			    calculation_mode = excluded.calculation_mode,
			    is_customer_visible = excluded.is_customer_visible,
			    is_active = excluded.is_active,
			    updated_at = CURRENT_TIMESTAMP
			""")
			.params(
				schemeId,
				itemId,
				specification,
				unit,
				unitPrice,
				remark,
				displayOrder,
				calculationMode,
				isCustomerVisible ? 1 : 0,
				isActive ? 1 : 0
			)
			.update();

		// 外部 API：透過 Spring JDBC 讀回剛寫入的類型品項資料。
		return jdbc.sql("""
			SELECT si.id, si.scheme_id, si.item_id, i.code AS item_code, i.name AS item_name,
			       si.specification, si.unit, si.unit_price, si.remark, si.display_order,
			       si.calculation_mode, si.is_customer_visible, si.is_active
			FROM quotation_scheme_item si
			JOIN quotation_item i ON i.id = si.item_id
			WHERE si.scheme_id = ? AND si.item_id = ?
			""")
			.params(schemeId, itemId)
			.query(QuotationAdminRepository::mapSchemeItem)
			.single();
	}

	// 方法：列出可匯出成單一主檔表格的方案品項與尚未分配品項。
	public List<MasterDataExportRow> findMasterDataExportRows() {
		// 外部 API：透過 Spring JDBC 將已分配與未分配品項合併成穩定排序的匯出資料。
		return jdbc.sql("""
			SELECT 0 AS sort_group,
			       s.code AS scheme_code,
			       s.name AS scheme_name,
			       i.code AS item_code,
			       i.name AS item_name,
			       i.aliases_json,
			       si.specification,
			       si.unit,
			       si.unit_price,
			       si.remark,
			       si.display_order,
			       si.calculation_mode,
			       si.is_customer_visible,
			       si.is_active AS mapping_active,
			       i.is_active AS item_active
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			UNION ALL
			SELECT 1 AS sort_group,
			       NULL AS scheme_code,
			       NULL AS scheme_name,
			       i.code AS item_code,
			       i.name AS item_name,
			       i.aliases_json,
			       NULL AS specification,
			       NULL AS unit,
			       NULL AS unit_price,
			       NULL AS remark,
			       NULL AS display_order,
			       NULL AS calculation_mode,
			       NULL AS is_customer_visible,
			       NULL AS mapping_active,
			       i.is_active AS item_active
			FROM quotation_item i
			WHERE NOT EXISTS (
			    SELECT 1
			    FROM quotation_scheme_item si
			    WHERE si.item_id = i.id
			)
			ORDER BY sort_group, scheme_code, display_order, item_code
			""")
			.query(this::mapMasterDataExportRow)
			.list();
	}

	// 方法：保存管理員修改摘要以供後續稽核。
	public void writeAudit(String action, String entityType, String entityId, String summaryJson) {
		// 外部 API：透過 Spring JDBC 寫入不含機密資訊的管理操作紀錄。
		jdbc.sql("""
			INSERT INTO admin_audit_log (action, entity_type, entity_id, summary_json)
			VALUES (?, ?, ?, ?)
			""")
			.params(action, entityType, entityId, summaryJson)
			.update();
	}

	// 方法：將報價類型查詢列轉成 API 資料。
	private static SchemeSummary mapScheme(ResultSet result, int rowNumber) throws SQLException {
		return new SchemeSummary(
			result.getString("code"),
			result.getString("name"),
			result.getString("calculation_visibility"),
			result.getBoolean("is_active"),
			result.getBoolean("template_ready")
		);
	}

	// 方法：將品項查詢列轉成 API 資料。
	private Item mapItem(ResultSet result, int rowNumber) throws SQLException {
		List<String> aliases = objectMapper.readValue(result.getString("aliases_json"), STRING_LIST_TYPE);

		return new Item(
			result.getLong("id"),
			result.getString("code"),
			result.getString("name"),
			aliases,
			result.getBoolean("is_active"),
			result.getString("updated_at")
		);
	}

	// 方法：將類型品項查詢列轉成 API 資料。
	private static SchemeItem mapSchemeItem(ResultSet result, int rowNumber) throws SQLException {
		String calculationMode = result.getString("calculation_mode");

		return new SchemeItem(
			result.getLong("id"),
			result.getLong("scheme_id"),
			result.getLong("item_id"),
			result.getString("item_code"),
			result.getString("item_name"),
			result.getString("specification"),
			result.getString("unit"),
			result.getBigDecimal("unit_price"),
			result.getString("remark"),
			result.getInt("display_order"),
			calculationMode,
			result.getBoolean("is_customer_visible"),
			result.getBoolean("is_active"),
			true,
			"DIRECT".equals(calculationMode)
		);
	}

	// 方法：將主檔匯出查詢列轉成 CSV 服務使用的資料。
	private MasterDataExportRow mapMasterDataExportRow(ResultSet result, int rowNumber) throws SQLException {
		return new MasterDataExportRow(
			result.getString("scheme_code"),
			result.getString("scheme_name"),
			result.getString("item_code"),
			result.getString("item_name"),
			objectMapper.readValue(result.getString("aliases_json"), STRING_LIST_TYPE),
			result.getString("specification"),
			result.getString("unit"),
			result.getBigDecimal("unit_price"),
			result.getString("remark"),
			nullableInteger(result, "display_order"),
			result.getString("calculation_mode"),
			nullableBoolean(result, "is_customer_visible"),
			nullableBoolean(result, "mapping_active"),
			result.getBoolean("item_active")
		);
	}

	// 方法：將 AI 目錄查詢列轉成不含價格的辨識資料。
	private AiCatalogEntry mapAiCatalogEntry(ResultSet result, int rowNumber) throws SQLException {
		return new AiCatalogEntry(
			result.getString("scheme_code"),
			result.getString("scheme_name"),
			result.getString("calculation_visibility"),
			result.getString("item_code"),
			result.getString("item_name"),
			objectMapper.readValue(result.getString("aliases_json"), STRING_LIST_TYPE),
			result.getString("specification"),
			result.getString("unit"),
			result.getString("calculation_mode")
		);
	}

	// 方法：保留 SQLite 可空整數布林值的 null 狀態。
	private static Boolean nullableBoolean(ResultSet result, String column) throws SQLException {
		Object value = result.getObject(column);
		return value == null ? null : result.getBoolean(column);
	}

	// 方法：保留 SQLite 可空整數欄位的 null 狀態。
	private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
		Number value = (Number) result.getObject(column);
		return value == null ? null : value.intValue();
	}

	public record SchemeSummary(
		String code,
		String name,
		String calculationVisibility,
		boolean isActive,
		boolean templateReady
	) {}

	public record Item(
		long id,
		String code,
		String name,
		List<String> aliases,
		boolean isActive,
		String updatedAt
	) {}

	public record SchemeItem(
		long id,
		long schemeId,
		long itemId,
		String itemCode,
		String itemName,
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		int displayOrder,
		String calculationMode,
		boolean isCustomerVisible,
		boolean isActive,
		boolean quantityEditable,
		boolean lineAmountCalculated
	) {}

	public record MasterDataExportRow(
		String schemeCode,
		String schemeName,
		String itemCode,
		String itemName,
		List<String> aliases,
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		Integer displayOrder,
		String calculationMode,
		Boolean isCustomerVisible,
		Boolean isMappingActive,
		boolean isItemActive
	) {}

	public record AiCatalogEntry(
		String schemeCode,
		String schemeName,
		String calculationVisibility,
		String itemCode,
		String itemName,
		List<String> aliases,
		String specification,
		String unit,
		String calculationMode
	) {}
}
