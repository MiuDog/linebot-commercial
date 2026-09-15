package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationInputCsvServiceTest {

	@Test
	void parsesQuotedUtf8FieldsAndKeepsPricingAsExplicitUserInput() {
		var service = service();
		var result = service.parse(QuotationInputCsvService.HEADER + "\r\n"
			+ "SALES,測試公司,測試工程,王先生,,, ,支架,\"尺寸,大型\",組,120.50,2,\"第一行\n第二行\"\r\n");
		assertThat(result.request().customItems()).singleElement().satisfies(item -> {
			assertThat(item.quantity().value()).isEqualByComparingTo("2");
			assertThat(item.unitPrice().value()).isEqualByComparingTo("120.50");
			assertThat(item.specification().value()).isEqualTo("尺寸,大型");
		});
		assertThat(result.request().headerPatch().companyName().value()).isEqualTo("測試公司");
	}

	@Test
	void rejectsConflictingFormatsMalformedRowsAndNegativeQuantities() {
		var service = service();
		String row = "SALES,測試公司,工程,承辦,,,,支架,規格,組,10,2,\n";
		assertThatThrownBy(() -> service.parse(QuotationInputCsvService.HEADER + "\n" + row + row.replace("SALES", "BLANK")))
			.hasMessageContaining("第 3 列");
		assertThatThrownBy(() -> service.parse(QuotationInputCsvService.HEADER + "\nSALES,\"未結束"))
			.hasMessageContaining("引號");
		assertThatThrownBy(() -> service.parse(QuotationInputCsvService.HEADER + "\n" + row.replace(",2,", ",-2,")))
			.hasMessageContaining("quantity");
	}

	// 使用真正業務驗證器確認 CSV 也遵循報價契約，只有外部資料庫使用替身。
	@Test
	void rejectsExponentAndExcessivePrecisionWithoutExpandingDecimals() {
		for (String number : List.of("1e-2147483647", "1.1234567", "1000000001")) {
			String row = "SALES,測試公司,工程,承辦,,,,支架,規格,組,10," + number + ",\n";
			assertThatThrownBy(() -> service().parse(QuotationInputCsvService.HEADER + "\n" + row))
				.hasMessageContaining("quantity");
		}
	}

	// 方法：建立使用真實業務驗證器的 CSV 解析器。
	private QuotationInputCsvService service() {
		var repository = mock(QuotationAdminRepository.class);
		when(repository.findSchemes()).thenReturn(List.of(
			new QuotationAdminRepository.SchemeSummary("SALES", "銷售", "DETAIL", true, true),
			new QuotationAdminRepository.SchemeSummary("BLANK", "空白", "DETAIL", true, true)
		));
		return new QuotationInputCsvService(new QuotationRequestValidationService(repository), new ObjectMapper());
	}
}
