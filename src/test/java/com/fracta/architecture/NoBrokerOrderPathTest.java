package com.fracta.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.external.broker.plug.PlugPathPolicy;

/**
 * <b>증권사에 주문을 보내는 코드가 존재할 수 없게 한다.</b>
 *
 * <p>이 프로젝트의 매매는 자체 오더북에서 체결된다. 증권사 API는 시세 조회 전용이다.
 * 런타임 차단({@code PlugApiClient})과 부팅 검증({@code BrokerSafetyValidator})이 이미 있지만,
 * 둘 다 <i>실행되어야</i> 막는다. 이 테스트는 <b>주문 경로가 소스에 나타나기만 해도</b> 빌드를 깬다.
 *
 * <p>"안 짰습니다"보다 "짤 수 없습니다"가 강한 보증이다. 사람이 규율을 지키는 것에
 * 기대지 않는다 — 나중에 누가(작성자 자신을 포함해) 실수로 넣어도 여기서 걸린다.
 */
class NoBrokerOrderPathTest {

    /**
     * PLUG 주문 경로. <b>경로 형태로만</b> 판정한다.
     *
     * <p>처음에는 {@code cashSell} 같은 메서드명도 넣었는데, 결제 서비스의 지역변수
     * {@code creditSeller}가 걸려 오탐이 났다. 증권사를 부르려면 경로가 반드시 필요하므로
     * 경로만 봐도 충분하고, 그래야 오탐이 없다.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "/krstock/order/",
            "/krbond/order/",
            "/krfuture/order/",
            "/krgold/order/",
            "/gbstock/order/",
            "/gbfuture/order/",
            "/order/v1/");

    @Test
    @DisplayName("main 소스 어디에도 증권사 주문 경로가 없다")
    void mainSourceHasNoOrderPath() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    if (source.contains(fragment)) {
                        violations.add(main.relativize(file) + " → " + fragment);
                    }
                }
            }
        }

        assertThat(violations)
                .as("증권사 주문 경로가 소스에 있다. 이 프로젝트는 외부에 주문을 보내지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("경로 정책은 조회만 허용한다 — 주문 경로는 전부 거부")
    void policyRejectsOrderPaths() {
        assertThat(PlugPathPolicy.isAllowed("/krstock/quote/v1/currentPrice")).isTrue();
        assertThat(PlugPathPolicy.isAllowed("/krstock/quote/v1/etfCurrent")).isTrue();

        // 계좌 조회는 읽기지만 쓰지 않으므로 화이트리스트에서 뺐다.
        // 부르지 않는 경로를 열어 두면 "시세만 조회한다"는 보증이 그만큼 약해진다.
        assertThat(PlugPathPolicy.isAllowed("/n2/acctinfo")).isFalse();

        // 주문·잔고 변경 계열
        assertThat(PlugPathPolicy.isAllowed("/krstock/order/v1/cashBuy")).isFalse();
        assertThat(PlugPathPolicy.isAllowed("/krstock/order/v1/cashSell")).isFalse();
        assertThat(PlugPathPolicy.isAllowed("/gbstock/order/v1/buy")).isFalse();
        // 조회라도 정책에 없으면 막는다 — 화이트리스트지 블랙리스트가 아니다
        assertThat(PlugPathPolicy.isAllowed("/krstock/inquiry/v1/balance")).isFalse();
        assertThat(PlugPathPolicy.isAllowed(null)).isFalse();
        assertThat(PlugPathPolicy.isAllowed("")).isFalse();
    }
}
