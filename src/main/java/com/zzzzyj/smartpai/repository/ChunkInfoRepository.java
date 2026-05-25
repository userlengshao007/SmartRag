package com.zzzzyj.smartpai.repository;

import com.zzzzyj.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
