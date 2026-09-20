package com.vcampus.client.fx.identity;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Socket 客户端和当前会话实现的学生资料网关。
 *
 * @since 1.0
 */
public final class SocketStudentGateway implements StudentGateway {
    private final VCampusClient client; private final String token;
    public SocketStudentGateway(VCampusClient client,String token){this.client=Objects.requireNonNull(client);this.token=Objects.requireNonNull(token);}
    @Override public StudentData.Profile self() throws IOException {return StudentData.profile(ok(client.getMyStudentProfile(token)));}
    @Override public StudentData.Page search(String keyword,StudentStatus status,int page)throws IOException{return StudentData.page(ok(client.searchStudents(token,text(keyword),status==null?"":status.name(),page)));}
    @Override public StudentData.Profile get(long studentId)throws IOException{return StudentData.profile(ok(client.getStudent(token,studentId)));}
    @Override public StudentData.References references()throws IOException{return StudentData.references(ok(client.studentReferenceData(token)));}
    @Override public List<StudentData.History> history(Long studentId)throws IOException{return StudentData.history(ok(client.studentStatusHistory(token,studentId)));}
    @Override public void updateProfile(Map<String,String> values)throws IOException{ok(client.updateStudent(token,Map.copyOf(values)));}
    @Override public void updateContact(long studentId,String phone,String email,String address)throws IOException{Map<String,String> values=contact(phone,email,address);values.put("studentId",Long.toString(studentId));ok(client.updateStudentContact(token,values));}
    @Override public void updateOwnContact(String phone,String email,String address)throws IOException{ok(client.updateStudentContact(token,contact(phone,email,address)));}
    @Override public void changeStatus(long studentId,StudentStatus status,String reason)throws IOException{ok(client.changeStudentStatus(token,studentId,Objects.requireNonNull(status).name(),text(reason)));}
    private static LinkedHashMap<String,String> contact(String phone,String email,String address){LinkedHashMap<String,String> values=new LinkedHashMap<>();values.put("phone",text(phone));values.put("email",text(email));values.put("address",text(address));return values;}
    private static String text(String value){return value==null?"":value.trim();}
    private static ResponseMessage ok(ResponseMessage response){if(!response.success())throw new IllegalStateException(response.message());return response;}
}
