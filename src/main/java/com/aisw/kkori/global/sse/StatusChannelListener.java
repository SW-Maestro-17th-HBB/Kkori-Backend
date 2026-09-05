package com.aisw.kkori.global.sse;

import org.springframework.data.redis.connection.MessageListener;

/**
 * Redis Pub/Sub 채널을 구독해 SSE로 중계하는 리스너의 공용 계약.
 *
 * <p>도메인 리스너(이력서·리포트)가 이 인터페이스를 구현하고 자기 채널 이름을 알려주면,
 * {@code RedisPubSubConfig}가 컨테이너에 등록한다. 도메인마다 구독 설정 파일을 따로 두지 않으려고 이렇게 했다.
 *
 * <p>Pub/Sub은 발행된 메시지를 구독 중인 모든 연결에 전달하므로, 인스턴스가 몇 대든 모든 인스턴스가
 * 모든 상태 이벤트를 받고 각자 자기 메모리의 SSE 연결에만 보낸다(HBB1-332 — Consumer Group 하나로
 * 스트림을 읽던 구조는 인스턴스가 여럿이면 Redis가 메시지를 나눠 줘서, SSE 연결이 없는 인스턴스가 받은 몫을 버렸다).
 */
public interface StatusChannelListener extends MessageListener {

    /** 구독할 채널 이름. 계약 record의 {@code CHANNEL} 상수를 반환한다. */
    String channel();
}
