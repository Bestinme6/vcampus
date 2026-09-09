package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.protocol.ResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StudentData {
    private StudentData() {}

    public static Profile profile(ResponseMessage response) {
        try { return profile(response.data(), "", true); }
        catch (RuntimeException error) { throw malformed("服务器返回的学籍数据格式不正确", error); }
    }

    public static Page page(ResponseMessage response) {
        try {
            Map<String,String> data=response.data();
            int count=integer(data,"count");
            List<Summary> rows=new ArrayList<>();
            for(int index=0;index<count;index++) {
                String p="row."+index+".";
                rows.add(new Summary(number(data,p+"id"),required(data,p+"studentNumber"),required(data,p+"fullName"),
                        Gender.valueOf(required(data,p+"gender")),required(data,p+"departmentName"),
                        required(data,p+"majorName"),required(data,p+"className"),integer(data,p+"enrollmentYear"),
                        StudentStatus.valueOf(required(data,p+"status")),text(data,p+"phone"),text(data,p+"email")));
            }
            return new Page(rows,integer(data,"page"),integer(data,"pageSize"),integer(data,"total"));
        } catch(RuntimeException error) { throw malformed("服务器返回的学籍列表格式不正确",error); }
    }

    public static List<History> history(ResponseMessage response) {
        try {
            Map<String,String> data=response.data(); List<History> rows=new ArrayList<>();
            for(int index=0;index<integer(data,"count");index++) {
                String p="row."+index+".";
                rows.add(new History(text(data,p+"oldStatus"),StudentStatus.valueOf(required(data,p+"newStatus")),
                        text(data,p+"reason"),text(data,p+"operator"),required(data,p+"changedAt")));
            }
            return List.copyOf(rows);
        } catch(RuntimeException error) { throw malformed("服务器返回的学籍状态历史格式不正确",error); }
    }

    public static References references(ResponseMessage response) {
        try {
            Map<String,String> data=response.data();
            return new References(refs(data,"department"),refs(data,"major"),refs(data,"class"));
        } catch(RuntimeException error) { throw malformed("服务器返回的学籍基础数据格式不正确",error); }
    }

    private static Profile profile(Map<String,String> data,String p,boolean detail) {
        return new Profile(number(data,p+"id"),required(data,p+"studentNumber"),required(data,p+"fullName"),
                Gender.valueOf(required(data,p+"gender")),text(data,p+"birthDate"),number(data,p+"departmentId"),
                required(data,p+"departmentName"),number(data,p+"majorId"),required(data,p+"majorName"),
                number(data,p+"classId"),required(data,p+"className"),integer(data,p+"enrollmentYear"),
                StudentStatus.valueOf(required(data,p+"status")),text(data,p+"phone"),text(data,p+"email"),text(data,p+"address"));
    }

    private static List<Reference> refs(Map<String,String> data,String type) {
        List<Reference> rows=new ArrayList<>();
        for(int i=0;i<integer(data,type+".count");i++) {
            String p=type+"."+i+".";
            rows.add(new Reference(number(data,p+"id"),number(data,p+"parentId"),required(data,p+"code"),required(data,p+"name"),integer(data,p+"year")));
        }
        return List.copyOf(rows);
    }

    private static IllegalArgumentException malformed(String message,RuntimeException error) {
        return error instanceof IllegalArgumentException && message.equals(error.getMessage())
                ? (IllegalArgumentException)error : new IllegalArgumentException(message,error);
    }
    private static String required(Map<String,String> data,String key) { String value=data.get(key); if(value==null)throw new IllegalArgumentException(); return value; }
    private static String text(Map<String,String> data,String key) { return data.getOrDefault(key,""); }
    private static long number(Map<String,String> data,String key) { return Long.parseLong(required(data,key)); }
    private static int integer(Map<String,String> data,String key) { return Integer.parseInt(required(data,key)); }

    public record Profile(long id,String studentNumber,String fullName,Gender gender,String birthDate,
                          long departmentId,String departmentName,long majorId,String majorName,long classId,
                          String className,int enrollmentYear,StudentStatus status,String phone,String email,String address) {}
    public record Summary(long id,String studentNumber,String fullName,Gender gender,String departmentName,
                          String majorName,String className,int enrollmentYear,StudentStatus status,String phone,String email) {}
    public record Page(List<Summary> rows,int page,int pageSize,int total) { public Page { rows=List.copyOf(rows); } }
    public record History(String oldStatus,StudentStatus newStatus,String reason,String operator,String changedAt) {}
    public record Reference(long id,long parentId,String code,String name,int year) { @Override public String toString(){return code+" · "+name;} }
    public record References(List<Reference> departments,List<Reference> majors,List<Reference> classes) {
        public References { departments=List.copyOf(departments); majors=List.copyOf(majors); classes=List.copyOf(classes); }
    }
}
