package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryTabPolicyTest {
    @Test void mapsRolesToReaderAndManagementTabs(){assertEquals(List.of("图书检索","我的借阅"),LibraryTabPolicy.titles(Set.of(UserRole.STUDENT)));assertEquals(List.of("图书检索","我的借阅","书目馆藏","借还办理","借阅查询"),LibraryTabPolicy.titles(Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN)));assertEquals(List.of("书目馆藏","借还办理","借阅查询"),LibraryTabPolicy.titles(Set.of(UserRole.SUPER_ADMIN)));}
}
