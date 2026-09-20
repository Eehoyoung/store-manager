package com.storemanager.api.legal;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 사업자 정보 — 「전자상거래 등에서의 소비자보호에 관한 법률」 제10조 제1항 표시사항.
 *
 * <p><b>★ 정본은 여기 하나다.</b> 화면·약관·처리방침이 각자 사업자등록번호를 들고 있으면
 * 하나를 고칠 때 나머지가 남는다. 표시 의무가 있는 값이 화면마다 다르면 그 자체가 위반이다.
 * 웹은 {@code GET /api/v1/legal/business-info} 로 받아 쓴다.
 *
 * <p><b>★ {@code require-complete=true} 면 값이 빌 때 기동을 막는다.</b>
 * {@code MasterKeyProvider.require-kms}·{@code AlimtalkProperties} 와 같은 fail-closed 원칙이다.
 * 표시 의무는 "사업자등록번호 없음" 을 화면에 띄우는 것으로 갈음되지 않는다 — 그 상태로
 * 실고객을 받는 것보다 서비스가 안 뜨는 편이 낫다.
 *
 * <p><b>★ 사업자 등록이 나오면 환경변수만 채우고 {@code BUSINESS_INFO_REQUIRED=true} 로 켠다.</b>
 * 코드 수정도 재빌드도 필요 없다. 이것이 "사업자 등록만 하면 출시 가능" 의 실제 의미다.
 */
@Component
@ConfigurationProperties(prefix = "app.business")
public class BusinessInfoProperties {

    /** 상호. 약관·처리방침이 "회사" 로 부르는 그 주체다(기본값은 두 문서와 같아야 한다). */
    private String name = "소담";
    /** 서비스명. 상호와 다를 수 있다(전자상거래법상 표시는 상호 기준). */
    private String serviceName = "리뷰파일럿";
    private String representative = "";
    private String address = "";
    private String phone = "";
    private String email = "";
    private String registrationNumber = "";
    private String mailOrderNumber = "";
    /** 개인정보 보호책임자 — 개인정보 보호법 제30조 제1항 제5호. */
    private String privacyOfficer = "";
    /** 운영 배포에서 true. 표시 의무 값이 하나라도 비면 기동하지 않는다. */
    private boolean requireComplete = false;

    /** 아직 채워지지 않은 표시 항목. 화면이 "준비 중" 을 판단하는 근거이자 기동 게이트의 입력이다. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (representative.isBlank()) {
            missing.add("BUSINESS_REPRESENTATIVE");
        }
        if (address.isBlank()) {
            missing.add("BUSINESS_ADDRESS");
        }
        if (phone.isBlank()) {
            missing.add("BUSINESS_PHONE");
        }
        if (email.isBlank()) {
            missing.add("BUSINESS_EMAIL");
        }
        if (registrationNumber.isBlank()) {
            missing.add("BUSINESS_REGISTRATION_NUMBER");
        }
        if (mailOrderNumber.isBlank()) {
            missing.add("BUSINESS_MAIL_ORDER_NUMBER");
        }
        if (privacyOfficer.isBlank()) {
            missing.add("BUSINESS_PRIVACY_OFFICER");
        }
        return missing;
    }

    @PostConstruct
    void validate() {
        if (!requireComplete) {
            return;
        }
        List<String> missing = missing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "BUSINESS_INFO_REQUIRED=true 인데 다음 값이 비어 있습니다: " + String.join(", ", missing)
                            + ". 전자상거래법 제10조 표시사항이라 비운 채로 실고객을 받을 수 없어 기동을 중단합니다.");
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getRepresentative() {
        return representative;
    }

    public void setRepresentative(String representative) {
        this.representative = representative;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getMailOrderNumber() {
        return mailOrderNumber;
    }

    public void setMailOrderNumber(String mailOrderNumber) {
        this.mailOrderNumber = mailOrderNumber;
    }

    public String getPrivacyOfficer() {
        return privacyOfficer;
    }

    public void setPrivacyOfficer(String privacyOfficer) {
        this.privacyOfficer = privacyOfficer;
    }

    public boolean isRequireComplete() {
        return requireComplete;
    }

    public void setRequireComplete(boolean requireComplete) {
        this.requireComplete = requireComplete;
    }
}
