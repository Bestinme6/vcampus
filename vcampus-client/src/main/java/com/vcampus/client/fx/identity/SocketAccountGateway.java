package com.vcampus.client.fx.identity;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SocketAccountGateway implements AccountGateway {
    private final VCampusClient client; private final String token;
    public SocketAccountGateway(VCampusClient client,String token){this.client=Objects.requireNonNull(client);this.token=Objects.requireNonNull(token);}
    @Override public AccountData.Page search(String keyword,UserRole identity,Boolean enabled,int page)throws IOException{return AccountData.page(ok(client.searchAccounts(token,text(keyword),identity==null?"":identity.name(),enabled==null?"":enabled.toString(),page)));}
    @Override public AccountData.References references()throws IOException{return AccountData.references(ok(client.accountReferenceData(token)));}
    @Override public void create(Map<String,String> values)throws IOException{ok(client.createAccount(token,Map.copyOf(values)));}
    @Override public void updateRoles(long userId,Set<UserRole> roles)throws IOException{ok(client.updateAccountRoles(token,userId,Set.copyOf(roles)));}
    @Override public void setEnabled(long userId,boolean enabled)throws IOException{ok(client.setAccountEnabled(token,userId,enabled));}
    @Override public void resetPassword(long userId,char[] password)throws IOException{char[] copy=Arrays.copyOf(password,password.length);try{ok(client.resetAccountPassword(token,userId,copy));}finally{Arrays.fill(copy,'\0');}}
    private static String text(String value){return value==null?"":value.trim();}
    private static ResponseMessage ok(ResponseMessage response){if(!response.success())throw new IllegalStateException(response.message());return response;}
}
