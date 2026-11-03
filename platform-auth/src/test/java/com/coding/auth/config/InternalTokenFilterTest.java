package com.coding.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalTokenFilterTest {

    private static MockHttpServletRequest internal(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/internal/clients/list");
        req.setContextPath("/auth");
        req.setServletPath("/internal/clients/list");
        if (token != null) req.addHeader(InternalTokenFilter.HEADER, token);
        return req;
    }

    @Test
    void missingTokenConfigRefusesToStart() {
        assertThatThrownBy(() -> new InternalTokenFilter("  ").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongHeaderGets401() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter("s3cret");
        for (String bad : new String[]{null, "", "s3cre", "s3cret2"}) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            f.doFilter(internal(bad), resp, chain);
            assertThat(resp.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void correctHeaderPassesThrough() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter("s3cret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(internal("s3cret"), resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void nonInternalPathIgnored() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter("s3cret");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/login.html");
        req.setContextPath("/auth");
        req.setServletPath("/login.html");
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
