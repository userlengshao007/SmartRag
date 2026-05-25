package com.zzzzyj.smartpai.repository;

import com.zzzzyj.smartpai.model.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {
}
