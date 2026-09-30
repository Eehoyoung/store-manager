package com.storemanager.api.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandMonthlyPriceRepository extends JpaRepository<BrandMonthlyPrice, BrandMonthlyPrice.Key> {

    Optional<BrandMonthlyPrice> findByBrandNameAndBillingMonth(String brandName, LocalDate billingMonth);

    boolean existsByBrandNameAndBillingMonth(String brandName, LocalDate billingMonth);

    /** 단가 변경 안내(또는 무변경 확인) 미처리 행 — 매일 09:00 재시도 대상. */
    List<BrandMonthlyPrice> findByNotifiedAtIsNull();
}
