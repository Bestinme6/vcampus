package com.vcampus.client.fx.identity;

import com.vcampus.common.protocol.ResponseMessage;

import java.util.Map;

public record TeacherData(String teacherNumber,String fullName,String departmentName,
                          String professionalTitle,String phone,String email) {
    public static TeacherData from(ResponseMessage response) {
        try {
            Map<String,String> data=response.data();
            return new TeacherData(required(data,"teacherNumber"),required(data,"fullName"),
                    required(data,"departmentName"),required(data,"professionalTitle"),
                    data.getOrDefault("phone",""),data.getOrDefault("email",""));
        } catch(RuntimeException error) { throw new IllegalArgumentException("服务器返回的教师信息格式不正确",error); }
    }
    private static String required(Map<String,String> data,String key) { String value=data.get(key); if(value==null)throw new IllegalArgumentException(); return value; }
}
