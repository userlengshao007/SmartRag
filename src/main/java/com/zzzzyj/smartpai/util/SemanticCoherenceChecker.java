package com.zzzzyj.smartpai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 语义连贯性检测器
 * 用于判断相邻文本块是否应该合并
 */
public class SemanticCoherenceChecker {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCoherenceChecker.class);

    // 未完成符号（表示内容待续）
    private static final Set<Character> INCOMPLETE_ENDINGS = new HashSet<>(
        Arrays.asList('：', ':', '、', '，', ',', '（', '(')
    );

    // 连接词（表示语义延续）
    private static final Set<String> CONTINUATION_WORDS = new HashSet<>(
        Arrays.asList("因此", "所以", "然而", "但是", "此外", "另外", "同时",
                     "接着", "然后", "于是", "因而", "反之", "否则")
    );

    // 短 chunk 阈值（小于此值考虑合并）
    private static final int SHORT_CHUNK_THRESHOLD = 100;

    /**
     * 检查两个 chunk 是否应该合并
     * @param previousChunk 前一个 chunk
     * @param nextChunk 后一个 chunk
     * @return true 如果应该合并
     */
    public static boolean shouldMerge(String previousChunk, String nextChunk) {
        if (previousChunk == null || nextChunk == null) {
            return false;
        }

        String prevTrimmed = previousChunk.trim();
        String nextTrimmed = nextChunk.trim();

        // 规则1：前一个chunk以未完成符号结尾
        if (endsWithIncompleteSymbol(prevTrimmed)) {
            logger.debug("检测到未完成符号结尾，建议合并");
            return true;
        }

        // 规则2：后一个chunk以连接词开头
        if (startsWithContinuationWord(nextTrimmed)) {
            logger.debug("检测到连接词开头，建议合并");
            return true;
        }

        // 规则3：前一个chunk过短且后一个chunk不是新章节
        if (prevTrimmed.length() < SHORT_CHUNK_THRESHOLD &&
            !DocumentStructureDetector.detectLineType(nextTrimmed.split("\n")[0])
                .equals(DocumentStructureDetector.TextType.CHAPTER_HEADING)) {
            logger.debug("前chunk过短且非新章节，建议合并");
            return true;
        }

        // 规则4：跨页段落检测（前chunk以非完整句子结尾）
        if (isCrossPageBreak(prevTrimmed, nextTrimmed)) {
            logger.debug("检测到跨页断点，建议合并");
            return true;
        }

        return false;
    }

    /**
     * 使用 Embedding 相似度检测语义连贯性
     * @param chunk1 第一个 chunk
     * @param chunk2 第二个 chunk
     * @param similarityThreshold 相似度阈值（默认 0.7）
     * @return true 如果相似度高且应该合并
     */
    public static boolean shouldMergeByEmbedding(String chunk1, String chunk2,
                                                  double similarityThreshold,
                                                  SemanticSimilarityProvider provider) {
        if (provider == null) {
            logger.warn("未提供 Embedding Provider，跳过相似度检测");
            return false;
        }

        try {
            double similarity = provider.calculateSimilarity(chunk1, chunk2);
            logger.debug("Embedding 相似度: {:.3f}", similarity);
            return similarity >= similarityThreshold;
        } catch (Exception e) {
            logger.error("计算 Embedding 相似度失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 综合检测（规则 + Embedding）
     */
    public static boolean shouldMergeComprehensive(String previousChunk, String nextChunk,
                                                    double embeddingThreshold,
                                                    SemanticSimilarityProvider provider) {
        // 先用规则快速判断
        if (shouldMerge(previousChunk, nextChunk)) {
            return true;
        }

        // 再用 Embedding 二次确认
        return shouldMergeByEmbedding(previousChunk, nextChunk, embeddingThreshold, provider);
    }

    private static boolean endsWithIncompleteSymbol(String text) {
        if (text.isEmpty()) return false;
        char lastChar = text.charAt(text.length() - 1);
        return INCOMPLETE_ENDINGS.contains(lastChar);
    }

    private static boolean startsWithContinuationWord(String text) {
        for (String word : CONTINUATION_WORDS) {
            if (text.startsWith(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCrossPageBreak(String prevChunk, String nextChunk) {
        // 检测前chunk末尾是否没有完整句子结束符
        boolean prevIncomplete = !prevChunk.matches(".*[。！？；.!?;]\\s*$");

        // 检测后chunk开头是否不是新章节/标题
        String[] nextLines = nextChunk.split("\n", 2);
        boolean nextNotHeading = nextLines.length > 0 &&
            !DocumentStructureDetector.detectLineType(nextLines[0])
                .equals(DocumentStructureDetector.TextType.CHAPTER_HEADING);

        return prevIncomplete && nextNotHeading;
    }

    /**
     * Embedding 相似度提供者接口（由调用方实现）
     */
    @FunctionalInterface
    public interface SemanticSimilarityProvider {
        double calculateSimilarity(String text1, String text2);
    }
}
