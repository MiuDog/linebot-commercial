package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuotationGenerationLauncherTest {

	@Test
	void confirmationOnlyWakesTheDurableWorkerAndNeverRunsAnInMemoryCommand() {
		QuotationGenerationJobWorker worker = Mockito.mock(QuotationGenerationJobWorker.class);
		QuotationGenerationLauncher launcher = new QuotationGenerationLauncher(worker);
		QuotationConfirmedGenerationCommand command = new QuotationConfirmedGenerationCommand(
			null,
			null,
			null,
			"U1"
		);

		launcher.launch(command);

		Mockito.verify(worker).wake();
	}
}
