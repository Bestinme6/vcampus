package com.vcampus.client.fx.identity;

import java.io.IOException;

public interface TeacherGateway {
    TeacherData self() throws IOException;
    void updateContact(String phone,String email) throws IOException;
}
