package com.vcampus.client.ui;

import java.util.List;
import java.util.Objects;

final class LibraryModuleNavigation {
    private static final String MY_LOANS = "我的借阅";

    private LibraryModuleNavigation() {
    }

    static int openMyLoansIndex(List<String> tabTitles) {
        Objects.requireNonNull(tabTitles, "tabTitles");
        return tabTitles.indexOf(MY_LOANS);
    }
}
