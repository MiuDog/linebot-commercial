package dev.miudog.linebotcommercial.storage;

import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.config.runtime.ObjectStorageProperties;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * AWS SDK v2 實作，可同時連接公有雲 S3 與本地 MinIO。
 */
public class S3ObjectStorage implements ObjectStorage {

	private static final String HASH_METADATA_KEY = "content-sha256";

	private final S3Client client;
	private final String bucket;
	private final String companyPrefix;

	// 方法：執行此方法定義的受控處理流程。
	public S3ObjectStorage(
		S3Client client,
		ObjectStorageProperties properties,
		CompanyProperties companyProperties
	) {
		this.client = client;
		this.bucket = properties.bucket();
		this.companyPrefix = "companies/" + companyProperties.id() + "/";
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public StoredObject put(
		String relativeKey,
		byte[] content,
		String contentType,
		Map<String, String> metadata
	) {
		String key = fullKey(relativeKey);
		String sha256 = sha256(content);
		Map<String, String> storedMetadata = new java.util.HashMap<>(metadata);
		storedMetadata.put(HASH_METADATA_KEY, sha256);

		try {
			PutObjectResponse response = client.putObject(
				PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType(contentType)
					.metadata(storedMetadata)
					.build(),
				RequestBody.fromBytes(content)
			);
			return new StoredObject(
				key,
				response.versionId(),
				response.eTag(),
				sha256,
				content.length,
				contentType
			);
		}
		catch (RuntimeException exception) {
			throw failure("無法寫入公司資產：" + key, exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public byte[] get(String relativeKey) {
		String key = fullKey(relativeKey);
		try {
			ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
				GetObjectRequest.builder().bucket(bucket).key(key).build()
			);
			return response.asByteArray();

		}
		catch (RuntimeException exception) {
			throw failure("無法讀取公司資產：" + key, exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public StoredObject metadata(String relativeKey) {
		String key = fullKey(relativeKey);
		try {
			HeadObjectResponse response = client.headObject(
				HeadObjectRequest.builder().bucket(bucket).key(key).build()
			);
			return new StoredObject(
				key,
				response.versionId(),
				response.eTag(),
				response.metadata().get(HASH_METADATA_KEY),
				response.contentLength(),
				response.contentType()
			);
		}
		catch (RuntimeException exception) {
			throw failure("無法查詢公司資產：" + key, exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public void copy(String sourceRelativeKey, String targetRelativeKey) {
		String sourceKey = fullKey(sourceRelativeKey);
		String targetKey = fullKey(targetRelativeKey);
		try {
			client.copyObject(
				CopyObjectRequest.builder()
					.copySource(bucket + "/" + sourceKey)
					.destinationBucket(bucket)
					.destinationKey(targetKey)
					.build()
			);
		}
		catch (RuntimeException exception) {
			throw failure("無法發佈公司資產：" + targetKey, exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public void delete(String relativeKey) {
		String key = fullKey(relativeKey);
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (RuntimeException exception) {
			throw failure("無法刪除暫存公司資產：" + key, exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private String fullKey(String relativeKey) {
		String normalized = relativeKey == null
			? ""
			: relativeKey.replace('\\', '/').strip();
		if (normalized.isBlank() || normalized.length() > 900) throw invalidKey();

		for (String segment : normalized.split("/", -1)) {
			if (!segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalidKey();
		}
		return companyPrefix + normalized;
	}

	// 方法：建立不回顯不可信 key 的固定驗證錯誤。
	private IllegalArgumentException invalidKey() {
		return new IllegalArgumentException("物件相對 key 格式不合法");
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String sha256(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(content));

		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境不支援 SHA-256", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static ObjectStorageException failure(String message, RuntimeException cause) {
		return new ObjectStorageException(message, cause);
	}
}
