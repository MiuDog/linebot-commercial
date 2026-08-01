package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.AssetFileReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageSyncControllerTest {

	@Mock
	AssetFileReconciliationService reconciliationService;

	StorageSyncController controller;

	@BeforeEach
	void setUp() {
		controller = new StorageSyncController(reconciliationService);
		ReflectionTestUtils.setField(controller, "syncToken", "secret-token");
	}

	@Test
	void executesOneSynchronizationForTheConfiguredToken() throws Exception {
		when(reconciliationService.synchronize())
			.thenReturn(
				new AssetFileReconciliationService.SyncResult(
					1,
					2,
					3,
					4,
					5
				)
			);

		var response = controller.synchronize("secret-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().status()).isEqualTo("completed");
		assertThat(response.getBody().deleted()).isEqualTo(2);
		verify(reconciliationService).synchronize();
	}

	@Test
	void rejectsSynchronizationWithoutTheConfiguredToken() throws Exception {
		var response = controller.synchronize("wrong-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(reconciliationService);
	}
}
