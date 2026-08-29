package com.vcampus.client.ui;

import com.vcampus.common.model.LibraryAccessPolicy;
import com.vcampus.common.model.UserRole;
import java.util.*;

public final class LibraryTabPolicy {
    private LibraryTabPolicy() {}
    public static List<String> titles(Set<UserRole> roles){Objects.requireNonNull(roles);List<String> result=new ArrayList<>();if(LibraryAccessPolicy.canBorrow(roles)){result.add("图书检索");result.add("我的借阅");}if(LibraryAccessPolicy.canManage(roles)){result.add("书目馆藏");result.add("借还办理");result.add("借阅查询");}return List.copyOf(result);}
}
