package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface DeletionLogRepository extends JpaRepository<DeletionLog, Long> {

    /**
     * 유저의 최신 탈퇴 요청 — 카카오 로그인의 상태별 판정 재료(PRD 기능 4).
     * 정렬을 명시하지 않은 findFirst는 반환 로그가 비결정적이므로 금지.
     */
    Optional<DeletionLog> findFirstByUserIdOrderByRequestedAtDescIdDesc(Long userId);

    /**
     * 현재 status만 스칼라로 재조회한다 — 복구 제출이 user 잠금 획득 후 배치 선점 여부를
     * 재확인하는 용도. 선조회한 엔티티는 1차 캐시에 남아 그 사이의 상태 전이가 보이지 않고,
     * {@code findById} 재호출도 캐시를 반환하므로 재확인이 되지 않는다.
     * Optional이라 레코드 소실도 empty로 자연 처리된다.
     */
    @Query("select d.status from DeletionLog d where d.id = :id")
    Optional<DeletionStatus> findStatusById(@Param("id") Long id);

    /**
     * 복구의 조건부 CANCELLED 전환 — provider_id 스냅샷 NULL 처리와 updated_at 갱신을 겸한다.
     * 유예 조건({@code requested_at > graceCutoff})을 술어에 중복 포함해 판정 순서를 착오한
     * 호출도 유예 초과 건을 취소하지 못하게 한다(PRD 기능 4).
     *
     * <p>{@code clearAutomatically}를 켜지 않는다 — 직전에 잠금 조회한 user 엔티티가 detach되어
     * 이후 {@code user.restore()}의 dirty checking이 유실된다. ({@code UserRepository.softDeleteById}가
     * 컨텍스트를 비우는 것은 그 흐름이 UPDATE 후 재조회하기 때문 — 이 흐름에 복사하지 말 것.)
     * UPDATE 이후 선조회한 DeletionLog 엔티티는 stale이므로 재검사 금지 — 판정은 영향 행 수로만.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.status = com.aisw.kkori.user.domain.DeletionStatus.CANCELLED,
                d.providerId = null,
                d.updatedAt = :now
            where d.id = :id
              and d.status = com.aisw.kkori.user.domain.DeletionStatus.PENDING_PURGE
              and d.requestedAt > :graceCutoff
            """)
    int cancelPendingPurge(@Param("id") Long id, @Param("now") Instant now, @Param("graceCutoff") Instant graceCutoff);
}
