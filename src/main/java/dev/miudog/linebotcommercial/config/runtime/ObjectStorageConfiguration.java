package dev.miudog.linebotcommercial.config.runtime;

import dev.miudog.linebotcommercial.storage.ObjectStorage;
import dev.miudog.linebotcommercial.storage.S3ObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.URI;

/**
 * 建立 S3 相容客戶端；雲端可使用預設 IAM，本地 MinIO 可明確提供金鑰。
 */
@Configuration
public class ObjectStorageConfiguration {

	// 方法：執行此方法定義的受控處理流程。
	@Bean
	public S3Client s3Client(ObjectStorageProperties properties) {
		S3ClientBuilder builder = S3Client.builder()
			.region(Region.of(properties.region()))
			.forcePathStyle(properties.forcePathStyle());

		if (hasText(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}

		boolean hasAccessKey = hasText(properties.accessKey());
		boolean hasSecretKey = hasText(properties.secretKey());
		if (hasAccessKey != hasSecretKey) {
			throw new IllegalStateException("物件儲存 access key 與 secret key 必須同時設定");
		}
		if (hasAccessKey) {
			builder.credentialsProvider(
				StaticCredentialsProvider.create(
					AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
				)
			);
		}
		return builder.build();
	}

	// 方法：執行此方法定義的受控處理流程。
	@Bean
	public ObjectStorage objectStorage(
		S3Client client,
		ObjectStorageProperties properties,
		CompanyProperties companyProperties
	) {
		return new S3ObjectStorage(client, properties, companyProperties);
	}

	// 方法：將 Bucket 可用性納入 readiness，但不在健康回應洩漏雲端錯誤細節。
	@Bean
	public HealthIndicator objectStorageHealthIndicator(
		S3Client client,
		ObjectStorageProperties properties
	) {
		return () -> {
			try {
				client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
				return Health.up().build();
			}
			catch (RuntimeException exception) {
				return Health.down().withDetail("reason", "object-storage-unavailable").build();
			}
		};
	}

	// 方法：執行此方法定義的受控處理流程。
	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
