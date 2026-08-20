package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.QuotationAdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-quotation-csv-test",
		"app.quotation.root-path=${java.io.tmpdir}/assets-manager-quotation-csv-workbook-test",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationMasterDataCsvImportTest {

	private static final String HEADER = "報價格式代碼,報價格式,品項代碼,品項,AI別名,規格/說明,單位,單價,備註,顯示順序,計價模式,顯示於客戶,格式品項啟用,品項主檔啟用";

	@Autowired
	QuotationMasterDataCsvService csvService;

	@Autowired
	QuotationAdminRepository repository;

	// 測試：Excel 編輯後上傳的 CSV 會整批覆蓋主檔，並保留別名供 AI 對應近似名稱。
	@Test
	void importsEditedMasterDataAndKeepsAliasesForAiMatching() {
		csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,外牆鷹架、外架,(CNS),m2,220,(實做實算),1,DIRECT,是,是,是",
			"CNS,CNS,DUST_NET,防塵網,圍網,9針,m2,40,,2,DIRECT,是,是,是"
		));

		List<QuotationAdminRepository.SchemeItem> items = repository.findActiveSchemeItems("CNS");

		assertThat(items).extracting(QuotationAdminRepository.SchemeItem::itemCode)
			.contains("EXTERNAL_SCAFFOLD", "DUST_NET");
		assertThat(repository.findItemByCode("EXTERNAL_SCAFFOLD")).hasValueSatisfying(item ->
			assertThat(item.aliases()).containsExactly("外牆鷹架", "外架")
		);
		assertThat(repository.findActiveAiCatalog())
			.filteredOn(entry -> "EXTERNAL_SCAFFOLD".equals(entry.itemCode()))
			.allSatisfy(entry -> assertThat(entry.aliases()).contains("外牆鷹架"));
	}

	// 測試：匯出後再匯入不會改變主檔內容，維護流程可安全往返。
	@Test
	void survivesAnExportImportExportRoundTrip() {
		csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,外牆鷹架,(CNS),m2,220,(實做實算),1,DIRECT,是,是,是"
		));
		String firstExport = new String(csvService.export(), StandardCharsets.UTF_8);

		csvService.importCsv(firstExport.getBytes(StandardCharsets.UTF_8));

		assertThat(new String(csvService.export(), StandardCharsets.UTF_8)).isEqualTo(firstExport);
	}

	// 測試：檔案中未出現的品項只會停用，不刪除，避免影響已確認報價的歷史關聯。
	@Test
	void deactivatesRowsMissingFromTheUploadedFileInsteadOfDeletingThem() {
		csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,,(CNS),m2,220,,1,DIRECT,是,是,是",
			"CNS,CNS,DUST_NET,防塵網,,9針,m2,40,,2,DIRECT,是,是,是"
		));

		csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,,(CNS),m2,240,,1,DIRECT,是,是,是"
		));

		assertThat(repository.findActiveSchemeItems("CNS"))
			.extracting(QuotationAdminRepository.SchemeItem::itemCode)
			.containsExactly("EXTERNAL_SCAFFOLD");
		assertThat(repository.findItemByCode("DUST_NET")).hasValueSatisfying(item ->
			assertThat(item.isActive()).isFalse()
		);
	}

	// 測試：不使用固定品項的三種格式不接受主檔資料列。
	@Test
	void rejectsFixedItemsForSchemesThatDoNotUseACatalog() {
		assertThatThrownBy(() -> csvService.importCsv(csv(
			"MARINE,船用,EXTERNAL_SCAFFOLD,外部鷹架,,(CNS),m2,220,,1,DIRECT,是,是,是"
		)))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("不使用固定品項");
	}

	// 測試：欄位錯誤時整份檔案都不套用，並指出實際列號。
	@Test
	void reportsTheFailingLineAndAppliesNothingWhenAValueIsInvalid() {
		csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,,(CNS),m2,220,,1,DIRECT,是,是,是"
		));

		assertThatThrownBy(() -> csvService.importCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,,(CNS),m2,260,,1,DIRECT,是,是,是",
			"CNS,CNS,DUST_NET,防塵網,,9針,m2,-1,,2,DIRECT,是,是,是"
		)))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("第 3 列")
			.hasMessageContaining("單價");
	}

	// 測試：標題與匯出格式不同時直接拒絕，避免欄位錯位造成價格錯誤。
	@Test
	void rejectsFilesWhoseHeaderDoesNotMatchTheExportFormat() {
		assertThatThrownBy(() -> csvService.importCsv(
			"品項代碼,品項\nEXTERNAL_SCAFFOLD,外部鷹架\n".getBytes(StandardCharsets.UTF_8)
		))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("標題必須與匯出格式相同");
	}

	// 方法：以匯出格式組出含 BOM 的測試檔案內容。
	private byte[] csv(String... rows) {
		StringBuilder csv = new StringBuilder("﻿").append(HEADER).append("\r\n");
		for (String row : rows) csv.append(row).append("\r\n");

		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}
}
