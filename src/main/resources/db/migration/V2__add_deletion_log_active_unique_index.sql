-- 유저당 활성 삭제 요청 1건 — docs/requirements/user/account.md §deletion_log 인덱스, deletion.md 기능 2.
-- 활성 = 파기가 아직 끝나지 않은 상태(PENDING_PURGE·PURGING·FAILED). PURGED·CANCELLED는 audit 이력이라
-- 같은 유저에 여러 건 남을 수 있으므로 부분 인덱스로 한정한다(JPA 애너테이션으로는 표현 불가 — HBB1-349).
-- 앱은 user 행 잠금 + 조건부 UPDATE로 이미 직렬화하므로 이 인덱스는 발동하지 않는 최후 방어선이다.
-- prod 적용 전 확인: SELECT user_id, count(*) FROM deletion_log
--                     WHERE status IN ('PENDING_PURGE', 'PURGING', 'FAILED') GROUP BY user_id HAVING count(*) > 1;
CREATE UNIQUE INDEX ux_deletion_log_active_user
    ON deletion_log (user_id)
    WHERE status IN ('PENDING_PURGE', 'PURGING', 'FAILED');
