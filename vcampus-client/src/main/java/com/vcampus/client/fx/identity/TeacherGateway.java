package com.vcampus.client.fx.identity;

import java.io.IOException;

/**
 * 教师个人资料界面的远程操作边界。
 *
 * @since 1.0
 */
public interface TeacherGateway {
    TeacherData self() throws IOException;
    void updateContact(String phone,String email) throws IOException;
}
