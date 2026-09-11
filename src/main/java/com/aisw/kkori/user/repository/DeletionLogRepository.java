package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.PurgeDetail;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeletionLogRepository extends JpaRepository<DeletionLog, Long> {

    /**
     * 유저의 최신 탈퇴 요청 — 카카오 로그인의 상태별 판정 재료(PRD 기능 4).
     * 정렬을 명시하지 않은 findFirst는 반환 로그가 비결정적이므로 금지.
     */
    Optional<DeletionLog> findFirstByUserIdOrderByRequestedAtDescIdDesc(Long userId);

    /**
     * deletion_log 행 잠금 — 상태 판정과 후속 상태 변경(식별정보 파기·복구) 사이에
     * 파기 배치의 {@code PURGING} 선점이 끼어드는 것을 차단한다(잠금 순서 user → deletion_log).
     * <p>주의: 반환 엔티티는 이미 1차 캐시에 있으면 낡은 인스턴스다 — 잠금 획득 용도로만 쓰고,
     * 상태는 반드시 {@link #findStatusById}로 재조회할 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeletionLog d where d.id = :id")
    Optional<DeletionLog> findWithLockById(@Param("id") Long id);

    /**
     * 현재 status만 스칼라로 재조회한다 — 판정 지점이 user·로그 행 잠금 획득 후 배치 선점 여부를
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

    // ── 파기 배치 (PRD deletion.md 기능 2) ──
    // 선점·종결·실패·기록은 전부 조건부 벌크 UPDATE다. 선점이 기록한 updated_at(선점 시각)이
    // 그 건의 후속 쓰기 전부에 대한 펜싱 토큰이라, stale 회수로 재선점된 건에 대한 이전 인스턴스의
    // 쓰기는 0행으로 무시된다. 벌크 쿼리라 updated_at을 명시 갱신하며, updated_at을 바꾸는 쓰기는
    // 선점과 종결·실패 전환뿐이다(중간 기록은 펜스 값을 유지해야 한다).

    /**
     * 파기 후보 — 유예 경과 PENDING_PURGE · FAILED · stale PURGING(선점 인스턴스 중단)의 합.
     * 유예 경계 정각({@code requested_at + 유예 = now})은 경과로 판정한다 — 복구 가능 판정
     * ({@link #cancelPendingPurge}의 {@code requested_at > graceCutoff})의 정확한 여집합.
     */
    @Query("""
            select d from DeletionLog d
            where (d.status = com.aisw.kkori.user.domain.DeletionStatus.PENDING_PURGE
                   and d.requestedAt <= :graceCutoff)
               or d.status = com.aisw.kkori.user.domain.DeletionStatus.FAILED
               or (d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
                   and d.updatedAt <= :staleCutoff)
            order by d.requestedAt asc, d.id asc
            """)
    List<DeletionLog> findPurgeCandidates(@Param("graceCutoff") Instant graceCutoff,
                                          @Param("staleCutoff") Instant staleCutoff);

    /**
     * 조건부 선점 — 후보 조건을 술어에 중복 포함해 스캔~선점 사이의 상태 변화(복구의 CANCELLED,
     * 타 인스턴스의 선점)를 흡수한다. 영향 행 수 1인 인스턴스만 파기를 진행한다. {@code claimedAt}이
     * 이 건의 펜싱 토큰이 된다. 잠금은 잡지 않는다 — 복구 경로가 로그 행 잠금을 쥐면 이 UPDATE가
     * 커밋까지 대기한 뒤 재평가되어 0행이 된다(READ COMMITTED의 갱신 후 재검사).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING,
                d.updatedAt = :claimedAt
            where d.id = :id
              and ((d.status = com.aisw.kkori.user.domain.DeletionStatus.PENDING_PURGE
                    and d.requestedAt <= :graceCutoff)
                   or d.status = com.aisw.kkori.user.domain.DeletionStatus.FAILED
                   or (d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
                       and d.updatedAt <= :staleCutoff))
            """)
    int claimForPurge(@Param("id") Long id, @Param("claimedAt") Instant claimedAt,
                      @Param("graceCutoff") Instant graceCutoff, @Param("staleCutoff") Instant staleCutoff);

    /** 중간 기록 — 펜스 값(updated_at)은 유지한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.purgeDetail = :detail
            where d.id = :id
              and d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
              and d.updatedAt = :claimedAt
            """)
    int recordPurgeDetail(@Param("id") Long id, @Param("claimedAt") Instant claimedAt,
                          @Param("detail") PurgeDetail detail);

    /** unlink 완료·생략 후 스냅샷 제거 (PRD 기능 4) — 펜스 값 유지. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.providerId = null
            where d.id = :id
              and d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
              and d.updatedAt = :claimedAt
            """)
    int clearProviderSnapshot(@Param("id") Long id, @Param("claimedAt") Instant claimedAt);

    /** PURGING → FAILED (재시도 대상). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.status = com.aisw.kkori.user.domain.DeletionStatus.FAILED,
                d.updatedAt = :now,
                d.purgeDetail = :detail
            where d.id = :id
              and d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
              and d.updatedAt = :claimedAt
            """)
    int failPurge(@Param("id") Long id, @Param("claimedAt") Instant claimedAt,
                  @Param("now") Instant now, @Param("detail") PurgeDetail detail);

    /** PURGING → PURGED (종결) — 파기 완료 시각 기록, 스냅샷 NULL 확인. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeletionLog d
            set d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGED,
                d.purgedAt = :now,
                d.updatedAt = :now,
                d.providerId = null,
                d.purgeDetail = :detail
            where d.id = :id
              and d.status = com.aisw.kkori.user.domain.DeletionStatus.PURGING
              and d.updatedAt = :claimedAt
            """)
    int completePurge(@Param("id") Long id, @Param("claimedAt") Instant claimedAt,
                      @Param("now") Instant now, @Param("detail") PurgeDetail detail);
}
