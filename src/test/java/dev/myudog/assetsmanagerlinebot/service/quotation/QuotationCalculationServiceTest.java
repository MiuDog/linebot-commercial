package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.QuotationAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationCalculationServiceTest {

	QuotationAdminRepository repository;
	QuotationCalculationService service;

	@BeforeEach
	void setUp() {
		repository = mock(QuotationAdminRepository.class);
		service = new QuotationCalculationService(repository);
	}

	@Test
	void keepsAllActiveFixedItemsAndLeavesUnmentionedAmountsNull() {
		when(repository.findActiveSchemeItems("CNS")).thenReturn(
			List.of(
				master("EXTERNAL_SCAFFOLD", "外部鷹架", "(CNS)", "m2", "220", 1),
				master("DUST_NET", "防塵網", "9針", "m2", "40", 2),
				master("CANVAS", "帆布", "", "m2", "70", 3)
			)
		);
		QuotationCalculationRequest request = request(
			"CNS",
			List.of(intent("DUST_NET", "2.5", "999999")),
			List.of(),
			Set.of()
		);

		QuotationCalculationResult result = service.calculate(request);

		assertThat(result.internalLines()).extracting(QuotationCalculationResult.QuotationLine::itemCode)
			.containsExactly("EXTERNAL_SCAFFOLD", "DUST_NET", "CANVAS");
		assertThat(result.internalLines().get(0).quantity()).isNull();
		assertThat(result.internalLines().get(0).lineAmount()).isNull();
		assertThat(result.internalLines().get(1).quantity()).isEqualByComparingTo("2.5");
		assertThat(result.internalLines().get(1).lineAmount()).isEqualByComparingTo("100.00");
		assertThat(result.subtotal()).isEqualByComparingTo("100.00");
		assertThat(result.tax()).isEqualByComparingTo("5.00");
		assertThat(result.total()).isEqualByComparingTo("105.00");
		assertThat(result.internalLines().get(1).lineAmount()).isNotEqualByComparingTo("999999");
	}

	@Test
	void removesFixedItemsOnlyWhenTheirCodesAreExplicitlyListed() {
		when(repository.findActiveSchemeItems("GENERAL")).thenReturn(
			List.of(
				master("EXTERNAL_SCAFFOLD", "外部鷹架", "(一般料)", "m2", "180", 1),
				master("DUST_NET", "防塵網", "9針", "m2", "45", 2)
			)
		);

		QuotationCalculationResult result = service.calculate(
			request("GENERAL", List.of(), List.of(), Set.of("DUST_NET"))
		);

		assertThat(result.internalLines()).extracting(QuotationCalculationResult.QuotationLine::itemCode)
			.containsExactly("EXTERNAL_SCAFFOLD");
	}

	@Test
	void rejectsTheThirdTemporaryItemForFixedSchemes() {
		when(repository.findActiveSchemeItems("CNS")).thenReturn(List.of());
		QuotationCalculationRequest request = request(
			"CNS",
			List.of(),
			List.of(custom("臨時一", "10"), custom("臨時二", "20"), custom("臨時三", "30")),
			Set.of()
		);

		assertThatThrownBy(() -> service.calculate(request))
			.isInstanceOf(QuotationCalculationException.class)
			.hasMessageContaining("最多 2 筆臨時品項");
	}

	@Test
	void acceptsTwoCompleteConfirmedTemporaryItemsForFixedSchemes() {
		when(repository.findActiveSchemeItems("GENERAL")).thenReturn(List.of());
		QuotationCalculationRequest request = request(
			"GENERAL",
			List.of(),
			List.of(temporary("臨時一", "10"), temporary("臨時二", "20")),
			Set.of()
		);

		QuotationCalculationResult result = service.calculate(request);

		assertThat(result.internalLines()).hasSize(2)
			.allSatisfy(line -> assertThat(line.origin())
				.isEqualTo(QuotationCalculationResult.LineOrigin.TEMPORARY));
		assertThat(result.subtotal()).isEqualByComparingTo("30.00");
	}

	@Test
	void rejectsIncompleteOrUnconfirmedTemporaryItems() {
		when(repository.findActiveSchemeItems("CNS")).thenReturn(List.of());
		QuotationCalculationRequest missingSpecification = request(
			"CNS",
			List.of(),
			List.of(custom("缺規格", "10")),
			Set.of()
		);
		QuotationCalculationRequest unconfirmed = request(
			"CNS",
			List.of(),
			List.of(
				new QuotationCalculationRequest.CustomItem(
					"未確認",
					"標準型",
					"式",
					BigDecimal.TEN,
					BigDecimal.ONE,
					"臨時品項",
					false,
					null
				)
			),
			Set.of()
		);

		assertThatThrownBy(() -> service.calculate(missingSpecification))
			.isInstanceOf(QuotationCalculationException.class)
			.hasMessageContaining("規格不可留空");
		assertThatThrownBy(() -> service.calculate(unconfirmed))
			.isInstanceOf(QuotationCalculationException.class)
			.hasMessageContaining("尚未經使用者確認");
	}

	@Test
	void allowsMoreThanTwoDynamicItemsForBlankAndSalesSchemes() {
		for (String schemeCode : List.of("BLANK", "SALES")) {
			QuotationCalculationRequest request = request(
				schemeCode,
				List.of(),
				List.of(custom("動態一", "10"), custom("動態二", "20"), custom("動態三", "30")),
				Set.of()
			);

			QuotationCalculationResult result = service.calculate(request);

			assertThat(result.internalLines()).hasSize(3);
			assertThat(result.customerLines()).hasSize(3);
			assertThat(result.subtotal()).isEqualByComparingTo("60.00");
		}
	}

	@Test
	void hidesMarineCalculationLinesFromTheCustomerAndExposesOnlyTotals() {
		QuotationCalculationRequest request = request(
			"MARINE",
			List.of(),
			List.of(custom("內部搭設工料", "102600"), custom("內部簽證費", "35000")),
			Set.of()
		);

		QuotationCalculationResult result = service.calculate(request);

		assertThat(result.internalLines()).hasSize(2);
		assertThat(result.customerLines()).isEmpty();
		assertThat(result.customerPresentation())
			.isEqualTo(QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY);
		assertThat(result.subtotal()).isEqualByComparingTo("137600.00");
		assertThat(result.tax()).isEqualByComparingTo("6880.00");
		assertThat(result.total()).isEqualByComparingTo("144480.00");
	}

	@Test
	void appliesCommercialHalfUpRoundingAtTwoDecimalPlacesWithoutFloatingPoint() {
		QuotationCalculationRequest request = request(
			"BLANK",
			List.of(),
			List.of(
				new QuotationCalculationRequest.CustomItem(
					"精密品項",
					"",
					"式",
					new BigDecimal("20.005"),
					BigDecimal.ONE,
					"",
					true,
					new BigDecimal("0.01")
				)
			),
			Set.of()
		);

		QuotationCalculationResult result = service.calculate(request);

		assertThat(result.internalLines().getFirst().lineAmount()).isEqualTo(new BigDecimal("20.01"));
		assertThat(result.subtotal()).isEqualTo(new BigDecimal("20.01"));
		assertThat(result.tax()).isEqualTo(new BigDecimal("1.00"));
		assertThat(result.total()).isEqualTo(new BigDecimal("21.01"));
	}

	private QuotationCalculationRequest request(
		String schemeCode,
		List<QuotationCalculationRequest.StandardItemIntent> standardItems,
		List<QuotationCalculationRequest.CustomItem> customItems,
		Set<String> removedItemCodes
	) {
		return new QuotationCalculationRequest(schemeCode, standardItems, customItems, removedItemCodes);
	}

	private QuotationCalculationRequest.StandardItemIntent intent(
		String itemCode,
		String quantity,
		String untrustedLineAmount
	) {
		return new QuotationCalculationRequest.StandardItemIntent(
			itemCode,
			new BigDecimal(quantity),
			new BigDecimal(untrustedLineAmount)
		);
	}

	private QuotationCalculationRequest.CustomItem custom(String name, String unitPrice) {
		return new QuotationCalculationRequest.CustomItem(
			name,
			"",
			"式",
			new BigDecimal(unitPrice),
			BigDecimal.ONE,
			"",
			true,
			new BigDecimal("999999")
		);
	}

	private QuotationCalculationRequest.CustomItem temporary(String name, String unitPrice) {
		return new QuotationCalculationRequest.CustomItem(
			name,
			"標準型",
			"式",
			new BigDecimal(unitPrice),
			BigDecimal.ONE,
			"臨時品項",
			true,
			new BigDecimal("999999")
		);
	}

	private QuotationAdminRepository.SchemeItem master(
		String itemCode,
		String itemName,
		String specification,
		String unit,
		String unitPrice,
		int displayOrder
	) {
		return new QuotationAdminRepository.SchemeItem(
			displayOrder,
			1,
			displayOrder,
			itemCode,
			itemName,
			specification,
			unit,
			new BigDecimal(unitPrice),
			"(實做實算)",
			displayOrder,
			"DIRECT",
			true,
			true,
			true,
			true
		);
	}
}
