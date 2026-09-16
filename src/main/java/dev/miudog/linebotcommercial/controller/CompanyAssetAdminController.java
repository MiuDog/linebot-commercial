package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.companyasset.CompanyAssetRepository.AssetSet;
import dev.miudog.linebotcommercial.companyasset.CompanyAssetService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 私有公司資產維護 API；網路與 token 驗證由管理 filters 統一套用。
 */
@RestController
@RequestMapping("/api/admin/company-assets")
public class CompanyAssetAdminController {

	private final CompanyAssetService service;

	// 方法：執行此方法定義的受控處理流程。
	public CompanyAssetAdminController(CompanyAssetService service) {
		this.service = service;
	}

	// 方法：執行此方法定義的受控處理流程。
	@PostMapping(value = "/stages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public AssetSet stage(
		@RequestPart("manifest") MultipartFile manifest,
		@RequestPart("files") List<MultipartFile> files,
		@RequestHeader("X-Admin-Actor") String actor
	) throws IOException {
		return service.stage(manifest.getBytes(), files, actor);
	}

	// 方法：執行此方法定義的受控處理流程。
	@PostMapping("/{version}/validation")
	public AssetSet validate(@PathVariable String version) {
		return service.validateAndPromote(version);
	}

	// 方法：執行此方法定義的受控處理流程。
	@PostMapping("/{version}/approval")
	public AssetSet approve(
		@PathVariable String version,
		@RequestHeader("X-Admin-Actor") String actor
	) {
		return service.approve(version, actor);
	}

	// 方法：執行此方法定義的受控處理流程。
	@PostMapping("/{version}/activation")
	public AssetSet activate(
		@PathVariable String version,
		@RequestHeader("X-Admin-Actor") String actor
	) {
		return service.activate(version, actor);
	}

	// 方法：執行此方法定義的受控處理流程。
	@PostMapping("/{version}/rollback")
	public AssetSet rollback(
		@PathVariable String version,
		@RequestHeader("X-Admin-Actor") String actor
	) {
		return service.rollback(version, actor);
	}

	// 方法：執行此方法定義的受控處理流程。
	@GetMapping
	public List<AssetSet> list() {
		return service.list();
	}
}
