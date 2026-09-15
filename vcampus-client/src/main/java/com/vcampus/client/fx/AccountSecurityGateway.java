package com.vcampus.client.fx;

import java.io.IOException;

interface AccountSecurityGateway {
    AccountSecurityData.Summary overview() throws IOException;
    void changePassword(char[] currentPassword, char[] newPassword) throws IOException;
}
