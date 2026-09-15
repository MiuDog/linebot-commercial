package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QuotationMasterDataCsvValidationTest {

	private static final String HEADER = "報價格式代碼,報價格式,品項代碼,品項,AI別名,規格/說明,單位,單價,備註,顯示順序,計價模式,顯示於客戶,格式品項啟用,品項主檔啟用";

	// 測試：資產 stage 的 CSV 驗證只解析內容，不得提前修改正式主檔。
	@Test
	void validatesWithoutMutatingTheRepository() {
		QuotationAdminRepository repository = mock(QuotationAdminRepository.class);
		QuotationMasterDataCsvService service = new QuotationMasterDataCsvService(repository);

		service.validateCsv(csv(
			"CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,外牆鷹架,(CNS),m2,220,,1,DIRECT,是,是,是"
		));

		verifyNoInteractions(repository);
	}

	// 測試：驗證入口與正式匯入共用相同格式規則。
	@Test
	void rejectsInvalidContentWithoutMutatingTheRepository() {
		QuotationAdminRepository repository = mock(QuotationAdminRepository.class);
		QuotationMasterDataCsvService service = new QuotationMasterDataCsvService(repository);

		assertThatThrownBy(() -> service.validateCsv(
			"品項代碼,品項\nEXTERNAL_SCAFFOLD,外部鷹架\n".getBytes(StandardCharsets.UTF_8)
		))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("標題必須與匯出格式相同");
		verifyNoInteractions(repository);
	}

	// 方法：以正式匯出格式組出含 BOM 的測試內容。
	private byte[] csv(String... rows) {
		StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
		for (String row : rows) csv.append(row).append("\r\n");

		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}
}
