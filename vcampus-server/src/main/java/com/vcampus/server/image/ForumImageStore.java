package com.vcampus.server.image;

public interface ForumImageStore {
    ShopImageStore.UploadTicket start(long ownerId,String mimeType,long bytes);
    void append(long ownerId,String uploadId,int index,byte[] bytes);
    Completed complete(long ownerId,String uploadId);
    ShopImageStore.ImageDescriptor describe(String storageKey);
    ShopImageStore.ImageRange readRange(String storageKey,long offset,int maxBytes);
    void cleanup(java.util.Set<String> referencedKeys,java.time.Instant now);
    record Completed(String uploadId,String storageKey,String thumbnailStorageKey,String mimeType,
                     long byteSize,String sha256,int width,int height) { }
}
