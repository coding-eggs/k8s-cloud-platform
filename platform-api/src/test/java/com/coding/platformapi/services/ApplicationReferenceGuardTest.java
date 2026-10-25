package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void replaceWritesOneRowPerReferenceAndSkipsEmpty() {
        guard.replace("app_1", List.of(
                new ApplicationReferenceGuard.Ref("db", "mwi_1"),
                new ApplicationReferenceGuard.Ref("cache", "  ")));

        verify(refs).deleteByApp("app_1");
        ArgumentCaptor<PlatformApplicationReference> cap =
                ArgumentCaptor.forClass(PlatformApplicationReference.class);
        verify(refs).insert(cap.capture());
        assertThat(cap.getValue().getNodeId()).isEqualTo("db");
        assertThat(cap.getValue().getRefId()).isEqualTo("mwi_1");
        assertThat(cap.getValue().getRefType()).isEqualTo("MIDDLEWARE_INSTANCE");
    }

    @Test
    void replaceWithNoRefsOnlyClears() {
        guard.replace("app_1", List.of());

        verify(refs).deleteByApp("app_1");
        verify(refs, never()).insert(any());
    }
}
