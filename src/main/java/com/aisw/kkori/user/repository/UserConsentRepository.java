package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 동의 이력 저장소. append-only라 저장 외 조작이 없고, 조회 API는 HBB1-12 범위다. */
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    /** 유저의 동의 이력 전체. 유형별 최신 상태 판정은 호출부가 id 기준으로 계산한다(append-only·소량). */
    List<UserConsent> findByUserId(Long userId);
}
