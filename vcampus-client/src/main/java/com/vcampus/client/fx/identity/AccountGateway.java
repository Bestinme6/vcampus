package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface AccountGateway {
    AccountData.Page search(String keyword,UserRole identity,Boolean enabled,int page)throws IOException;
    AccountData.References references()throws IOException;
    void create(Map<String,String> values)throws IOException;
    void updateRoles(long userId,Set<UserRole> roles)throws IOException;
    void setEnabled(long userId,boolean enabled)throws IOException;
    void resetPassword(long userId,char[] password)throws IOException;
}
