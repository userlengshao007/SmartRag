// 创建测试文件：FileUploadPerformanceTest.java
package com.zzzzyj.smartpai.test;

import com.zzzzyj.smartpai.service.UploadService;
import com.zzzzyj.smartpai.service.VectorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@SpringBootTest
public class FileUploadPerformanceTest {

    @Autowired
    private UploadService uploadService;

    @Autowired
    private VectorizationService vectorizationService;

    private static final String TEST_USER_ID = "1";
    private static final String TEST_ORG_TAG = "test";
    private static final boolean IS_PUBLIC = false;

    @Test
    public void testFileUploadAndVectorizationSpeed() throws Exception {
        // 测试不同大小的文件
        testFileSize(1, "1MB.txt");     // 1MB
        testFileSize(5, "5MB.txt");     // 5MB
        testFileSize(10, "10MB.txt");   // 10MB
        testFileSize(50, "50MB.txt");   // 50MB
        testFileSize(100, "100MB.txt");   // 50MB
    }

    private void testFileSize(int sizeMB, String fileName) throws Exception {
        byte[] fileContent = generateTestContent(sizeMB);
        String fileMd5 = "test_" + sizeMB + "mb_" + System.currentTimeMillis();
        int totalChunks = (int) Math.ceil((double) fileContent.length / (5 * 1024 * 1024)); // 5MB分片

        System.out.println("\n========== 测试文件: " + fileName + " (" + sizeMB + "MB) ==========");

        // 1. 上传阶段
        long uploadStartTime = System.currentTimeMillis();
        uploadFileChunks(fileContent, fileMd5, totalChunks, fileName);
        String mergedUrl = uploadService.mergeChunks(fileMd5, fileName, TEST_USER_ID);
        long uploadEndTime = System.currentTimeMillis();
        long uploadDuration = uploadEndTime - uploadStartTime;

        System.out.printf("上传耗时: %d ms (%.2f seconds)%n", uploadDuration, uploadDuration / 1000.0);
        System.out.printf("上传速度: %.2f MB/s%n", (double) sizeMB / (uploadDuration / 1000.0));

        // 2. 向量化阶段
        long vectorStartTime = System.currentTimeMillis();
        var result = vectorizationService.vectorizeWithUsage(fileMd5, TEST_USER_ID, TEST_ORG_TAG, IS_PUBLIC, TEST_USER_ID);
        long vectorEndTime = System.currentTimeMillis();
        long vectorDuration = vectorEndTime - vectorStartTime;

        System.out.printf("向量化耗时: %d ms (%.2f seconds)%n", vectorDuration, vectorDuration / 1000.0);
        System.out.printf("向量化速度: %.2f chunks/s%n", (double) result.actualChunkCount() / (vectorDuration / 1000.0));
        System.out.printf("总令牌数: %d, 分块数: %d%n", result.actualEmbeddingTokens(), result.actualChunkCount());

        // 3. 总耗时
        long totalDuration = uploadEndTime - uploadStartTime + vectorDuration;
        System.out.printf("总耗时: %d ms (%.2f seconds)%n", totalDuration, totalDuration / 1000.0);
        System.out.println("=====================================");
    }

    private void uploadFileChunks(byte[] fileContent, String fileMd5, int totalChunks, String fileName) throws Exception {
        int chunkSize = 5 * 1024 * 1024; // 5MB per chunk

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, fileContent.length);
            byte[] chunkContent = new byte[end - start];
            System.arraycopy(fileContent, start, chunkContent, 0, chunkContent.length);

            MultipartFile chunkFile = new MockMultipartFile(
                    "file",
                    fileName,
                    "text/plain",
                    new ByteArrayInputStream(chunkContent)
            );

            uploadService.uploadChunk(
                    fileMd5,
                    i,
                    fileContent.length,
                    fileName,
                    chunkFile,
                    TEST_ORG_TAG,
                    IS_PUBLIC,
                    TEST_USER_ID
            );
        }
    }

    private byte[] generateTestContent(int sizeMB) {
        int sizeBytes = sizeMB * 1024 * 1024;
        StringBuilder content = new StringBuilder();
        String testText = "This is test content for file upload performance testing. ";

        while (content.length() < sizeBytes) {
            content.append(testText);
        }

        return content.toString().substring(0, sizeBytes).getBytes(StandardCharsets.UTF_8);
    }
}