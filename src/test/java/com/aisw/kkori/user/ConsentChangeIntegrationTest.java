package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선택 동의 변경 검증 (PRD {@code docs/requirements/user/consent.md} 기능 4 — 판정표 전 케이스).
 * 동시성 시나리오는 {@code ConsentChangeConcurrencyTest}가 다룬다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConsentChangeIntegrationTest extends AuthIntegrationTestSupport {

    private static final String MARKETING_URI = "/api/v1/user/consents/marketing";
    private static final String QUERY_URI = "/api/v1/user/consents";

    @Autowired
    private UserService userService;

    private User user;
    private String accessToken;

    @BeforeEach
    void setUpUser() {
        user = saveUser("kakao-8001");
        accessToken = tokenService.issueTokenPair(user.getId()).accessToken();
    }

    @Test
    @DisplayName("이력 없는 marketing에 agreed=true·현재 버전 요청 시 AGREED가 제출 버전으로 append되고 응답·조회에 반영된다")
    void agreeAppendsRowAndReflects() throws Exception {
        putMarketing("{\"agreed\": true, \"version\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].agreed").value(true))
                .andExpect(jsonPath("$.data.consents[3].version").value(1));

        assertThat(marketingRows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo(ConsentAction.AGREED);
                    assertThat(row.getVersion()).isEqualTo(1);
                });

        mockMvc.perform(get(QUERY_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.consents[3].agreed").value(true));
    }

    @Test
    @DisplayName("현재 설정 버전과 다른 version의 agreed=true 요청은 409 U005이고 이력이 생성되지 않는다")
    void versionMismatchIsRejected() throws Exception {
        putMarketing("{\"agreed\": true, \"version\": 99}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U005"));

        assertThat(marketingRows()).isEmpty();
    }

    @Test
    @DisplayName("agreed=true인데 version이 누락되면 400 C002이고 이력이 생성되지 않는다")
    void missingVersionIsRejected() throws Exception {
        putMarketing("{\"agreed\": true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(marketingRows()).isEmpty();
    }

    @Test
    @DisplayName("동의 상태에서 agreed=false 요청은 version 없이 성공하고 WITHDRAWN이 직전 AGREED와 동일 버전으로 기록된다")
    void withdrawRecordsPriorAgreedVersion() throws Exception {
        // 직전 AGREED 버전(2)이 현재 설정 버전(1)과 달라야 "직전 AGREED 버전" 규칙이 판별된다
        seedMarketing(true, 2);

        putMarketing("{\"agreed\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].agreed").value(false));

        assertThat(marketingRows()).hasSize(2);
        UserConsent withdrawn = marketingRows().get(1);
        assertThat(withdrawn.getAction()).isEqualTo(ConsentAction.WITHDRAWN);
        assertThat(withdrawn.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("agreed=false의 version은 무시된다 — version=99여도 성공하고 WITHDRAWN은 요청 버전이 아닌 직전 AGREED 버전으로 기록된다")
    void versionOnWithdrawIsIgnored() throws Exception {
        seedMarketing(true, 1);

        putMarketing("{\"agreed\": false, \"version\": 99}")
                .andExpect(status().isOk());

        assertThat(marketingRows()).hasSize(2);
        assertThat(marketingRows().get(1).getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 상태·동일 버전 재요청은 이력을 생성하지 않고 200과 현재 상태를 반환한다 (멱등)")
    void sameStateSameVersionIsNoop() throws Exception {
        seedMarketing(true, 1);

        putMarketing("{\"agreed\": true, \"version\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].agreed").value(true));

        assertThat(marketingRows()).hasSize(1);
    }

    @Test
    @DisplayName("이력 없는 항목에 agreed=false 요청은 no-op이다 (미동의 = 동일 상태)")
    void withdrawWithoutHistoryIsNoop() throws Exception {
        putMarketing("{\"agreed\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].agreed").value(false));

        assertThat(marketingRows()).isEmpty();
    }

    @Test
    @DisplayName("필수 항목 변경 시도는 400 CONSENT_NOT_CHANGEABLE(U004)로 거부되고 이력이 생성되지 않는다")
    void requiredTypeIsRejected() throws Exception {
        putWithBearer("/api/v1/user/consents/privacy", "{\"agreed\": false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U004"));

        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("알 수 없는 동의 항목은 400 INVALID_CONSENT_TYPE(U003)로 거부된다")
    void unknownTypeIsRejected() throws Exception {
        putWithBearer("/api/v1/user/consents/foo", "{\"agreed\": true, \"version\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U003"));
    }

    @Test
    @DisplayName("agreed 누락·null 요청은 공통 bean validation 400 C002로 거부된다")
    void nullAgreedIsRejected() throws Exception {
        putMarketing("{\"version\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("기록 버전이 설정 버전보다 높으면(설정 롤백 위반 상황) no-op 처리되고 WARN 로그가 남는다 — 역행 방어")
    void staleConfigIsAbsorbedWithWarn(CapturedOutput output) throws Exception {
        seedMarketing(true, 2); // 설정은 v1 — 기록(v2)이 더 높은 비정상 상태를 재현

        putMarketing("{\"agreed\": true, \"version\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].agreed").value(true))
                .andExpect(jsonPath("$.data.consents[3].version").value(2));

        assertThat(marketingRows()).hasSize(1); // 구버전 AGREED가 최신 행으로 얹히지 않는다
        assertThat(output).contains("동의 버전 역행 감지");
    }

    @Test
    @DisplayName("탈퇴된 유저의 변경 요청은 401로 거부된다")
    void deletedUserIsRejected() throws Exception {
        userService.withdraw(user.getId());

        putMarketing("{\"agreed\": true, \"version\": 1}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("변경은 append-only다 — 상태 전환마다 새 행이 추가되고 기존 행은 변경되지 않는다")
    void historyIsAppendOnly() throws Exception {
        putMarketing("{\"agreed\": true, \"version\": 1}").andExpect(status().isOk());
        List<UserConsent> afterFirst = marketingRows();
        UserConsent firstRow = afterFirst.get(0);
        Long firstId = firstRow.getId();
        Instant firstCreatedAt = firstRow.getCreatedAt();

        putMarketing("{\"agreed\": false}").andExpect(status().isOk());
        putMarketing("{\"agreed\": true, \"version\": 1}").andExpect(status().isOk());

        List<UserConsent> history = marketingRows();
        assertThat(history).hasSize(3)
                .extracting(UserConsent::getAction)
                .containsExactly(ConsentAction.AGREED, ConsentAction.WITHDRAWN, ConsentAction.AGREED);
        assertThat(history).allSatisfy(row -> assertThat(row.getVersion()).isEqualTo(1));
        // 첫 행 튜플 불변 — UPDATE 없이 행 수만 늘었다
        assertThat(history.get(0).getId()).isEqualTo(firstId);
        assertThat(history.get(0).getCreatedAt()).isEqualTo(firstCreatedAt);
        assertThat(history.get(0).getAction()).isEqualTo(ConsentAction.AGREED);
    }

    // ── 헬퍼 ──

    private ResultActions putMarketing(String body) throws Exception {
        return putWithBearer(MARKETING_URI, body);
    }

    private ResultActions putWithBearer(String uri, String body) throws Exception {
        return mockMvc.perform(put(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void seedMarketing(boolean agreed, int version) {
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.MARKETING, agreed,
                version, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private List<UserConsent> marketingRows() {
        return userConsentRepository.findByUserId(user.getId()).stream()
                .filter(consent -> consent.getConsentType() == ConsentType.MARKETING)
                .sorted(Comparator.comparing(UserConsent::getId))
                .toList();
    }
}
