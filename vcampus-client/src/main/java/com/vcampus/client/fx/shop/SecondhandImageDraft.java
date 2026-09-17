package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SecondhandImageDraft {
    enum Kind { EXISTING, UPLOAD }

    record DraftImage(long key, Kind kind, Long existingImageId, Path file,
                      String uploadId, boolean cover) {
        DraftImage {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.EXISTING && (existingImageId == null || existingImageId < 1 || file != null)
                    || kind == Kind.UPLOAD && (file == null || existingImageId != null)) {
                throw new IllegalArgumentException("图片草稿无效");
            }
            if (file != null) file = file.toAbsolutePath().normalize();
        }

        static DraftImage existing(ImageRef ref) {
            return new DraftImage(ref.id(), Kind.EXISTING, ref.id(), null, null, ref.cover());
        }

        DraftImage withCover(boolean value) {
            return new DraftImage(key, kind, existingImageId, file, uploadId, value);
        }

        DraftImage uploaded(String value) {
            return new DraftImage(key, kind, existingImageId, file, value, cover);
        }
    }

    private final List<DraftImage> images = new ArrayList<>();
    private long nextUploadKey = -1;

    SecondhandImageDraft(List<DraftImage> initial) {
        if (initial.size() > 5) throw new IllegalArgumentException("每件二手物品最多五张图片");
        images.addAll(initial);
        normalizeCover();
    }

    static SecondhandImageDraft from(List<ImageRef> refs) {
        return new SecondhandImageDraft(refs.stream().map(DraftImage::existing).toList());
    }

    List<DraftImage> images() { return List.copyOf(images); }

    List<DraftImage> pendingUploads() {
        return images.stream().filter(image -> image.kind() == Kind.UPLOAD
                && (image.uploadId() == null || image.uploadId().isBlank())).toList();
    }

    DraftImage addUpload(Path file) {
        if (images.size() >= 5) throw new IllegalStateException("每件二手物品最多五张图片");
        DraftImage image = new DraftImage(nextUploadKey--, Kind.UPLOAD, null,
                Objects.requireNonNull(file), null, images.isEmpty());
        images.add(image);
        return image;
    }

    void markUploaded(long key, String uploadId) {
        if (uploadId == null || uploadId.isBlank()) throw new IllegalArgumentException("上传编号无效");
        int index = indexOf(key);
        images.set(index, images.get(index).uploaded(uploadId));
    }

    void resetUploads() {
        for (int index = 0; index < images.size(); index++) {
            DraftImage image = images.get(index);
            if (image.kind() == Kind.UPLOAD && image.uploadId() != null) {
                images.set(index, new DraftImage(image.key(), image.kind(), null,
                        image.file(), null, image.cover()));
            }
        }
    }

    void remove(long key) {
        boolean removedCover = images.remove(indexOf(key)).cover();
        if (removedCover && !images.isEmpty()) setCover(images.getFirst().key());
    }

    void move(int from, int to) {
        if (from < 0 || from >= images.size() || to < 0 || to >= images.size()) {
            throw new IndexOutOfBoundsException("图片顺序无效");
        }
        images.add(to, images.remove(from));
    }

    void setCover(long key) {
        indexOf(key);
        for (int index = 0; index < images.size(); index++) {
            DraftImage image = images.get(index);
            images.set(index, image.withCover(image.key() == key));
        }
    }

    List<SecondhandData.ImagePlanItem> plan() {
        normalizeCover();
        return images.stream().map(image -> {
            if (image.kind() == Kind.UPLOAD && (image.uploadId() == null || image.uploadId().isBlank())) {
                throw new IllegalStateException("仍有图片尚未上传");
            }
            return image.kind() == Kind.EXISTING
                    ? new SecondhandData.ImagePlanItem(image.existingImageId(), null, image.cover())
                    : new SecondhandData.ImagePlanItem(null, image.uploadId(), image.cover());
        }).toList();
    }

    private int indexOf(long key) {
        for (int index = 0; index < images.size(); index++) {
            if (images.get(index).key() == key) return index;
        }
        throw new IllegalArgumentException("图片不存在");
    }

    private void normalizeCover() {
        if (images.isEmpty()) return;
        long key = images.stream().filter(DraftImage::cover).findFirst()
                .orElse(images.getFirst()).key();
        for (int index = 0; index < images.size(); index++) {
            DraftImage image = images.get(index);
            images.set(index, image.withCover(image.key() == key));
        }
    }
}
