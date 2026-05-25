package com.zzzzyj.smartpai.repository;

import com.zzzzyj.smartpai.model.MilvusPendingDelete;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MilvusPendingDeleteRepository extends JpaRepository<MilvusPendingDelete, Long> {

    /**
     * 查询重试次数未超过上限的待删除记录
     */
    List<MilvusPendingDelete> findByRetryCountLessThan(int maxRetry);

    /**
     * 是否已存在相同 fileMd5 的待删除记录（避免重复插入）
     */
    boolean existsByFileMd5(String fileMd5);
}
