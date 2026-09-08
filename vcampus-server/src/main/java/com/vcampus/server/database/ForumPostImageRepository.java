package com.vcampus.server.database;

import java.sql.*;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;

public final class ForumPostImageRepository {
    private final ConnectionFactory connections;
    public ForumPostImageRepository(ConnectionFactory connections){this.connections=connections;}
    public Optional<ImageFile> find(long imageId,boolean administrator)throws SQLException{
        String visibility=administrator?"":" AND p.status='NORMAL' AND s.enabled=TRUE";
        String sql="SELECT i.id,i.storage_key,i.thumbnail_storage_key,i.mime_type,i.byte_size,i.sha256 FROM forum_post_images i JOIN forum_posts p ON p.id=i.post_id JOIN forum_sections s ON s.id=p.section_id WHERE i.id=?"+visibility;
        try(Connection c=connections.openConnection();PreparedStatement statement=c.prepareStatement(sql)){statement.setLong(1,imageId);try(ResultSet r=statement.executeQuery()){return r.next()?Optional.of(new ImageFile(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6))):Optional.empty();}}
    }
    public Set<String> storageKeys()throws SQLException{Set<String> keys=new LinkedHashSet<>();try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement("SELECT storage_key,thumbnail_storage_key FROM forum_post_images");ResultSet r=s.executeQuery()){while(r.next()){keys.add(r.getString(1));keys.add(r.getString(2));}}return keys;}
    public record ImageFile(long id,String storageKey,String thumbnailStorageKey,String mimeType,long byteSize,String sha256){}
}
