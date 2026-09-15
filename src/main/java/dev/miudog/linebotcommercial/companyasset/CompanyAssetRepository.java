package dev.miudog.linebotcommercial.companyasset;

import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 公司資產版本與 active 指標的唯一資料庫邊界。
 */
@Repository
public class CompanyAssetRepository {

	private final JdbcClient jdbc;
	private final String companyId;

	// 方法：執行此方法定義的受控處理流程。
	public CompanyAssetRepository(JdbcClient jdbc, CompanyProperties companyProperties) {
		this.jdbc = jdbc;
		this.companyId = companyProperties.id();
	}

	// 方法：執行此方法定義的受控處理流程。
	public long createStaged(
		CompanyAssetManifest manifest,
		String manifestHash,
		String actor,
		List<StoredManifestObject> objects
	) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO company_asset_set (
					company_id, asset_set_version, schema_version, status,
					manifest_hash, created_by
				)
				VALUES (?, ?, ?, 'STAGED', ?, ?)
				""")
			.params(
				companyId,
				manifest.assetSetVersion(),
				manifest.schemaVersion(),
				manifestHash,
				actor
			)
			.update(keys);
		Number key = keys.getKey();
		if (key == null) throw new IllegalStateException("無法建立公司資產版本");

		long setId = key.longValue();
		for (StoredManifestObject object : objects) {
			jdbc.sql("""
					INSERT INTO company_asset_object (
						asset_set_id, purpose, object_key, object_version,
						content_type, file_size, content_hash
					)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""")
				.params(
					setId,
					object.purpose().name(),
					object.objectKey(),
					object.objectVersion(),
					object.contentType(),
					object.size(),
					object.sha256()
				)
				.update();
		}
		return setId;
	}

	// 方法：執行此方法定義的受控處理流程。
	public AssetSet required(String version) {
		return find(version)
			.orElseThrow(() -> new IllegalArgumentException("找不到公司資產版本"));
	}

	// 方法：執行此方法定義的受控處理流程。
	public Optional<AssetSet> find(String version) {
		return jdbc.sql("""
				SELECT id, asset_set_version, schema_version, status, manifest_hash,
					created_by, approved_by, created_at, approved_at, activated_at
				FROM company_asset_set
				WHERE company_id = ? AND asset_set_version = ?
				""")
			.params(companyId, version)
			.query((result, rowNumber) -> new AssetSet(
				result.getLong("id"),
				result.getString("asset_set_version"),
				result.getString("schema_version"),
				result.getString("status"),
				result.getString("manifest_hash"),
				result.getString("created_by"),
				result.getString("approved_by"),
				result.getString("created_at"),
				result.getString("approved_at"),
				result.getString("activated_at")
			))
			.optional();
	}

	// 方法：執行此方法定義的受控處理流程。
	public List<AssetSet> list() {
		return jdbc.sql("""
				SELECT id, asset_set_version, schema_version, status, manifest_hash,
					created_by, approved_by, created_at, approved_at, activated_at
				FROM company_asset_set
				WHERE company_id = ?
				ORDER BY id DESC
				""")
			.param(companyId)
			.query((result, rowNumber) -> new AssetSet(
				result.getLong("id"),
				result.getString("asset_set_version"),
				result.getString("schema_version"),
				result.getString("status"),
				result.getString("manifest_hash"),
				result.getString("created_by"),
				result.getString("approved_by"),
				result.getString("created_at"),
				result.getString("approved_at"),
				result.getString("activated_at")
			))
			.list();
	}

	// 方法：執行此方法定義的受控處理流程。
	public List<StoredManifestObject> objects(long setId) {
		return jdbc.sql("""
				SELECT purpose, object_key, object_version, content_type, file_size, content_hash
				FROM company_asset_object
				WHERE asset_set_id = ?
				ORDER BY purpose
				""")
			.param(setId)
			.query((result, rowNumber) -> new StoredManifestObject(
				CompanyAssetPurpose.valueOf(result.getString("purpose")),
				result.getString("object_key"),
				result.getString("object_version"),
				result.getString("content_type"),
				result.getLong("file_size"),
				result.getString("content_hash")
			))
			.list();
	}

	// 方法：執行此方法定義的受控處理流程。
	public void promoteObject(
		long setId,
		CompanyAssetPurpose purpose,
		String key,
		String versionId
	) {
		jdbc.sql("""
				UPDATE company_asset_object
				SET object_key = ?, object_version = ?
				WHERE asset_set_id = ? AND purpose = ?
				""")
			.params(key, versionId, setId, purpose.name())
			.update();
	}

	// 方法：執行此方法定義的受控處理流程。
	public void markValidated(long setId) {
		transition(setId, "STAGED", "VALIDATED", null);
	}

	// 方法：執行此方法定義的受控處理流程。
	public void approve(long setId, String actor) {
		int updated = jdbc.sql("""
				UPDATE company_asset_set
				SET status = 'APPROVED', approved_by = ?, approved_at = ?
				WHERE id = ? AND company_id = ? AND status = 'VALIDATED'
				""")
			.params(actor, Instant.now().toString(), setId, companyId)
			.update();
		if (updated != 1) throw new IllegalStateException("只有驗證完成的版本可以核准");
	}

	// 方法：執行此方法定義的受控處理流程。
	@Transactional
	public void activate(long setId, String actor, boolean rollback) {
		AssetSet target = requiredById(setId);
		if (
			!("APPROVED".equals(target.status())
				|| rollback && "RETIRED".equals(target.status()))
		) {
			throw new IllegalStateException("目標版本尚未核准或不可回復");
		}

		Long previousId = jdbc.sql("""
				SELECT id FROM company_asset_set
				WHERE company_id = ? AND status = 'ACTIVE'
				""")
			.param(companyId)
			.query(Long.class)
			.optional()
			.orElse(null);
		if (previousId != null && previousId == setId) return;

		jdbc.sql("""
				UPDATE company_asset_set
				SET status = 'RETIRED'
				WHERE company_id = ? AND status = 'ACTIVE'
				""")
			.param(companyId)
			.update();
		int updated = jdbc.sql("""
				UPDATE company_asset_set
				SET status = 'ACTIVE', activated_at = ?
				WHERE id = ? AND company_id = ?
				""")
			.params(Instant.now().toString(), setId, companyId)
			.update();
		if (updated != 1) throw new IllegalStateException("公司資產版本啟用失敗");

		jdbc.sql("""
				INSERT INTO company_asset_activation (
					company_id, previous_set_id, activated_set_id, action, actor
				)
				VALUES (?, ?, ?, ?, ?)
				""")
			.params(companyId, previousId, setId, rollback ? "ROLLBACK" : "ACTIVATE", actor)
			.update();
	}

	// 方法：執行此方法定義的受控處理流程。
	public StoredManifestObject activeObject(CompanyAssetPurpose purpose) {
		return jdbc.sql("""
				SELECT o.purpose, o.object_key, o.object_version,
					o.content_type, o.file_size, o.content_hash
				FROM company_asset_object o
				JOIN company_asset_set s ON s.id = o.asset_set_id
				WHERE s.company_id = ? AND s.status = 'ACTIVE' AND o.purpose = ?
				""")
			.params(companyId, purpose.name())
			.query((result, rowNumber) -> new StoredManifestObject(
				purpose,
				result.getString("object_key"),
				result.getString("object_version"),
				result.getString("content_type"),
				result.getLong("file_size"),
				result.getString("content_hash")
			))
			.optional()
			.orElseThrow(() -> new IllegalStateException("尚未啟用必要公司資產：" + purpose));
	}

	// 方法：執行此方法定義的受控處理流程。
	public long activeSetId() {
		return jdbc.sql("""
				SELECT id FROM company_asset_set
				WHERE company_id = ? AND status = 'ACTIVE'
				""")
			.param(companyId)
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("尚未啟用公司資產版本"));
	}

	// 方法：執行此方法定義的受控處理流程。
	public StoredManifestObject quotationObject(
		long quotationId,
		CompanyAssetPurpose purpose
	) {
		return jdbc.sql("""
				SELECT o.purpose, o.object_key, o.object_version,
					o.content_type, o.file_size, o.content_hash
				FROM quotation q
				JOIN company_asset_set s ON s.id = q.asset_set_id
				JOIN company_asset_object o ON o.asset_set_id = s.id
				WHERE q.id = ? AND s.company_id = ? AND o.purpose = ?
				""")
			.params(quotationId, companyId, purpose.name())
			.query((result, rowNumber) -> new StoredManifestObject(
				purpose,
				result.getString("object_key"),
				result.getString("object_version"),
				result.getString("content_type"),
				result.getLong("file_size"),
				result.getString("content_hash")
			))
			.optional()
			.orElseThrow(() -> new IllegalStateException("報價未鎖定必要公司資產：" + purpose));
	}

	// 方法：執行此方法定義的受控處理流程。
	private AssetSet requiredById(long setId) {
		return jdbc.sql("""
				SELECT id, asset_set_version, schema_version, status, manifest_hash,
					created_by, approved_by, created_at, approved_at, activated_at
				FROM company_asset_set
				WHERE company_id = ? AND id = ?
				""")
			.params(companyId, setId)
			.query((result, rowNumber) -> new AssetSet(
				result.getLong("id"),
				result.getString("asset_set_version"),
				result.getString("schema_version"),
				result.getString("status"),
				result.getString("manifest_hash"),
				result.getString("created_by"),
				result.getString("approved_by"),
				result.getString("created_at"),
				result.getString("approved_at"),
				result.getString("activated_at")
			))
			.optional()
			.orElseThrow(() -> new IllegalArgumentException("找不到公司資產版本"));
	}

	// 方法：執行此方法定義的受控處理流程。
	private void transition(long setId, String current, String next, String actor) {
		int updated = jdbc.sql("""
				UPDATE company_asset_set
				SET status = ?
				WHERE id = ? AND company_id = ? AND status = ?
				""")
			.params(next, setId, companyId, current)
			.update();
		if (updated != 1) throw new IllegalStateException("公司資產版本狀態不允許此操作");
	}

	public record AssetSet(
		long id,
		String version,
		String schemaVersion,
		String status,
		String manifestHash,
		String createdBy,
		String approvedBy,
		String createdAt,
		String approvedAt,
		String activatedAt
	) {
	}

	public record StoredManifestObject(
		CompanyAssetPurpose purpose,
		String objectKey,
		String objectVersion,
		String contentType,
		long size,
		String sha256
	) {
	}
}
