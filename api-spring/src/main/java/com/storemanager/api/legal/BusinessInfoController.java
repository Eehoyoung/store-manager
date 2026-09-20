package com.storemanager.api.legal;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사업자 정보 공개 조회 — 화면 하단 표시용.
 *
 * <p><b>★ 로그인 없이 열린다.</b> 전자상거래법 제10조의 표시는 <b>거래 전</b> 소비자가 볼 수
 * 있어야 하는 것이라 로그인 뒤로 숨기면 표시하지 않은 것과 같다. 여기 실리는 값은 전부
 * 사업자 등록부에 공개된 항목이고 개인정보가 아니다.
 *
 * <p><b>★ {@code pending} 은 숨기지 않고 내려보낸다.</b> 아직 못 채운 항목을 화면이 빈칸으로
 * 그리면 "표시했는데 값이 없는" 상태가 되고 아무도 눈치채지 못한다. 무엇이 비었는지
 * 말해 주는 쪽이 낫다 — 위험 사유 코드를 모르면 코드 그대로 보여주는 것과 같은 규율이다.
 */
@RestController
public class BusinessInfoController {

    private final BusinessInfoProperties props;

    public BusinessInfoController(BusinessInfoProperties props) {
        this.props = props;
    }

    @GetMapping("/api/v1/legal/business-info")
    public BusinessInfo businessInfo() {
        return new BusinessInfo(props.getName(), props.getServiceName(), props.getRepresentative(),
                props.getAddress(), props.getPhone(), props.getEmail(), props.getRegistrationNumber(),
                props.getMailOrderNumber(), props.getPrivacyOfficer(), props.missing());
    }

    public record BusinessInfo(String name, String serviceName, String representative, String address,
            String phone, String email, String registrationNumber, String mailOrderNumber,
            String privacyOfficer, List<String> pending) {}
}
