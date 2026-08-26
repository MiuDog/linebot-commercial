package dev.miudog.linebotcommercial.desktop.control;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miudog.linebotcommercial.desktop.config.SecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 service 控制端點發布、機密保護、命令轉送與關閉清理。
 */
class ServiceControlServerTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：啟動後發布受保護端點，並讓 client 以 nonce 認證後查詢狀態。
	@Test
	void shouldPublishProtectedEndpointAndServeCommands() throws IOException {
		LinkedBlockingQueue<ServiceControlCommand> commands = new LinkedBlockingQueue<>();
		ServiceControlEndpointRepository repository = new ServiceControlEndpointRepository(
			temporaryDirectory,
			new ReversingSecretStore()
		);
		ServiceControlClient client = new ServiceControlClient(repository);

		try (ServiceControlServer server = new ServiceControlServer(repository)) {
			server.start(command -> {
				commands.add(command);

				return ServiceControlResponse.RUNNING;

			});

			ServiceControlEndpoint endpoint = repository.load().orElseThrow();
			String storedData = Files.readString(repository.endpointFile(), StandardCharsets.ISO_8859_1);

			assertThat(client.request(ServiceControlCommand.STATUS)).isEqualTo(ServiceControlResponse.RUNNING);
			assertThat(commands).containsExactly(ServiceControlCommand.STATUS);
			assertThat(storedData).doesNotContain(endpoint.nonce());
		}

		assertThat(repository.load()).isEmpty();
		assertThat(client.request(ServiceControlCommand.STATUS)).isEqualTo(ServiceControlResponse.UNAVAILABLE);
	}

	/**
	 * 以反轉位元組模擬可逆平台機密保護。
	 */
	private static final class ReversingSecretStore implements SecretStore {

		// 方法：反轉明文位元組模擬平台保護。
		@Override
		public byte[] protect(byte[] plaintext) {
			return reverse(plaintext);
		}

		// 方法：再次反轉受保護資料還原明文。
		@Override
		public byte[] unprotect(byte[] protectedData) {
			return reverse(protectedData);
		}

		// 方法：測試替身一律回報可使用。
		@Override
		public boolean available() {
			return true;
		}

		// 方法：建立來源位元組反向排列的獨立副本。
		private byte[] reverse(byte[] source) {
			byte[] result = source.clone();

			for (int left = 0, right = result.length - 1; left < right; left++, right--) {
				byte value = result[left];
				result[left] = result[right];
				result[right] = value;
			}

			return result;
		}
	}
}
