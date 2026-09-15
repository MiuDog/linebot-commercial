package dev.miudog.linebotcommercial.companyasset;

import dev.miudog.linebotcommercial.companyasset.CompanyAssetManifest.AssetObject;
import dev.miudog.linebotcommercial.companyasset.CompanyAssetRepository.AssetSet;
import dev.miudog.linebotcommercial.companyasset.CompanyAssetRepository.StoredManifestObject;
import dev.miudog.linebotcommercial.config.runtime.CompanyProperties;
import dev.miudog.linebotcommercial.service.quotation.QuotationMasterDataCsvService;
import dev.miudog.linebotcommercial.storage.ObjectStorage;
import dev.miudog.linebotcommercial.storage.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公司資產的 stage、驗證、核准、原子啟用與回復流程。
 */
@Service
public class CompanyAssetService {

	private static final String SCHEMA_VERSION = "1";
	private static final long MAXIMUM_ASSET_BYTES = 50L * 1024L * 1024L;

	private final CompanyProperties company;
	private final ObjectStorage storage;
	private final CompanyAssetRepository repository;
	private final ObjectMapper mapper;
	private final QuotationMasterDataCsvService masterData;

	// 方法：執行此方法定義的受控處理流程。
	public CompanyAssetService(
		CompanyProperties company,
		ObjectStorage storage,
		CompanyAssetRepository repository,
		ObjectMapper mapper,
		QuotationMasterDataCsvService masterData
	) {
		this.company = company;
		this.storage = storage;
		this.repository = repository;
		this.mapper = mapper;
		this.masterData = masterData;
	}

