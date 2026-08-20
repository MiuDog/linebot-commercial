package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUsageCostCalculatorTest {

	// 驗證三種 token 依每百萬 token 費率各自計價後加總。
	@Test
	void calculatesConfiguredPriceWithBigDecimalFormula() {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "1.25", "0.125", "10");

		AiUsageAudit audit = calculator.calculate("gpt-test", 1_000_000, 1_000_000, 1_000_000);

		assertThat(audit.priceStatus()).isEqualTo(AiPriceStatus.CONFIGURED);
		assertThat(audit.totalCost()).isEqualByComparingTo("11.375");
		assertThat(audit.inputRatePerMillion()).isEqualByComparingTo("1.25");
		assertThat(audit.cachedInputRatePerMillion()).isEqualByComparingTo("0.125");
		assertThat(audit.outputRatePerMillion()).isEqualByComparingTo("10");
	}

	// 驗證費率不完整時不猜測金額，並標記為未設定。
	@Test
	void marksPriceUnconfiguredWhenAnyRateIsMissing() {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "1.25", "", "10");

		AiUsageAudit audit = calculator.calculate("gpt-unknown", 100, 20, 30);

		assertThat(audit.priceStatus()).isEqualTo(AiPriceStatus.UNCONFIGURED);
		assertThat(audit.totalCost()).isNull();
		assertThat(audit.cachedInputRatePerMillion()).isNull();
	}

	// 驗證不接受不可能的負 token 數，避免產生負成本。
	@Test
	void rejectsNegativeTokenCounts() {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "1", "1", "1");

		assertThatThrownBy(() -> calculator.calculate("gpt-test", -1, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
