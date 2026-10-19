package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PriceQuoteTest {

    @Test
    void totalEquals25000_29000_33000_그리고_공급가_더하기_VAT는_총액이다() {
        assertThat(PricingTier.tieredQuote(1)).isEqualTo(new PriceQuote(22727, 2273, 25000));
        assertThat(PricingTier.tieredQuote(2)).isEqualTo(new PriceQuote(26364, 2636, 29000));
        assertThat(PricingTier.tieredQuote(3)).isEqualTo(new PriceQuote(30000, 3000, 33000));
        for (int n = 1; n <= 3; n++) {
            PriceQuote q = PricingTier.tieredQuote(n);
            assertThat(q.supply() + q.vat()).isEqualTo(q.total());
        }
    }

    @Test
    void 공급가_기준_가격은_예전_계산과_같다() {
        assertThat(PriceQuote.ofSupply(30000)).isEqualTo(new PriceQuote(30000, 3000, 33000));
        assertThat(PriceQuote.ofSupply(23000)).isEqualTo(new PriceQuote(23000, 2300, 25300));
    }

    @Test
    void 선택_수가_1에서_3_밖이면_거절한다() {
        assertThatThrownBy(() -> PricingTier.tieredQuote(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingTier.tieredQuote(4)).isInstanceOf(IllegalArgumentException.class);
    }

    /** VAT 를 공급가/10 으로 다시 구하는 길이 PriceQuote 밖에 남아 있지 않다. */
    @Test
    void noRemainingCallerOfLegacyVatDerivation() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("PriceQuote.java") && !p.endsWith("PricingTier.java"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p);
                            return s.contains("PricingTier.vat(") || s.contains("PricingTier.total(");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).map(Path::toString).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