	// 方法：執行此方法定義的受控處理流程。
	@Transactional
	public AssetSet stage(byte[] manifestBytes, List<MultipartFile> files, String actor) {
		actor = requireActor(actor);
		CompanyAssetManifest manifest = parse(manifestBytes);
		validateManifest(manifest);
		Map<String, MultipartFile> byName = filesByName(files);
		List<String> stagedKeys = new ArrayList<>();
		List<StoredManifestObject> objects = new ArrayList<>();

		try {
			for (AssetObject declared : manifest.objects()) {
				MultipartFile file = byName.remove(declared.fileName());
				if (file == null) throw new IllegalArgumentException("manifest 宣告檔案不存在");

				byte[] content = file.getBytes();
				validateContent(declared, content, file.getContentType());
				String key = stagingKey(manifest.assetSetVersion(), declared.purpose());
				StoredObject stored = storage.put(
					key,
					content,
					declared.contentType(),
					Map.of(
						"asset-set-version", manifest.assetSetVersion(),
						"purpose", declared.purpose().name()
					)
				);
				stagedKeys.add(key);
				objects.add(new StoredManifestObject(
					declared.purpose(),
					key,
					stored.versionId(),
					declared.contentType(),
					declared.size(),
					declared.sha256().toLowerCase()
				));
			}
			if (!byName.isEmpty()) throw new IllegalArgumentException("資產包含有 manifest 未宣告檔案");

			repository.createStaged(
				manifest,
				sha256(manifestBytes),
				actor,
				objects
			);
			storage.put(
				"company-assets/staging/" + manifest.assetSetVersion() + "/manifest.json",
				manifestBytes,
				"application/json",
				Map.of("asset-set-version", manifest.assetSetVersion())
			);
			return repository.required(manifest.assetSetVersion());
		}
		catch (IOException | RuntimeException exception) {
			for (String key : stagedKeys) {
				try {
					storage.delete(key);
				}
				catch (RuntimeException ignored) {
					// 補償可由 reconciliation 重試；原始失敗必須保留。
				}
			}
			if (exception instanceof RuntimeException runtime) throw runtime;

			throw new IllegalStateException("無法讀取公司資產包", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	@Transactional
	public AssetSet validateAndPromote(String version) {
		AssetSet set = repository.required(version);
		if (!"STAGED".equals(set.status())) throw new IllegalStateException("只有 STAGED 版本可驗證");

		List<String> promoted = new ArrayList<>();
		try {
			for (StoredManifestObject object : repository.objects(set.id())) {
				StoredObject actual = storage.metadata(object.objectKey());
				if (
					actual.contentLength() != object.size()
						|| !object.sha256().equalsIgnoreCase(actual.sha256())
				) {
					throw new IllegalStateException("公司資產完整性驗證失敗：" + object.purpose());
				}
				String target = finalKey(version, object.purpose());
				storage.copy(object.objectKey(), target);
				StoredObject copied = storage.metadata(target);
				if (
					copied.contentLength() != object.size()
						|| !object.sha256().equalsIgnoreCase(copied.sha256())
				) {
					throw new IllegalStateException("公司資產 promote 驗證失敗：" + object.purpose());
				}
				repository.promoteObject(set.id(), object.purpose(), target, copied.versionId());
				promoted.add(target);
			}
			String manifestSource = "company-assets/staging/" + version + "/manifest.json";
			String manifestTarget = "company-assets/sets/" + version + "/manifest.json";
			storage.copy(manifestSource, manifestTarget);
			promoted.add(manifestTarget);
			repository.markValidated(set.id());
			return repository.required(version);
		}
		catch (RuntimeException exception) {
			for (String key : promoted) {
				try {
					storage.delete(key);
				}
				catch (RuntimeException ignored) {
					// 下一次 reconciliation 會清除未被資料庫引用的 promote 物件。
				}
			}
			throw exception;
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	public AssetSet approve(String version, String actor) {
		AssetSet set = repository.required(version);
		repository.approve(set.id(), requireActor(actor));
		return repository.required(version);
	}

	// 方法：執行此方法定義的受控處理流程。
	@Transactional
	public AssetSet activate(String version, String actor) {
		actor = requireActor(actor);
		AssetSet set = repository.required(version);
		requireActivatable(set, false);
		applyMasterData(set);
		repository.activate(set.id(), actor, false);
		return repository.required(version);
	}

	// 方法：執行此方法定義的受控處理流程。
	@Transactional
	public AssetSet rollback(String version, String actor) {
		actor = requireActor(actor);
		AssetSet set = repository.required(version);
		requireActivatable(set, true);
		applyMasterData(set);
		repository.activate(set.id(), actor, true);
		return repository.required(version);
	}

	// 方法：執行此方法定義的受控處理流程。
	public List<AssetSet> list() {
		return repository.list();
	}

	// 方法：執行此方法定義的受控處理流程。
	public byte[] active(CompanyAssetPurpose purpose) {
		return storage.get(repository.activeObject(purpose).objectKey());
	}

	// 方法：執行此方法定義的受控處理流程。
	public long activeSetId() {
		return repository.activeSetId();
	}

	// 方法：執行此方法定義的受控處理流程。
	public byte[] forQuotation(long quotationId, CompanyAssetPurpose purpose) {
		return storage.get(repository.quotationObject(quotationId, purpose).objectKey());
	}

	// 方法：執行此方法定義的受控處理流程。
	private CompanyAssetManifest parse(byte[] bytes) {
		try {
			return mapper.readValue(bytes, CompanyAssetManifest.class);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("manifest 不是有效 JSON", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void validateManifest(CompanyAssetManifest manifest) {
		if (!SCHEMA_VERSION.equals(manifest.schemaVersion())) {
			throw new IllegalArgumentException("不支援的 manifest schemaVersion");
		}
		if (!company.id().equals(manifest.companyId())) {
			throw new IllegalArgumentException("manifest companyId 與部署不符");
		}
		if (
			manifest.assetSetVersion() == null
				|| !manifest.assetSetVersion().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
		) {
			throw new IllegalArgumentException("assetSetVersion 格式不合法");
		}
		if (manifest.objects() == null || manifest.objects().isEmpty()) {
			throw new IllegalArgumentException("manifest 不得沒有物件");
		}
		Set<CompanyAssetPurpose> purposes = EnumSet.noneOf(CompanyAssetPurpose.class);
		Set<String> names = new HashSet<>();
		for (AssetObject object : manifest.objects()) {
			if (object.purpose() == null || !purposes.add(object.purpose())) {
				throw new IllegalArgumentException("manifest 物件用途重複或留空");
			}
			if (
				object.fileName() == null
					|| !object.fileName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
					|| !names.add(object.fileName())
			) {
				throw new IllegalArgumentException("manifest 檔名重複或不安全");
			}
			if (!object.purpose().accepts(object.contentType())) {
				throw new IllegalArgumentException("資產 MIME 與用途不符：" + object.purpose());
			}
			if (object.size() <= 0 || object.size() > MAXIMUM_ASSET_BYTES) {
				throw new IllegalArgumentException("公司資產大小超出允許範圍");
			}
			if (object.sha256() == null || !object.sha256().matches("[0-9a-fA-F]{64}")) {
				throw new IllegalArgumentException("公司資產 SHA-256 格式不合法");
			}
		}
		if (!purposes.containsAll(EnumSet.allOf(CompanyAssetPurpose.class))) {
			throw new IllegalArgumentException("公司資產包缺少必要用途");
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private void validateContent(AssetObject declared, byte[] content, String uploadedType) {
		if (content.length != declared.size()) throw new IllegalArgumentException("公司資產大小不符");

		if (!sha256(content).equalsIgnoreCase(declared.sha256())) {
			throw new IllegalArgumentException("公司資產 SHA-256 不符");
		}
		if (
			uploadedType != null
				&& !uploadedType.equals("application/octet-stream")
				&& !declared.purpose().accepts(uploadedType)
		) {
			throw new IllegalArgumentException("上傳 MIME 與用途不符");
		}
		if (declared.purpose().name().startsWith("TEMPLATE_")
			&& declared.purpose() != CompanyAssetPurpose.TEMPLATE_DEFINITIONS) {
			if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
				throw new IllegalArgumentException("XLSX magic bytes 不正確");
			}
		}
		if (
			declared.purpose() == CompanyAssetPurpose.TEMPLATE_DEFINITIONS
				|| declared.purpose() == CompanyAssetPurpose.CONTACT_DATA
		) {
			try {
				var json = mapper.readTree(content);
				if (declared.purpose() == CompanyAssetPurpose.TEMPLATE_DEFINITIONS) {
					validateTemplateDefinitions(json);
				}
			}
			catch (RuntimeException exception) {
				throw new IllegalArgumentException("JSON 公司資產格式錯誤", exception);
			}
		}
		if (declared.purpose() == CompanyAssetPurpose.ITEM_MASTER) masterData.validateCsv(content);

		if (
			(declared.purpose() == CompanyAssetPurpose.LOGO
				|| declared.purpose() == CompanyAssetPurpose.SEAL)
				&& !isSupportedImage(content)
		) {
			throw new IllegalArgumentException("圖片 magic bytes 不支援");
		}
	}

	// 方法：啟用或回復版本前，以該版本不可變 CSV 交易式取代正式品項主檔。
	private void applyMasterData(AssetSet set) {
		StoredManifestObject itemMaster = repository.objects(set.id())
			.stream()
			.filter(object -> object.purpose() == CompanyAssetPurpose.ITEM_MASTER)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("公司資產版本缺少品項主檔"));
		masterData.importCsv(storage.get(itemMaster.objectKey()));
	}

	// 方法：在改動主檔前確認目標版本狀態，避免無效請求產生暫時性資料變更。
	private void requireActivatable(AssetSet set, boolean rollback) {
		if (
			!("APPROVED".equals(set.status())
				|| rollback && "RETIRED".equals(set.status()))
		) {
			throw new IllegalStateException("目標版本尚未核准或不可回復");
		}
	}

	// 方法：驗證範本定義包含固定 schema 與五種唯一報價格式。
	private void validateTemplateDefinitions(tools.jackson.databind.JsonNode json) {
		if (!"1.1".equals(json.path("schemaVersion").asString())) {
			throw new IllegalArgumentException("範本定義 schemaVersion 不支援");
		}
		Set<String> required = Set.of("BLANK", "CNS", "GENERAL", "MARINE", "SALES");
		Set<String> actual = new HashSet<>();
		var templates = json.path("templates");
		if (!templates.isArray()) throw new IllegalArgumentException("範本定義缺少 templates");

		for (var template : templates) {
			String schemeCode = template.path("schemeCode").asString();
			if (!required.contains(schemeCode) || !actual.add(schemeCode)) {
				throw new IllegalArgumentException("範本定義包含未知或重複格式");
			}
		}
		if (!actual.equals(required)) throw new IllegalArgumentException("範本定義未包含全部五種格式");
	}

	// 方法：執行此方法定義的受控處理流程。
	private static boolean isSupportedImage(byte[] content) {
		boolean jpeg = content.length >= 3
			&& (content[0] & 0xff) == 0xff
			&& (content[1] & 0xff) == 0xd8
			&& (content[2] & 0xff) == 0xff;
		boolean png = content.length >= 8
			&& (content[0] & 0xff) == 0x89
			&& content[1] == 'P'
			&& content[2] == 'N'
			&& content[3] == 'G';
		return jpeg || png;
	}

	// 方法：執行此方法定義的受控處理流程。
	private static Map<String, MultipartFile> filesByName(List<MultipartFile> files) {
		Map<String, MultipartFile> byName = new HashMap<>();
		for (MultipartFile file : files) {
			String name = file.getOriginalFilename();
			if (name == null || byName.putIfAbsent(name, file) != null) {
				throw new IllegalArgumentException("上傳檔名留空或重複");
			}
		}
		return byName;
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String stagingKey(String version, CompanyAssetPurpose purpose) {
		return "company-assets/staging/" + version + "/" + purpose.name().toLowerCase() + ".bin";
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String finalKey(String version, CompanyAssetPurpose purpose) {
		return "company-assets/sets/" + version + "/" + purpose.name().toLowerCase() + ".bin";
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境不支援 SHA-256", exception);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String requireActor(String value) {
		if (value == null || !value.matches("[A-Za-z0-9@._-]{1,80}")) {
			throw new IllegalArgumentException("管理者識別格式不合法");
		}
		return value;
	}
}
