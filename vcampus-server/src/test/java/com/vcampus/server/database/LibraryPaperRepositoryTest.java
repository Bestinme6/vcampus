package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LibraryPaperRepositoryTest {
    private ConnectionFactory connections;
    private LibraryPaperRepository repository;

    @BeforeEach void setup() throws Exception {
        connections=new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:library-paper-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        try(var connection=connections.openConnection();var sql=connection.createStatement()){
            sql.execute("CREATE ALIAS FIND_IN_SET FOR 'com.vcampus.server.database.LibraryPaperRepositoryTest.findInSet'");
            sql.execute("""
                    CREATE TABLE library_papers (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        title VARCHAR(300) NOT NULL,
                        authors VARCHAR(300) NOT NULL,
                        abstract_text TEXT NOT NULL,
                        keywords VARCHAR(500) NOT NULL DEFAULT '',
                        publish_year VARCHAR(4) NOT NULL DEFAULT '',
                        venue VARCHAR(300) NOT NULL DEFAULT '',
                        doi VARCHAR(255),
                        source_url VARCHAR(2000) NOT NULL DEFAULT '',
                        rights_note VARCHAR(2000) NOT NULL DEFAULT '',
                        enabled BOOLEAN NOT NULL DEFAULT FALSE,
                        filename VARCHAR(180) NOT NULL DEFAULT '',
                        byte_size BIGINT NOT NULL DEFAULT 0,
                        sha256 CHAR(64),
                        created_by BIGINT NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE TABLE audit_logs (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        user_id BIGINT NOT NULL,
                        action_code VARCHAR(100) NOT NULL,
                        result_code VARCHAR(32) NOT NULL,
                        client_address VARCHAR(255) NOT NULL
                    )
                    """);
            sql.execute("INSERT INTO library_papers (title,authors,abstract_text,rights_note,created_by) VALUES ('我的草稿','甲','','授权',1),('他人草稿','乙','','授权',2)");
        }
        repository=new LibraryPaperRepository(connections);
    }

    public static int findInSet(String needle,String values){
        if(needle==null||values==null)return 0;
        String[] parts=values.split(",",-1);for(int index=0;index<parts.length;index++)if(needle.equals(parts[index]))return index+1;return 0;
    }

    @Test void contributorSearchIncludesOnlyTheirOwnDraft() throws Exception {
        var mine=repository.search("","","",1,8,false,1);
        assertEquals(1,mine.total());
        assertEquals("我的草稿",mine.rows().getFirst().title());
        assertEquals(1,mine.rows().getFirst().createdBy());
        assertEquals(2,repository.search("","","",1,8,true,99).total());
    }

    @Test void firstAttachmentPublishesButReplacementDoesNotReopenModeratedPaper() throws Exception {
        String first="a".repeat(64),replacement="b".repeat(64);
        repository.attach(1,"first.pdf",20,first,"",1,false,true);
        assertTrue(repository.find(1).orElseThrow().enabled());

        repository.setEnabled(1,false,9);
        repository.attach(1,"replacement.pdf",30,replacement,first,9,true,true);
        var moderated=repository.find(1).orElseThrow();
        assertFalse(moderated.enabled());
        assertEquals(replacement,moderated.sha256());
        assertEquals(0,repository.search("","","",1,8,false,1).total());
    }

    @Test void metadataOnlyDraftCannotBePublished() {
        assertThrows(IllegalArgumentException.class,()->repository.setEnabled(2,true,9));
    }

    @Test void transactionRejectsOwnerMutationAfterAdminModeration() throws Exception {
        String first="c".repeat(64),replacement="d".repeat(64);
        repository.attach(1,"first.pdf",20,first,"",1,false,true);
        repository.setEnabled(1,false,9);
        var metadata=new LibraryPaperStore.Metadata("绕过下架","甲","","","2026","","","","授权");

        assertThrows(IllegalArgumentException.class,()->repository.save(1,metadata,1,false));
        assertThrows(IllegalArgumentException.class,()->repository.attach(1,"replacement.pdf",30,replacement,first,1,false,true));
        assertEquals("我的草稿",repository.find(1).orElseThrow().title());
        assertEquals(first,repository.find(1).orElseThrow().sha256());
    }
}
