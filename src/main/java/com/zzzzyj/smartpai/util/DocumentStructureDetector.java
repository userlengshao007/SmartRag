package com.zzzzyj.smartpai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档结构特征检测器
 * 用于识别章节标题、列表项、表格边界、代码块等文档结构元素
 */
public class DocumentStructureDetector {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStructureDetector.class);

    // 章节标题模式（中文：第一章、第二章...；英文：Chapter 1, Chapter 2...）
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百]+[章篇节]|Chapter\\s+\\d+|CHAPTER\\s+\\d+|\\d+\\.\\s+.*).*"
    );

    // 小节标题模式（1.1, 1.2, 2.1...）
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^\\s*\\d+\\.\\d+(?:\\.\\d+)?\\s+.*"
    );

    // 列表项模式（- item, * item, 1. item, 1、item）
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile(
            "^\\s*(?:[-*•]|\\d+[.、])\\s+.*"
    );

    // Markdown 表格行模式
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "^\\s*\\|.*\\|\\s*$"
    );

    // 代码块标记
    private static final String CODE_BLOCK_MARKER = "```";

    // 表格分隔行（|---|---|）
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile(
            "^\\s*\\|[\\s:-]+\\|.*$"
    );

    /**
     * 检测文本类型
     */
    public enum TextType {
        CHAPTER_HEADING,      // 章标题
        SECTION_HEADING,      // 节标题
        LIST_ITEM,            // 列表项
        TABLE_ROW,            // 表格行
        TABLE_SEPARATOR,      // 表格分隔符
        CODE_BLOCK,           // 代码块
        PARAGRAPH,            // 普通段落
        EMPTY_LINE            // 空行
    }

    /**
     * 检测单行文本的类型
     */
    public static TextType detectLineType(String line) {
        if (line == null || line.trim().isEmpty()) {
            return TextType.EMPTY_LINE;
        }

        String trimmed = line.trim();

        // 检测代码块
        if (trimmed.startsWith(CODE_BLOCK_MARKER)) {
            return TextType.CODE_BLOCK;
        }

        // 检测章标题
        if (CHAPTER_PATTERN.matcher(trimmed).matches()) {
            return TextType.CHAPTER_HEADING;
        }

        // 检测节标题
        if (SECTION_PATTERN.matcher(trimmed).matches()) {
            return TextType.SECTION_HEADING;
        }

        // 检测列表项
        if (LIST_ITEM_PATTERN.matcher(trimmed).matches()) {
            return TextType.LIST_ITEM;
        }

        // 检测表格行
        if (TABLE_ROW_PATTERN.matcher(trimmed).matches()) {
            if (TABLE_SEPARATOR_PATTERN.matcher(trimmed).matches()) {
                return TextType.TABLE_SEPARATOR;
            }
            return TextType.TABLE_ROW;
        }

        return TextType.PARAGRAPH;
    }

    /**
     * 受保护的内容块（表格、代码块等，不应被切断）
     */
    public static class ProtectedBlock {
        private final int startPos;
        private final int endPos;
        private final TextType type;
        private final String content;

        public ProtectedBlock(int startPos, int endPos, TextType type, String content) {
            this.startPos = startPos;
            this.endPos = endPos;
            this.type = type;
            this.content = content;
        }

        public int getStartPos() { return startPos; }
        public int getEndPos() { return endPos; }
        public TextType getType() { return type; }
        public String getContent() { return content; }

        public boolean containsPosition(int pos) {
            return pos >= startPos && pos < endPos;
        }
    }

    /**
     * 提取所有受保护的内容块（表格、代码块）
     */
    public static List<ProtectedBlock> extractProtectedBlocks(String text) {
        List<ProtectedBlock> blocks = new ArrayList<>();

        // 提取代码块
        extractCodeBlocks(text, blocks);

        // 提取表格
        extractTables(text, blocks);

        logger.debug("提取到 {} 个受保护块", blocks.size());
        return blocks;
    }

    private static void extractCodeBlocks(String text, List<ProtectedBlock> blocks) {
        int startIndex = 0;
        while (startIndex < text.length()) {
            int blockStart = text.indexOf(CODE_BLOCK_MARKER, startIndex);
            if (blockStart == -1) break;

            int blockEnd = text.indexOf(CODE_BLOCK_MARKER, blockStart + 3);
            if (blockEnd == -1) {
                // 未找到结束标记，保护到文本末尾
                blockEnd = text.length();
            } else {
                blockEnd += 3; // 包含 ```
            }

            blocks.add(new ProtectedBlock(
                blockStart,
                blockEnd,
                TextType.CODE_BLOCK,
                text.substring(blockStart, blockEnd)
            ));

            startIndex = blockEnd;
        }
    }

    private static void extractTables(String text, List<ProtectedBlock> blocks) {
        String[] lines = text.split("\n", -1);
        int currentPos = 0;
        int tableStart = -1;
        StringBuilder tableContent = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            TextType lineType = detectLineType(line);

            if (lineType == TextType.TABLE_ROW || lineType == TextType.TABLE_SEPARATOR) {
                if (tableStart == -1) {
                    tableStart = currentPos;
                    tableContent.setLength(0);
                }
                tableContent.append(line).append("\n");
            } else {
                // 表格结束
                if (tableStart != -1 && tableContent.length() > 0) {
                    blocks.add(new ProtectedBlock(
                        tableStart,
                        currentPos,
                        TextType.TABLE_ROW,
                        tableContent.toString().trim()
                    ));
                    tableStart = -1;
                }
            }

            currentPos += line.length() + 1; // +1 for \n
        }

        // 处理文件末尾的表格
        if (tableStart != -1 && tableContent.length() > 0) {
            blocks.add(new ProtectedBlock(
                tableStart,
                text.length(),
                TextType.TABLE_ROW,
                tableContent.toString().trim()
            ));
        }
    }

    /**
     * 检查位置是否在受保护块内
     */
    public static ProtectedBlock findProtectedBlockAt(List<ProtectedBlock> blocks, int position) {
        for (ProtectedBlock block : blocks) {
            if (block.containsPosition(position)) {
                return block;
            }
        }
        return null;
    }
}
