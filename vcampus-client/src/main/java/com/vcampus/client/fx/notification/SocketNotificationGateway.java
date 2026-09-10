package com.vcampus.client.fx.notification;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.NotificationViewData;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.util.Objects;

final class SocketNotificationGateway implements NotificationGateway {
    private final VCampusClient client;
    private final String token;

    SocketNotificationGateway(VCampusClient client,String token){
        this.client=Objects.requireNonNull(client,"client");
        this.token=Objects.requireNonNull(token,"token");
    }

    @Override public NotificationPage search(String keyword,NotificationSource source,Boolean read,int page)throws IOException{
        return NotificationPage.parse(client.searchNotifications(token,keyword,source,read,page));
    }
    @Override public NotificationDetail detail(long notificationId)throws IOException{
        return NotificationDetail.parse(client.getNotification(token,notificationId));
    }
    @Override public int unreadCount()throws IOException{
        return NotificationViewData.unreadCount(client.unreadNotificationCount(token));
    }
    @Override public void markRead(long notificationId)throws IOException{require(client.markNotificationRead(token,notificationId));}
    @Override public void markUnread(long notificationId)throws IOException{require(client.markNotificationUnread(token,notificationId));}
    @Override public void markAllRead()throws IOException{require(client.markAllNotificationsRead(token));}
    @Override public void delete(long notificationId)throws IOException{require(client.deleteNotification(token,notificationId));}

    private static void require(ResponseMessage response){
        if(!response.success())throw new IllegalStateException(response.message());
    }
}
