package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

/** 동의 이력 저장소. append-only라 저장 외 조작이 없고, 조회 API는 HBB1-12 범위다. */
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
}
