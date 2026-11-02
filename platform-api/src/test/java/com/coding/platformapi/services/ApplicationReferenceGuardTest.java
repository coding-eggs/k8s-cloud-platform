package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationReferenceGuardTest {

    private final PlatformApplicationReferenceMapper refs = mock(PlatformApplicationReferenceMapper.class);
    private final PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
    private final ApplicationReferenceGuard guard = new ApplicationReferenceGuard(refs, apps);

    @Test
    void refusesWhenInstanceIsUsedByAnApplication() {
        PlatformApplicationReference ref = new PlatformApplicationReference();
        ref.setAppId("app_1");
        PlatformApplication app = new PlatformApplication();
        app.setName("order-service");
        when(refs.selectByRef("MIDDLEWARE_INSTANCE", "mwi_1")).thenReturn(List.of(ref));
        when(apps.selectById("app_1")).thenReturn(app);

        assertThatThrownBy(() -> guard.assertNotReferenced("mwi_1"))
                .hasMessageContaining("被应用 order-service 使用");
    }

    @Test
    void allowsDeletingUnreferencedInstance() {
        when(refs.selectByRef(anyString(), anyString())).thenReturn(List.of());

        guard.assertNotReferenced("mwi_2");   // 不抛即通过
        verify(refs).selectByRef("MIDDLEWARE_INSTANCE", "mwi_2");
    }
}
