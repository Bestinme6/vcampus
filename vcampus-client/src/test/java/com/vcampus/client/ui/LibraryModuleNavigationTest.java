package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryModuleNavigationTest {
    @Test
    void myLoansDeepLinkFindsTheReaderTab() {
        assertEquals(1, LibraryModuleNavigation.openMyLoansIndex(
                List.of("图书检索", "我的借阅", "书目馆藏")));
    }

    @Test
    void managementOnlyLibraryHasNoMyLoansTarget() {
        assertEquals(-1, LibraryModuleNavigation.openMyLoansIndex(
                List.of("书目馆藏", "借还办理", "借阅查询")));
    }
}
