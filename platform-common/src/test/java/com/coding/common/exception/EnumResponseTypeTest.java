package com.coding.common.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EnumResponseTypeTest {

    @Test
    void business_error_codes_are_unique() {
        assertThat(Arrays.stream(EnumResponseType.values())
                .map(EnumResponseType::getCode)
                .filter(c -> c >= 10000 && c < 90000)   // 业务段；4004 等 HTTP 语义复用不在范围
                .toList()).doesNotHaveDuplicates();
    }
}
