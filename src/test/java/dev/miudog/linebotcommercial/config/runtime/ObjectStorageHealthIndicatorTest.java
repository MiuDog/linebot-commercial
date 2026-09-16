package dev.miudog.linebotcommercial.config.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectStorageHealthIndicatorTest {

	// 測試：Bucket 可查詢時 readiness contributor 回報 UP。
	@Test
	void reportsUpWhenBucketIsAvailable() {
		S3Client client = mock(S3Client.class);
		when(client.headBucket(any(HeadBucketRequest.class)))
			.thenReturn(HeadBucketResponse.builder().build());

		var indicator = new ObjectStorageConfiguration()
			.objectStorageHealthIndicator(client, properties());

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	// 測試：外部物件儲存失效時 readiness contributor 回報 DOWN 且不暴露例外文字。
	@Test
	void reportsDownWithoutExposingProviderDetails() {
		S3Client client = mock(S3Client.class);
		when(client.headBucket(any(HeadBucketRequest.class)))
			.thenThrow(new IllegalStateException("credential-and-endpoint-detail"));

		var health = new ObjectStorageConfiguration()
			.objectStorageHealthIndicator(client, properties())
			.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails())
			.containsEntry("reason", "object-storage-unavailable")
			.doesNotContainValue("credential-and-endpoint-detail");
	}

	// 方法：建立健康檢查所需的最小固定設定。
	private ObjectStorageProperties properties() {
		return new ObjectStorageProperties("", "ap-northeast-1", "company-bucket", "", "", false);
	}
}
