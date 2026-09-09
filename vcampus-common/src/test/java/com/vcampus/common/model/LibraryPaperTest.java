package com.vcampus.common.model;

import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LibraryPaperTest {
    @Test void unicodeMultilineMetadataSurvivesBinaryProtocol() throws Exception {
        var paper=new LibraryPaper(1,"校园：研究","甲、乙","摘要\n第二行","检索;PDF","2026","会议","10.1234/example","https://example.org/paper","作者授权",true,"研究.pdf",20,"abc");
        var bytes=new ByteArrayOutputStream();
        MessageCodec.writeResponse(new DataOutputStream(bytes),ResponseMessage.success("r","ok",Map.of("row",paper.encode())));
        var response=MessageCodec.readResponse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(paper,LibraryPaper.decode(response.data().get("row")));
        assertTrue(paper.hasPdf());
    }
    @Test void malformedRowIsRejected() {
        assertThrows(IllegalArgumentException.class,()->LibraryPaper.decode(RowCodec.encode("1","title")));
    }
    @Test void paperActionsStayInsideLibraryNamespace(){
        assertTrue(java.util.List.of(Actions.LIBRARY_PAPER_SEARCH,Actions.LIBRARY_PAPER_GET,Actions.LIBRARY_PAPER_SAVE,
                Actions.LIBRARY_PAPER_SET_ENABLED,Actions.LIBRARY_PAPER_UPLOAD_START,Actions.LIBRARY_PAPER_UPLOAD_CHUNK,
                Actions.LIBRARY_PAPER_UPLOAD_COMPLETE,Actions.LIBRARY_PAPER_UPLOAD_CANCEL,Actions.LIBRARY_PAPER_READ)
                .stream().allMatch(action->action.startsWith("library.paper.")));
    }
}
