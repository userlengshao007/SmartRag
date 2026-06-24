package com.zzzzyj.smartpai.repository;

import com.zzzzyj.smartpai.model.ChatMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMemoryRepository extends JpaRepository<ChatMemory, Long> {

    /**
     * 查找用户的第一个匹配的记忆记录（不限会话）。
     * 
     * 用于检查用户级别或跨会话的记忆是否存在相同内容。
     *
     * @param userId 用户ID
     * @param scope 记忆作用域（USER或CONVERSATION）
     * @param content 记忆内容
     * @return 匹配的记忆记录，不存在则返回Optional.empty()
     */
    Optional<ChatMemory> findFirstByUserIdAndScopeAndContent(Long userId,
                                                             ChatMemory.MemoryScope scope,
                                                             String content);

    /**
     * 查找用户在指定会话中的第一个匹配的记忆记录。
     * 
     * 用于在特定会话范围内检查是否存在相同内容的记忆。
     *
     * @param userId 用户ID
     * @param scope 记忆作用域（USER或CONVERSATION）
     * @param conversationId 会话ID
     * @param content 记忆内容
     * @return 匹配的记忆记录，不存在则返回Optional.empty()
     */
    Optional<ChatMemory> findFirstByUserIdAndScopeAndConversationIdAndContent(Long userId,
                                                                              ChatMemory.MemoryScope scope,
                                                                              String conversationId,
                                                                              String content);

    /**
     * 查询用户可见的所有记忆记录，包含用户级记忆和指定会话的记忆。
     * 
     * 查询逻辑：
     * 1. 用户级记忆（scope=USER）：对所有会话可见
     * 2. 会话级记忆（scope=CONVERSATION）：仅对指定会话可见
     * 
     * 结果按更新时间倒序排列，优先显示最近活跃的记忆。
     *
     * @param userId 用户ID
     * @param conversationId 会话ID，用于过滤会话级记忆
     * @param userScope 用户级作用域枚举值（固定传入MemoryScope.USER）
     * @param conversationScope 会话级作用域枚举值（固定传入MemoryScope.CONVERSATION）
     * @param pageable 分页和排序参数
     * @return 记忆记录列表，按活跃度降序排列
     */
    @Query("""
            select m from ChatMemory m
            where m.user.id = :userId
              and (
                    m.scope = :userScope
                    or (m.scope = :conversationScope and m.conversationId = :conversationId)
                  )
            order by m.updatedAt desc, m.createdAt desc
            """)
    List<ChatMemory> findVisibleMemories(@Param("userId") Long userId,
                                         @Param("conversationId") String conversationId,
                                         @Param("userScope") ChatMemory.MemoryScope userScope,
                                         @Param("conversationScope") ChatMemory.MemoryScope conversationScope,
                                         Pageable pageable);
}
