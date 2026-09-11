package com.aisw.kkori.global.oauth;

import com.aisw.kkori.global.oauth.dto.KakaoErrorResponse;
import com.aisw.kkori.global.oauth.dto.KakaoUnlinkResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 어드민 키 방식 연결 끊기 — {@code POST /v1/user/unlink}, {@code Authorization: KakaoAK {어드민 키}},
 * {@code target_id_type=user_id&target_id={회원번호}} (PRD deletion.md 기능 4 인터페이스 요구사항).
 *
 * <p>응답 해석: 2xx → 완료. HTTP 400 + 에러 코드 {@code -101}("해당 앱에 카카오계정 연결이 완료되지 않은
 * 사용자") → 이미 해제된 것으로 보고 완료(멱등). 그 외는 {@link KakaoUnlinkException}(재시도 대상).
 * 예외 메시지에는 응답 본문을 싣지 않는다 — 본문에 회원번호가 포함될 수 있다(공통: 로그·개인정보).
 */
@Component
public class KakaoAdminUnlinkClient implements KakaoUnlinkClient {

    /** 카카오 공통 에러 코드 — 앱에 연결되지 않은 사용자. */
    static final int NOT_LINKED_USER = -101;

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;

    public KakaoAdminUnlinkClient(RestClient.Builder restClientBuilder, KakaoOAuthProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public Outcome unlink(String providerId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", providerId);
        try {
            restClient.post()
                    .uri(properties.unlinkUri())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoUnlinkResponse.class);
            return Outcome.UNLINKED;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 && errorCodeOf(e) == NOT_LINKED_USER) {
                return Outcome.ALREADY_UNLINKED;
            }
            // 원인으로 보존하지 않는다 — 원본 예외 메시지에는 응답 본문이 실린다(회원번호 포함 가능)
            throw new KakaoUnlinkException("카카오 unlink 응답 오류 (http=" + e.getStatusCode().value()
                    + ", code=" + errorCodeOf(e) + ")");
        } catch (RestClientException e) {
            throw new KakaoUnlinkException("카카오 unlink 통신 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 오류 본문의 {@code code} — 본문이 JSON이 아니거나 필드가 없으면 0(해당 없음). */
    private static int errorCodeOf(RestClientResponseException e) {
        try {
            KakaoErrorResponse body = e.getResponseBodyAs(KakaoErrorResponse.class);
            return body == null || body.code() == null ? 0 : body.code();
        } catch (RuntimeException parseFailure) {
            return 0;
        }
    }
}
