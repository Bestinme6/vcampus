package com.vcampus.client.fx.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecondhandImageDraftTest {
    @Test
    void limitsGalleryToFiveAndKeepsExactlyOneCover() {
        SecondhandImageDraft draft = new SecondhandImageDraft(List.of());
        for (int index = 0; index < 5; index++) {
            draft.addUpload(Path.of("photo-" + index + ".png"));
        }
        assertEquals(5, draft.images().size());
        assertEquals(1, draft.images().stream().filter(SecondhandImageDraft.DraftImage::cover).count());
        assertThrows(IllegalStateException.class,
                () -> draft.addUpload(Path.of("photo-5.png")));
    }

    @Test
    void supportsCoverRemovalReorderingAndRetryingOnlyMissingUploads() {
        SecondhandImageDraft draft = new SecondhandImageDraft(List.of());
        var first = draft.addUpload(Path.of("first.png"));
        var second = draft.addUpload(Path.of("second.png"));
        draft.setCover(second.key());
        draft.move(1, 0);
        draft.markUploaded(second.key(), "upload-2");

        assertEquals(second.key(), draft.images().getFirst().key());
        assertTrue(draft.pendingUploads().stream().anyMatch(image -> image.key() == first.key()));
        assertTrue(draft.pendingUploads().stream().noneMatch(image -> image.key() == second.key()));

        draft.resetUploads();
        assertEquals(2, draft.pendingUploads().size());

        draft.remove(second.key());
        assertTrue(draft.images().getFirst().cover());
    }
}
