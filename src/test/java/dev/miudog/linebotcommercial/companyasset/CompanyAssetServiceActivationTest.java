package dev.miudog.linebotcommercial.companyasset;

import dev.miudog.linebotcommercial.companyasset.CompanyAssetRepository.AssetSet;
import dev.miudog.linebotcommercial.companyasset.CompanyAssetRepository.StoredManifestObject;
import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.service.quotation.QuotationMasterDataCsvService;
import dev.miudog.linebotcommercial.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyAssetServiceActivationTest {

	private static final byte[] MASTER_DATA = "master-data".getBytes(StandardCharsets.UTF_8);

	// 測試：啟用資產版本時會先交易式套用該版本主檔，再切換 active 指標。
	@Test
	void appliesVersionedMasterDataBeforeActivation() {
		Fixture fixture = fixture();

		fixture.service().activate("2026.09.0", "asset-owner");

		InOrder ordered = inOrder(fixture.masterData(), fixture.repository());
		ordered.verify(fixture.masterData()).importCsv(MASTER_DATA);
		ordered.verify(fixture.repository()).registerTemplates(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
		ordered.verify(fixture.repository()).activate(42L, "asset-owner", false);
	}

	// 測試：回復舊版本時同樣套用該版本主檔，確保文件與價格資料一致。
	@Test
	void appliesVersionedMasterDataBeforeRollback() {
		Fixture fixture = fixture();

		fixture.service().rollback("2026.09.0", "asset-owner");

		InOrder ordered = inOrder(fixture.masterData(), fixture.repository());
		ordered.verify(fixture.masterData()).importCsv(MASTER_DATA);
		ordered.verify(fixture.repository()).registerTemplates(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
		ordered.verify(fixture.repository()).activate(42L, "asset-owner", true);
	}

	// 測試：主檔套用失敗時不得切換 active 指標。
	@Test
	void keepsCurrentActivationWhenMasterDataImportFails() {
		Fixture fixture = fixture();
		RuntimeException failure = new RuntimeException("invalid master data");
		when(fixture.masterData().importCsv(MASTER_DATA)).thenThrow(failure);

		assertThatThrownBy(() -> fixture.service().activate("2026.09.0", "asset-owner"))
			.isSameAs(failure);
		verify(fixture.repository(), never()).activate(42L, "asset-owner", false);
	}

	// 測試：未核准版本在讀取與匯入主檔前即被拒絕。
	@Test
	void rejectsInvalidStateBeforeApplyingMasterData() {
		Fixture fixture = fixture("STAGED");

		assertThatThrownBy(() -> fixture.service().activate("2026.09.0", "asset-owner"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("尚未核准");
		verify(fixture.masterData(), never()).importCsv(MASTER_DATA);
		verify(fixture.repository(), never()).activate(42L, "asset-owner", false);
	}

	// 方法：建立只包含啟用流程必要邊界的測試組件。
	private Fixture fixture() {
		return fixture("APPROVED");
	}

	// 方法：建立指定初始狀態的測試組件。
	private Fixture fixture(String initialStatus) {
		ObjectStorage storage = mock(ObjectStorage.class);
		CompanyAssetRepository repository = mock(CompanyAssetRepository.class);
		QuotationMasterDataCsvService masterData = mock(QuotationMasterDataCsvService.class);
		AssetSet approved = assetSet(initialStatus);
		AssetSet active = assetSet("ACTIVE");
		StoredManifestObject itemMaster = new StoredManifestObject(
			CompanyAssetPurpose.ITEM_MASTER,
			"company-assets/sets/2026.09.0/item_master.bin",
			"version-1",
			"text/csv",
			MASTER_DATA.length,
			"hash"
		);
		when(repository.required("2026.09.0")).thenReturn(approved, active);
		byte[] definitions = """
			{"schemaVersion":"1.1","templates":[{"schemeCode":"CNS"},{"schemeCode":"GENERAL"},{"schemeCode":"MARINE"},{"schemeCode":"BLANK"},{"schemeCode":"SALES"}]}
			""".getBytes(StandardCharsets.UTF_8);
		StoredManifestObject templates = new StoredManifestObject(CompanyAssetPurpose.TEMPLATE_DEFINITIONS,
			"company-assets/sets/2026.09.0/templates.bin", "version-1", "application/json", definitions.length, "hash");
		when(repository.objects(42L)).thenReturn(List.of(itemMaster, templates));
		when(storage.get(itemMaster.objectKey())).thenReturn(MASTER_DATA);
		when(storage.get(templates.objectKey())).thenReturn(definitions);

		CompanyAssetService service = new CompanyAssetService(
			new CompanyProperties("company-test"),
			storage,
			repository,
			new ObjectMapper(),
			masterData
		);
		return new Fixture(service, repository, masterData);
	}

	// 方法：建立指定狀態的固定資產版本。
	private AssetSet assetSet(String status) {
		return new AssetSet(
			42L,
			"2026.09.0",
			"1",
			status,
			"manifest-hash",
			"creator",
			"approver",
			"created-at",
			"approved-at",
			"activated-at"
		);
	}

	private record Fixture(
		CompanyAssetService service,
		CompanyAssetRepository repository,
		QuotationMasterDataCsvService masterData
	) {
	}
}
