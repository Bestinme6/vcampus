package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemActionsTest {
    @Test
    void systemActionsUseStablePrefix() {
        assertEquals("system.config.get", Actions.SYSTEM_CONFIG_GET);
        assertEquals("system.config.updateTerm", Actions.SYSTEM_CONFIG_UPDATE_TERM);
        assertEquals("system.config.updatePeriods", Actions.SYSTEM_CONFIG_UPDATE_PERIODS);
        assertEquals("system.audit.search", Actions.SYSTEM_AUDIT_SEARCH);
    }
}
