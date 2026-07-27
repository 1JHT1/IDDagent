package com.IDDagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 附件文件文本提取服务 —— 从上传的 Excel、PDF、Word 文档中提取原始文本，
 * 供后续 LLM 进行语义解析。不再做规则匹配。
 */
@Service
public class FileParserService {

    private static final Logger log = LoggerFactory.getLogger(FileParserService.class);

    /**
     * 提取文件的原始文本内容
     * @param filePath 文件磁盘路径
     * @return 提取到的原始文本
     */
    public String extractText(Path filePath) throws Exception {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            return extractExcelText(filePath);
        } else if (fileName.endsWith(".pdf")) {
            return extractPdfText(filePath);
        } else if (fileName.endsWith(".docx")) {
            return extractDocxText(filePath);
        } else {
            // 其他文件（如图片、MD）不支持文本提取
            log.warn("不支持的文件类型: {}", fileName);
            return "";
        }
    }

    // ============================================================
    // Excel 文本提取（带行列结构）
    // ============================================================
    private String extractExcelText(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = Files.newInputStream(filePath);
             Workbook wb = new XSSFWorkbook(is)) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        cells.add(getCellString(row.getCell(ci)));
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
                sb.append("\n");
            }
        }
        String text = sb.toString();
        log.info("Excel 文本提取完成: {} 字符, file={}", text.length(), filePath.getFileName());
        return text;
    }

    // ============================================================
    // PDF 文本提取
    // ============================================================
    private String extractPdfText(Path filePath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            log.info("PDF 文本提取完成: {} 字符, file={}", text.length(), filePath.getFileName());
            return text;
        }
    }

    // ============================================================
    // Word 文本提取（段落 + 表格）
    // ============================================================
    private String extractDocxText(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {

            // 段落
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            // 表格
            for (XWPFTable table : doc.getTables()) {
                sb.append("--- 表格 ---\n");
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().trim());
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
                sb.append("--- 表格结束 ---\n\n");
            }
        }
        String text = sb.toString();
        log.info("Word 文本提取完成: {} 字符, file={}", text.length(), filePath.getFileName());
        return text;
    }

    // ============================================================
    // 辅助方法
    // ============================================================
    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.format("%,.0f", val);
                }
                yield String.format("%,.2f", val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.format("%,.2f", cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }
}
