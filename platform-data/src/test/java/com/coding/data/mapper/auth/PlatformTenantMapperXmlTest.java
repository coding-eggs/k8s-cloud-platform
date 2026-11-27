package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformTenant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTenantMapperXmlTest {

    @Test
    void selectiveUpdatePersistsSoftDeletionWithoutChangingStatus() throws Exception {
        String resource = "mapper/auth/PlatformTenantMapper.xml";
        Configuration config = new Configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        var update = config.getMappedStatement(PlatformTenantMapper.class.getName() + ".updateByPrimaryKeySelective");
        PlatformTenant tenant = new PlatformTenant();
        tenant.setId("test-tenant");
        tenant.setUpdatedAt(new Date());
        assertThat(update.getBoundSql(tenant).getSql()).doesNotContain("deleted_at");

        tenant.setDeletedAt(tenant.getUpdatedAt());
        assertThat(update.getBoundSql(tenant).getSql()).contains("deleted_at = ?").doesNotContain("`status`");
    }
}
