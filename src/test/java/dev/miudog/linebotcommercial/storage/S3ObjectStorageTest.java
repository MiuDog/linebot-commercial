package dev.miudog.linebotcommercial.storage;

import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.config.runtime.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3ObjectStorageTest {

	// 測試：所有寫入都加上唯一公司前綴並保存內容 hash。
	@Test
	void prefixesGeneratedKeysAndAddsContentHash() {
		S3Client client = mock(S3Client.class);
		when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenReturn(PutObjectResponse.builder().versionId("version-1").eTag("etag").build());
		S3ObjectStorage storage = storage(client);

		storage.put(
			"assets/ZD123/01.png",
			"image".getBytes(StandardCharsets.UTF_8),
			"image/png",
			Map.of("source", "line")
		);

		var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(client).putObject(request.capture(), any(RequestBody.class));
		assertThat(request.getValue().key()).isEqualTo("companies/company-test/assets/ZD123/01.png");
		assertThat(request.getValue().metadata())
			.containsEntry("source", "line")
			.containsKey("content-sha256");
	}

	// 測試：空白、路徑跳脫、URL 與不安全 segment 在呼叫 S3 前即被拒絕。
	@Test
	void rejectsUnsafeRelativeKeysBeforeCallingS3() {
		S3Client client = mock(S3Client.class);
		S3ObjectStorage storage = storage(client);

		for (String key : new String[] { "", "..", "../file", "folder/../file", "./file", "https://evil" }) {
			assertThatThrownBy(() -> storage.get(key))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("物件相對 key 格式不合法");
		}
		verifyNoInteractions(client);
	}

	// 方法：建立綁定單一公司與 Bucket 的測試儲存服務。
	private S3ObjectStorage storage(S3Client client) {
		return new S3ObjectStorage(
			client,
			new ObjectStorageProperties("", "ap-northeast-1", "bucket", "", "", false),
			new CompanyProperties("company-test")
		);
	}
}
