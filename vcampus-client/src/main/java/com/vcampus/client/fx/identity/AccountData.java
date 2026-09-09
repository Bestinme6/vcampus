package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AccountData {
    private AccountData() {}

    public static Page page(ResponseMessage response) {
        try {
            Map<String,String> data=response.data(); List<Account> rows=new ArrayList<>();
            for(int i=0;i<integer(data,"count");i++) {
                List<String> fields=RowCodec.decode(required(data,"row."+i));
                if(fields.size()!=8)throw new IllegalArgumentException();
                rows.add(new Account(Long.parseLong(fields.get(0)),fields.get(1),fields.get(2),UserRole.valueOf(fields.get(3)),
                        roles(fields.get(4)),bool(fields.get(5)),bool(fields.get(6)),fields.get(7)));
            }
            return new Page(rows,integer(data,"page"),integer(data,"pageSize"),integer(data,"total"));
        } catch(RuntimeException error) { throw new IllegalArgumentException("服务器返回的账号数据格式不正确",error); }
    }

    public static References references(ResponseMessage response) {
        try {
            Map<String,String> data=response.data();
            return new References(refs(data,"department"),refs(data,"major"),refs(data,"class"));
        } catch(RuntimeException error) { throw new IllegalArgumentException("服务器返回的账号基础数据格式不正确",error); }
    }
    private static List<Reference> refs(Map<String,String> data,String type) {
        List<Reference> rows=new ArrayList<>();
        for(int i=0;i<integer(data,type+".count");i++) {
            String p=type+"."+i+".";
            rows.add(new Reference(Long.parseLong(required(data,p+"id")),Long.parseLong(required(data,p+"parentId")),
                    required(data,p+"code"),required(data,p+"name"),Integer.parseInt(required(data,p+"year"))));
        }
        return List.copyOf(rows);
    }
    private static Set<UserRole> roles(String encoded) {
        if(encoded==null||encoded.isBlank())return Set.of(); Set<UserRole> values=new LinkedHashSet<>();
        for(String value:encoded.split(","))values.add(UserRole.valueOf(value.trim())); return Set.copyOf(values);
    }
    private static boolean bool(String value) { if(!"true".equalsIgnoreCase(value)&&!"false".equalsIgnoreCase(value))throw new IllegalArgumentException(); return Boolean.parseBoolean(value); }
    private static int integer(Map<String,String> data,String key) { return Integer.parseInt(required(data,key)); }
    private static String required(Map<String,String> data,String key) { String value=data.get(key); if(value==null)throw new IllegalArgumentException(); return value; }

    public record Account(long userId,String username,String displayName,UserRole baseIdentity,
                          Set<UserRole> administrativeRoles,boolean enabled,boolean forcePasswordChange,String lastLoginAt) {
        public Account { administrativeRoles=Set.copyOf(administrativeRoles); }
    }
    public record Page(List<Account> rows,int page,int pageSize,int total) { public Page { rows=List.copyOf(rows); } }
    public record Reference(long id,long parentId,String code,String name,int year) { @Override public String toString(){return code+" · "+name;} }
    public record References(List<Reference> departments,List<Reference> majors,List<Reference> classes) {
        public References { departments=List.copyOf(departments); majors=List.copyOf(majors); classes=List.copyOf(classes); }
    }
}
