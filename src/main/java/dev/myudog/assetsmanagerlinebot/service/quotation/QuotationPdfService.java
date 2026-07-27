package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.service.ai.ExtractedSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 【職責】把規格、金額與資訊圖套進 PDF 報價單模板，產出成品檔案。
 *
 * <p><b>本類別目前是佔位實作，模板尚未提供。</b>
 * 需要補齊的資訊：
 * <ul>
 *   <li>模板檔案本身，以及它是「可填表單的 AcroForm」還是「純版面」</li>
 *   <li>各欄位在模板上的名稱或座標</li>
 *   <li>資訊圖要貼在第幾頁、左下角座標與寬高</li>
 * </ul>
 * 上述確定後才好挑 PDF 函式庫——若模板是 AcroForm 表單，填欄位即可；
 * 若是純版面，就得靠絕對座標描繪。兩者做法差很多，因此先不引入依賴。
 */
@Service
public class QuotationPdfService {

    /** 報價單模板路徑，留空待填。 */
    @Value("${app.quotation.template-path:}")
    private String templatePath;

    /** 產出的報價單存放目錄，留空時預設放在 storage 底下的 quotations。 */
    @Value("${app.quotation.output-path:}")
    private String outputPath;

    // ===== 以下皆為佔位常數，待模板確定後替換 =====

    /** 佔位：資訊圖要貼在第幾頁（從 0 起算）。 */
    private static final int IMAGE_PAGE_INDEX = 0;

    /** 佔位：資訊圖左下角 X 座標。 */
    private static final float IMAGE_X = 0f;

    /** 佔位：資訊圖左下角 Y 座標。 */
    private static final float IMAGE_Y = 0f;

    /** 佔位：資訊圖寬度。 */
    private static final float IMAGE_WIDTH = 0f;

    /** 佔位：資訊圖高度。 */
    private static final float IMAGE_HEIGHT = 0f;

    /**
     * 模板是否已設定。指令入口應先問過，才能在流程一開始就給出明確錯誤。
     *
     * @return 模板路徑有值時為 true
     */
    public boolean isConfigured() {
        return templatePath != null && !templatePath.isBlank();
    }

    /**
     * 產生報價單 PDF。
     *
     * @param spec       AI 擷取出的規格欄位
     * @param amounts    計算出的金額
     * @param infoImage  要貼進報價單的資訊圖
     * @return 產出的 PDF 檔案路徑
     * @throws UnsupportedOperationException 模板與座標尚未提供時一律拋出
     */
    public Path generate(ExtractedSpec spec, QuotationAmounts amounts, byte[] infoImage) {
        throw new UnsupportedOperationException(
                "報價單模板尚未提供：請補上 PDF 模板檔、欄位對應與資訊圖貼附座標後，實作 QuotationPdfService#generate");
    }

    /**
     * 佔位：把規格與金額填進模板欄位。
     *
     * @param spec    規格欄位
     * @param amounts 金額
     */
    @SuppressWarnings("unused")
    private void fillFields(ExtractedSpec spec, QuotationAmounts amounts) {
        throw new UnsupportedOperationException("模板欄位對應待定");
    }

    /**
     * 佔位：把資訊圖貼到模板的指定位置。
     *
     * @param infoImage 資訊圖位元組
     */
    @SuppressWarnings("unused")
    private void stampInfoImage(byte[] infoImage) {
        throw new UnsupportedOperationException("資訊圖貼附座標待定");
    }
}
