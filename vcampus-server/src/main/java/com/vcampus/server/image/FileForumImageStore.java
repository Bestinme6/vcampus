package com.vcampus.server.image;

import com.vcampus.server.config.ForumImageConfig;

public final class FileForumImageStore implements ForumImageStore {
    private static final long FORUM_UPLOAD_MARKER=1;
    private final ShopImageStore delegate;
    public FileForumImageStore(ForumImageConfig config){delegate=new FileShopImageStore(config.asStoreConfig());}
    @Override public ShopImageStore.UploadTicket start(long ownerId,String mimeType,long bytes){return delegate.startUpload(ownerId,FORUM_UPLOAD_MARKER,mimeType,bytes);}
    @Override public void append(long ownerId,String uploadId,int index,byte[] bytes){delegate.appendChunk(ownerId,uploadId,index,bytes);}
    @Override public Completed complete(long ownerId,String uploadId){var uploaded=delegate.completeUpload(ownerId,uploadId);var stored=delegate.finalizeUpload(ownerId,uploadId);return new Completed(uploadId,stored.storageKey(),stored.thumbnailStorageKey(),uploaded.mimeType(),uploaded.normalizedBytes().length,uploaded.sha256(),uploaded.width(),uploaded.height());}
    @Override public ShopImageStore.ImageDescriptor describe(String key){return delegate.describe(key);}
    @Override public ShopImageStore.ImageRange readRange(String key,long offset,int maxBytes){return delegate.readRange(key,offset,maxBytes);}
    @Override public void cleanup(java.util.Set<String> keys,java.time.Instant now){delegate.cleanup(keys,now);}
}
