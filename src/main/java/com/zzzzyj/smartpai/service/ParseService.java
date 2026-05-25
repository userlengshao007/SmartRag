package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.model.DocumentVector;
import com.zzzzyj.smartpai.repository.DocumentVectorRepository;
import com.zzzzyj.smartpai.util.DocumentStructureDetector;
import com.zzzzyj.smartpai.util.SemanticCoherenceChecker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);
    private static final int PDF_BOUNDARY_SCAN_LINES = 3;
    private static final int PDF_BOILERPLATE_MIN_LENGTH = 4;
    private static final int PDF_BOILERPLATE_MAX_LENGTH = 120;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    // 控制最终入库的chunk的大小
    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    // 控制触发切割的父块大小 Tika解析器累积文本达到此大小时，触发一次语义切割流程（调用processParentChunk）
    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;

    // 优化文件流读取效率 BufferedInputStream的底层缓冲区大小，减少磁盘I/O次数
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    // 滑动窗口重叠比例（默认10%，用于保持上下文连贯性）
    @Value("${file.parsing.overlap-ratio:0.1}")
    private double overlapRatio;

    // 分块策略：fixed（固定大小）或 smart（智能结构感知）
    @Value("${file.parsing.strategy:smart}")
    private String chunkingStrategy;

    // Embedding 相似度阈值（用于语义连贯性检测）
    @Value("${file.parsing.semantic-similarity-threshold:0.75}")
    private double semanticSimilarityThreshold;

    // 防OOM熔断开关
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;
    
    public ParseService() {
        // 无需初始化，StandardTokenizer是静态方法
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);
        
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                parsePdfAndSave(fileMd5, bufferedStream, userId, orgTag, isPublic);
                logger.info("PDF 文件页级解析和入库完成，fileMd5: {}", fileMd5);
                return;
            }

            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            // 用于存储元数据 比如说文件MIME类型，文档标题，作者，创建时间这些信息
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        logger.info("开始估算文档 Embedding Token");
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                return estimatePdfEmbeddingUsage(bufferedStream);
            }

            StreamingEstimateHandler handler = new StreamingEstimateHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(bufferedStream, handler, metadata, context);
            return handler.snapshot();
        } catch (SAXException e) {
            logger.error("文档 Embedding Token 估算失败", e);
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();
            
            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + 
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
    
    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // 1. 将父块分割成更小的、有语义的子切片（支持滑动窗口重叠）
            List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

            // 2. 将子切片批量保存到数据库
            this.savedChunkCount = ParseService.this.saveChildChunks(
                    fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount, null
            );

            // 3. 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }
    }

    private class StreamingEstimateHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private long estimatedTokens = 0L;
        private int estimatedChunkCount = 0;

        private StreamingEstimateHandler() {
            super(-1);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(buffer.toString(), chunkSize);
            estimatedChunkCount += childChunks.size();
            estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
            buffer.setLength(0);
        }

        private EmbeddingEstimate snapshot() {
            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId, Integer pageNumber) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setPageNumber(pageNumber);
            vector.setAnchorText(buildAnchorText(chunk));
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    private void parsePdfAndSave(String fileMd5, InputStream fileStream, String userId, String orgTag, boolean isPublic) throws IOException {
        try (PDDocument document = PDDocument.load(fileStream)) {
            int savedChunkCount = 0;

            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document);
            for (int pageNumber = 1; pageNumber <= cleanedPageTexts.size(); pageNumber++) {
                String pageText = cleanedPageTexts.get(pageNumber - 1);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                // 支持滑动窗口重叠
                List<String> childChunks = splitTextIntoChunksWithSemantics(pageText, chunkSize);
                savedChunkCount = saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, savedChunkCount, pageNumber);
            }
        }
    }

    private EmbeddingEstimate estimatePdfEmbeddingUsage(InputStream fileStream) throws IOException {
        // 自动管理 PDDocument 资源，使用完后自动关闭它，防止内存泄漏或文件句柄未释放
        try (PDDocument document = PDDocument.load(fileStream)) {
            long estimatedTokens = 0L;
            int estimatedChunkCount = 0;

            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document);
            for (String pageText : cleanedPageTexts) {
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                // 支持滑动窗口重叠
                List<String> childChunks = splitTextIntoChunksWithSemantics(pageText, chunkSize);
                estimatedChunkCount += childChunks.size();
                estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
            }

            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    private List<String> extractCleanPdfPageTexts(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<List<String>> rawPageLines = new ArrayList<>();

        for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String pageText = stripper.getText(document);
            rawPageLines.add(splitPdfLines(pageText));
        }

        Map<String, Integer> topLineCounts = collectBoundaryLineCounts(rawPageLines, true);
        Map<String, Integer> bottomLineCounts = collectBoundaryLineCounts(rawPageLines, false);
        int repeatedThreshold = Math.max(2, Math.min(3, document.getNumberOfPages()));

        List<String> cleanedPages = new ArrayList<>(rawPageLines.size());
        for (int pageIndex = 0; pageIndex < rawPageLines.size(); pageIndex++) {
            List<String> cleanedLines = removePdfBoilerplateLines(
                    rawPageLines.get(pageIndex),
                    topLineCounts,
                    bottomLineCounts,
                    repeatedThreshold
            );
            String cleanedText = String.join("\n", cleanedLines).trim();
            cleanedPages.add(cleanedText);
        }

        return cleanedPages;
    }

    private List<String> splitPdfLines(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return new ArrayList<>();
        }

        String[] lines = pageText.split("\\R");
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(line == null ? "" : line.strip());
        }
        return result;
    }

    private Map<String, Integer> collectBoundaryLineCounts(List<List<String>> pageLines, boolean topBoundary) {
        Map<String, Integer> counts = new HashMap<>();

        for (List<String> lines : pageLines) {
            List<String> boundaryLines = topBoundary
                    ? firstMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES)
                    : lastMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES);

            for (String line : boundaryLines) {
                String key = normalizePdfBoundaryLine(line);
                if (key == null) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
            }
        }

        return counts;
    }

    private List<String> firstMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> lastMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index);
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(0, line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> removePdfBoilerplateLines(
            List<String> lines,
            Map<String, Integer> topLineCounts,
            Map<String, Integer> bottomLineCounts,
            int repeatedThreshold) {

        int start = 0;
        int remainingTopChecks = PDF_BOUNDARY_SCAN_LINES;
        while (start < lines.size() && remainingTopChecks > 0) {
            String line = lines.get(start);
            if (line == null || line.isBlank()) {
                start++;
                continue;
            }

            String key = normalizePdfBoundaryLine(line);
            if (key == null || topLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }

            logger.debug("过滤 PDF 页眉文本: {}", line);
            start++;
            remainingTopChecks--;
        }

        int end = lines.size() - 1;
        int remainingBottomChecks = PDF_BOUNDARY_SCAN_LINES;
        while (end >= start && remainingBottomChecks > 0) {
            String line = lines.get(end);
            if (line == null || line.isBlank()) {
                end--;
                continue;
            }

            String key = normalizePdfBoundaryLine(line);
            if (key == null || bottomLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }

            logger.debug("过滤 PDF 页脚文本: {}", line);
            end--;
            remainingBottomChecks--;
        }

        List<String> cleanedLines = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            cleanedLines.add(lines.get(index));
        }
        return cleanedLines;
    }

    private String normalizePdfBoundaryLine(String line) {
        if (line == null) {
            return null;
        }

        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("\\d+", "#")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.length() < PDF_BOILERPLATE_MIN_LENGTH || normalized.length() > PDF_BOILERPLATE_MAX_LENGTH) {
            return null;
        }

        return normalized;
    }

    private boolean isPdfDocument(BufferedInputStream stream) throws IOException {
        stream.mark(bufferSize);
        byte[] header = stream.readNBytes(5);
        stream.reset();
        return header.length == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }

    private String buildAnchorText(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }

        String normalized = chunk.replaceAll("\\s+", " ").trim();
        int maxLength = 120;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    /**
     * 智能文本分割主入口（支持两种策略）
     * @param text 待分割文本
     * @param chunkSize 分块大小
     * @return 分块列表
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        if ("smart".equalsIgnoreCase(chunkingStrategy)) {
            logger.info("使用智能结构感知分块策略");
            return smartSplitWithStructure(text, chunkSize);
        } else {
            logger.info("使用固定大小分块策略");
            return fixedSizeSplit(text, chunkSize);
        }
    }

    /**
     * 智能结构感知分块（混合模式）
     * 流程：文档结构粗切分 → 512字符细分超长块 → 语义连贯性合并短块 + 滑动窗口重叠
     */
    private List<String> smartSplitWithStructure(String text, int chunkSize) {
        int overlapSize = (int) (chunkSize * overlapRatio);

        // 第一步：基于文档结构进行粗切分
        List<String> roughChunks = splitByDocumentStructure(text);
        logger.debug("结构粗切分完成，得到 {} 个块", roughChunks.size());

        // 第二步：对超长块进行512字符智能细分
        List<String> refinedChunks = new ArrayList<>();
        for (String roughChunk : roughChunks) {
            if (roughChunk.length() > chunkSize * 1.5) {
                // 超长块，需要细分
                refinedChunks.addAll(fixedSizeSplit(roughChunk, chunkSize));
            } else {
                refinedChunks.add(roughChunk);
            }
        }
        logger.debug("超长块细分完成，得到 {} 个块", refinedChunks.size());

        // 第三步：语义连贯性检测与短块合并
        List<String> mergedChunks = mergeShortChunks(refinedChunks, chunkSize, overlapSize);
        logger.debug("语义合并完成，最终得到 {} 个块", mergedChunks.size());

        // 第四步：应用滑动窗口重叠
        List<String> finalChunks = applySlidingWindow(mergedChunks, overlapSize);

        logger.info("智能分块完成，chunkSize: {}, overlapSize: {}, 总块数: {}",
                chunkSize, overlapSize, finalChunks.size());

        return finalChunks;
    }

    /**
     * 基于文档结构的粗切分
     */
    private List<String> splitByDocumentStructure(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();
        DocumentStructureDetector.TextType lastType = null;

        for (String paragraph : paragraphs) {
            String firstLine = paragraph.split("\n")[0];
            DocumentStructureDetector.TextType currentType =
                DocumentStructureDetector.detectLineType(firstLine);

            // 检测到新章节或标题，结束当前块
            if (currentType == DocumentStructureDetector.TextType.CHAPTER_HEADING ||
                currentType == DocumentStructureDetector.TextType.SECTION_HEADING) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(paragraph);
                lastType = currentType;
                continue;
            }

            // 表格/代码块：整段保护
            if (currentType == DocumentStructureDetector.TextType.TABLE_ROW ||
                currentType == DocumentStructureDetector.TextType.CODE_BLOCK) {
                if (currentChunk.length() > 0 && currentChunk.length() < chunkSize) {
                    // 如果当前块不大，可以带上这个表格/代码块
                    currentChunk.append("\n\n").append(paragraph);
                } else {
                    // 否则单独成块
                    if (currentChunk.length() > 0) {
                        chunks.add(currentChunk.toString().trim());
                        currentChunk.setLength(0);
                    }
                    chunks.add(paragraph.trim());
                }
                lastType = currentType;
                continue;
            }

            // 普通段落：按大小合并
            if (currentChunk.length() + paragraph.length() > chunkSize * 1.5) {
                // 累积过多，保存当前块
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(paragraph);
            } else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }

            lastType = currentType;
        }

        // 添加最后一个块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 固定大小分块（原有的三层语义分割）
     */
    private List<String> fixedSizeSplit(String text, int chunkSize) {
        int overlapSize = (int) (chunkSize * overlapRatio);

        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落（传入overlapSize）
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize, overlapSize);
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    // 保留最后 overlapSize 个字符作为重叠内容
                    if (overlapSize > 0 && currentChunk.length() > overlapSize) {
                        currentChunk = new StringBuilder(
                            currentChunk.substring(currentChunk.length() - overlapSize)
                        );
                    } else {
                        currentChunk = new StringBuilder();
                    }
                }
                // 开始新chunk
                currentChunk.append(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        logger.debug("滑动窗口分块完成，chunkSize: {}, overlapSize: {}, 总块数: {}",
                chunkSize, overlapSize, chunks.size());

        return chunks;
    }

    /**
     * 语义连贯性检测与短块合并
     */
    private List<String> mergeShortChunks(List<String> chunks, int chunkSize, int overlapSize) {
        if (chunks.isEmpty()) return chunks;

        List<String> merged = new ArrayList<>();
        StringBuilder pendingChunk = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            String current = chunks.get(i);

            if (pendingChunk.length() == 0) {
                pendingChunk.append(current);
            } else {
                String pending = pendingChunk.toString();

                // 检查是否应该合并
                boolean shouldMerge = SemanticCoherenceChecker.shouldMerge(pending, current);

                if (shouldMerge && pending.length() + current.length() <= chunkSize * 1.2) {
                    // 合并
                    pendingChunk.append("\n\n").append(current);
                    logger.debug("合并 chunk[{}] 和 chunk[{}]", merged.size(), i);
                } else {
                    // 不合并，保存待处理块
                    merged.add(pending.trim());
                    pendingChunk = new StringBuilder(current);
                }
            }
        }

        // 添加最后一个待处理块
        if (pendingChunk.length() > 0) {
            merged.add(pendingChunk.toString().trim());
        }

        return merged;
    }

    /**
     * 应用滑动窗口重叠
     */
    private List<String> applySlidingWindow(List<String> chunks, int overlapSize) {
        if (overlapSize <= 0 || chunks.size() <= 1) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevChunk = chunks.get(i - 1);
            String currentChunk = chunks.get(i);

            // 提取前一个chunk的最后 overlapSize 字符
            String overlap = prevChunk.substring(Math.max(0, prevChunk.length() - overlapSize));

            // 将重叠部分添加到当前chunk开头
            String chunkWithOverlap = overlap + currentChunk;
            result.add(chunkWithOverlap);
        }

        logger.debug("滑动窗口重叠应用完成，overlapSize: {}", overlapSize);
        return result;
    }

    /**
     * 分割长段落，按句子边界（支持滑动窗口重叠）
     * @param paragraph 待分割段落
     * @param chunkSize 分块大小
     * @param overlapSize 重叠大小
     * @return 分块列表
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    // 保留最后 overlapSize 个字符作为重叠内容
                    if (overlapSize > 0 && currentChunk.length() > overlapSize) {
                        currentChunk = new StringBuilder(
                            currentChunk.substring(currentChunk.length() - overlapSize)
                        );
                    } else {
                        currentChunk = new StringBuilder();
                    }
                }

                // 如果单个句子太长，按词分割（传入overlapSize）
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize, overlapSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割（支持滑动窗口重叠）
     * @param sentence 待分割句子
     * @param chunkSize 分块大小
     * @param overlapSize 重叠大小
     * @return 分块列表
     */
    private List<String> splitLongSentence(String sentence, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();

        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;

                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    // 保留最后 overlapSize 个字符作为重叠内容
                    if (overlapSize > 0 && currentChunk.length() > overlapSize) {
                        currentChunk = new StringBuilder(
                            currentChunk.substring(currentChunk.length() - overlapSize)
                        );
                    } else {
                        currentChunk = new StringBuilder();
                    }
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }

            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}, overlapSize: {}",
                    sentence.length(), termList.size(), chunks.size(), overlapSize);

        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize, overlapSize);
        }

        return chunks;
    }
    
    /**
     * 备用方案：按字符分割（支持滑动窗口重叠）
     */
    private List<String> splitByCharacters(String sentence, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                // 保留最后 overlapSize 个字符作为重叠内容
                if (overlapSize > 0 && currentChunk.length() > overlapSize) {
                    currentChunk = new StringBuilder(
                        currentChunk.substring(currentChunk.length() - overlapSize)
                    );
                } else {
                    currentChunk = new StringBuilder();
                }
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
