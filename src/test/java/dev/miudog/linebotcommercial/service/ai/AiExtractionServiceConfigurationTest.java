package dev.miudog.linebotcommercial.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiExtractionServiceConfigurationTest {

	private final ApplicationContextRunner contextRunner =
	new ApplicationContextRunner().withUserConfiguration(AiExtractionService.class);

	@Test
	void startsWhenTimeoutEnvironmentVariableIsBlank() {
		contextRunner.withPropertyValues("app.ai.timeout-seconds=").run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AiExtractionService.class);
			});
	}
}
