package com.vcampus.server.service;

import com.vcampus.common.model.ForumAccessPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.ForumPostImageRepository;
import com.vcampus.server.database.ForumStore.PendingImage;
import com.vcampus.server.image.ForumImageStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.*;
import java.util.concurrent.*;

/**
 * 论坛图片分块上传、发布绑定和安全读取服务。
 *
 * <p>服务验证会话与内容归属，实际文件规范化和路径边界检查由图片存储组件完成。</p>
 *
 * @since 1.0
 */
public final class ForumImageService {
    private static final int READ_BYTES=192*1024;
    private final ForumImageStore images;private final ForumPostImageRepository repository;private final SessionManager sessions;
    private final Map<String,OwnedImage> completed=new ConcurrentHashMap<>();
    public ForumImageService(ForumImageStore images,ForumPostImageRepository repository,SessionManager sessions){this.images=images;this.repository=repository;this.sessions=sessions;}
    public ResponseMessage start(RequestMessage request){return handle(request,session->{var ticket=images.start(session.userId(),required(request,"mimeType"),positiveLong(request.parameters().get("byteSize")));return ResponseMessage.success(request.requestId(),"图片上传已开始",Map.of("uploadId",ticket.uploadId(),"chunkBytes",Integer.toString(ticket.chunkBytes()),"expectedBytes",Long.toString(ticket.expectedBytes())));});}
    public ResponseMessage chunk(RequestMessage request){return handle(request,session->{String id=required(request,"uploadId");int index=nonNegativeInt(request.parameters().get("chunkIndex"));byte[] bytes;try{bytes=Base64.getDecoder().decode(required(request,"contentBase64"));}catch(IllegalArgumentException e){throw new IllegalArgumentException("图片分块编码无效");}images.append(session.userId(),id,index,bytes);return ResponseMessage.success(request.requestId(),"图片分块已接收",Map.of());});}
    public ResponseMessage complete(RequestMessage request){return handle(request,session->{var image=images.complete(session.userId(),required(request,"uploadId"));completed.put(image.uploadId(),new OwnedImage(session.userId(),image,Instant.now()));return ResponseMessage.success(request.requestId(),"图片上传完成",Map.of("uploadId",image.uploadId(),"mimeType",image.mimeType(),"byteSize",Long.toString(image.byteSize()),"sha256",image.sha256(),"width",Integer.toString(image.width()),"height",Integer.toString(image.height())));});}
    public ResponseMessage read(RequestMessage request){Optional<UserSession> user=user(request);if(user.isEmpty())return expired(request);try{long id=positiveLong(request.parameters().get("imageId"));String variant=required(request,"variant");int index=nonNegativeInt(request.parameters().get("chunkIndex"));if(!variant.equals("THUMBNAIL")&&!variant.equals("DETAIL"))throw new IllegalArgumentException("图片版本无效");var row=repository.find(id,ForumAccessPolicy.canManage(user.get().roles())).orElseThrow(()->new IllegalArgumentException("图片不存在"));String key=variant.equals("THUMBNAIL")?row.thumbnailStorageKey():row.storageKey();var descriptor=images.describe(key);long offset=(long)index*READ_BYTES;if(offset>=descriptor.totalBytes())throw new IllegalArgumentException("图片分块序号无效");var range=images.readRange(key,offset,READ_BYTES);int chunks=Math.toIntExact(Math.ceilDiv(descriptor.totalBytes(),READ_BYTES));return ResponseMessage.success(request.requestId(),"图片读取成功",Map.of("imageId",Long.toString(id),"variant",variant,"mimeType",row.mimeType(),"sha256",descriptor.sha256(),"totalBytes",Long.toString(descriptor.totalBytes()),"totalChunks",Integer.toString(chunks),"chunkIndex",Integer.toString(index),"contentBase64",Base64.getEncoder().encodeToString(range.content())));}catch(IllegalArgumentException e){return ResponseMessage.failure(request.requestId(),e.getMessage());}catch(SQLException e){return ResponseMessage.failure(request.requestId(),"数据库操作失败，请稍后重试");}}
    public List<PendingImage> claim(long owner,List<String> uploadIds){pruneCompleted(Instant.now());if(uploadIds.size()>3||new LinkedHashSet<>(uploadIds).size()!=uploadIds.size())throw new IllegalArgumentException("每篇帖子最多三张且不能重复");List<PendingImage> result=new ArrayList<>();for(String id:uploadIds){OwnedImage value=completed.get(id);if(value==null||value.owner()!=owner)throw new IllegalArgumentException("图片上传尚未完成或已过期");var image=value.image();result.add(new PendingImage(image.storageKey(),image.thumbnailStorageKey(),image.mimeType(),image.byteSize(),image.sha256(),image.width(),image.height()));}return result;}
    public void consume(long owner,List<String> ids){for(String id:ids)completed.computeIfPresent(id,(key,value)->value.owner()==owner?null:value);}
    private ResponseMessage handle(RequestMessage request,Work work){Optional<UserSession> user=user(request);if(user.isEmpty())return expired(request);try{return work.run(user.get());}catch(IllegalArgumentException|com.vcampus.server.image.ShopImageException e){return ResponseMessage.failure(request.requestId(),e.getMessage());}}
    private Optional<UserSession> user(RequestMessage request){return sessions.find(request.parameters().get("sessionToken")).filter(s->ForumAccessPolicy.canUse(s.roles()));}
    private ResponseMessage expired(RequestMessage request){return ResponseMessage.failure(request.requestId(),"登录已过期，请重新登录");}
    private String required(RequestMessage r,String key){String v=r.parameters().get(key);if(v==null||v.isBlank())throw new IllegalArgumentException("上传参数无效");return v;}
    private long positiveLong(String v){long n=Long.parseLong(v==null?"":v);if(n<1)throw new IllegalArgumentException("编号无效");return n;}
    private int nonNegativeInt(String v){int n=Integer.parseInt(v==null?"":v);if(n<0)throw new IllegalArgumentException("图片分块序号无效");return n;}
    public void cleanup(Instant now){pruneCompleted(now);try{images.cleanup(repository.storageKeys(),now);}catch(SQLException|RuntimeException e){System.err.println("Forum image cleanup failed: "+e.getMessage());}}
    public ScheduledExecutorService startCleanupScheduler(Duration interval){cleanup(Instant.now());var executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"vcampus-forum-image-cleanup");t.setDaemon(true);return t;});executor.scheduleWithFixedDelay(()->cleanup(Instant.now()),interval.toMillis(),interval.toMillis(),TimeUnit.MILLISECONDS);return executor;}
    private void pruneCompleted(Instant now){completed.entrySet().removeIf(e->!e.getValue().createdAt().plus(Duration.ofMinutes(30)).isAfter(now));}
    private record OwnedImage(long owner,ForumImageStore.Completed image,Instant createdAt){}
    @FunctionalInterface private interface Work{ResponseMessage run(UserSession session);}
}
