package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.DeletionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionLogRepository extends JpaRepository<DeletionLog, Long> {
}
