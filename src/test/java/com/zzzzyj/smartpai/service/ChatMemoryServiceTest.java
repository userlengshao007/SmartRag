package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.model.ChatMemory;
import com.zzzzyj.smartpai.model.User;
import com.zzzzyj.smartpai.repository.ChatMemoryRepository;
import com.zzzzyj.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {

    @Test
    void storesExplicitRememberRequestAsUserMemory() {
        ChatMemoryRepository memoryRepository = mock(ChatMemoryRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        ChatMemoryService service = new ChatMemoryService(memoryRepository, userRepository, estimator, new ObjectMapper());
        User user = new User();
        user.setId(7L);
        user.setPrimaryOrg("ops");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(memoryRepository.findFirstByUserIdAndScopeAndContent(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(estimator.estimateTextTokens("我喜欢简洁回答")).thenReturn(12);
        when(memoryRepository.save(any(ChatMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ChatMemory> saved = service.storeExplicitMemoryHint("7", "conv-1", "请记住：我喜欢简洁回答");

        assertTrue(saved.isPresent());
        assertEquals(ChatMemory.MemoryScope.USER, saved.get().getScope());
        assertEquals(ChatMemory.MemoryType.PREFERENCE, saved.get().getType());
        assertEquals("我喜欢简洁回答", saved.get().getContent());
        assertEquals("ops", saved.get().getOrgTag());
        verify(memoryRepository).save(any(ChatMemory.class));
    }

    @Test
    void buildsContextWithPreferenceEvenWhenQueryDoesNotShareKeywords() {
        ChatMemoryRepository memoryRepository = mock(ChatMemoryRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        ChatMemoryService service = new ChatMemoryService(memoryRepository, userRepository, estimator, new ObjectMapper());
        ChatMemory preference = memory(1L, ChatMemory.MemoryScope.USER, ChatMemory.MemoryType.PREFERENCE,
                "用户喜欢先给结论再解释", 20);
        ChatMemory fact = memory(2L, ChatMemory.MemoryScope.USER, ChatMemory.MemoryType.FACT,
                "SmartRag 部署在内网环境", 18);
        when(memoryRepository.findVisibleMemories(eq(7L), eq("conv-1"), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(preference, fact));

        String context = service.buildMemoryContext("7", "conv-1", "帮我介绍系统功能", 100);

        assertTrue(context.contains("用户喜欢先给结论再解释"));
        assertTrue(context.contains("PREFERENCE"));
    }

    private ChatMemory memory(Long id,
                              ChatMemory.MemoryScope scope,
                              ChatMemory.MemoryType type,
                              String content,
                              int tokenCount) {
        ChatMemory memory = new ChatMemory();
        memory.setId(id);
        memory.setScope(scope);
        memory.setType(type);
        memory.setContent(content);
        memory.setTokenCount(tokenCount);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
