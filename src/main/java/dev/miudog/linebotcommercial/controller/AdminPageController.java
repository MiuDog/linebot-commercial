package dev.miudog.linebotcommercial.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 將管理頁根路徑穩定導向實際靜態首頁。
 */
@Controller
public class AdminPageController {

	// 方法：將首頁與管理頁根路徑轉送到靜態首頁檔案。
	@GetMapping({"/", "/admin", "/admin/"})
	public String adminPage() {
		return "forward:/admin/index.html";
	}
}
