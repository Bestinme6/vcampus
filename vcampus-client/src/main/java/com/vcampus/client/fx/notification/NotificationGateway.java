package com.vcampus.client.fx.notification;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.common.model.NotificationSource;

import java.io.IOException;

interface NotificationGateway {
    NotificationPage search(String keyword,NotificationSource source,Boolean read,int page)throws IOException;
    NotificationDetail detail(long notificationId)throws IOException;
    int unreadCount()throws IOException;
    void markRead(long notificationId)throws IOException;
    void markAllRead()throws IOException;
}
