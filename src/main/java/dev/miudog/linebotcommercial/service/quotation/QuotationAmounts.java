package dev.miudog.linebotcommercial.service.quotation;

import java.math.BigDecimal;

/**
 * 【職責】報價計算的結果，是計算器與 PDF 產生器之間的資料契約。
 *
 * <p><b>欄位組成為佔位設計</b>，等實際公式與報價單版面確定後再調整。
 * 先定義出來是為了讓上下游可以先串起來，而不是等公式到位才開始接。
 *
 * <p><b>預定資料流：</b>
 * {@code QuotationCalculator.calculate → QuotationAmounts
 * → QuotationPdfService.generate}。
 *
 * @param unitPrice 單價（佔位）
 * @param quantity  數量（佔位）
 * @param subtotal  未稅小計（佔位）
 * @param tax       稅額（佔位）
 * @param total     含稅總計（佔位）
 */
public record
QuotationAmounts(BigDecimal unitPrice, BigDecimal quantity, BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}
