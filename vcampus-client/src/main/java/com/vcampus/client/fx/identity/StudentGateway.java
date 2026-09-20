package com.vcampus.client.fx.identity;

import com.vcampus.common.model.StudentStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 学生资料和学籍管理界面的远程操作边界。
 *
 * @since 1.0
 */
public interface StudentGateway {
    StudentData.Profile self() throws IOException;
    StudentData.Page search(String keyword,StudentStatus status,int page) throws IOException;
    StudentData.Profile get(long studentId) throws IOException;
    StudentData.References references() throws IOException;
    List<StudentData.History> history(Long studentId) throws IOException;
    void updateProfile(Map<String,String> values) throws IOException;
    void updateContact(long studentId,String phone,String email,String address) throws IOException;
    void updateOwnContact(String phone,String email,String address) throws IOException;
    void changeStatus(long studentId,StudentStatus status,String reason) throws IOException;
}
