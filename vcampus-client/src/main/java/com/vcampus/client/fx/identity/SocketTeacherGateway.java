package com.vcampus.client.fx.identity;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.util.Objects;

/**
 * 基于 Socket 客户端和当前会话实现的教师资料网关。
 *
 * @since 1.0
 */
public final class SocketTeacherGateway implements TeacherGateway {
    private final VCampusClient client; private final String token;
    public SocketTeacherGateway(VCampusClient client,String token){this.client=Objects.requireNonNull(client);this.token=Objects.requireNonNull(token);}
    @Override public TeacherData self()throws IOException{return TeacherData.from(ok(client.getMyTeacherProfile(token)));}
    @Override public void updateContact(String phone,String email)throws IOException{ok(client.updateTeacherContact(token,text(phone),text(email)));}
    private static String text(String value){return value==null?"":value.trim();}
    private static ResponseMessage ok(ResponseMessage response){if(!response.success())throw new IllegalStateException(response.message());return response;}
}
