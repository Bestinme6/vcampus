package com.vcampus.common.model;

import com.vcampus.common.protocol.RowCodec;
import java.util.Objects;

/** Public paper metadata; server filesystem paths never cross this boundary. */
public record LibraryPaper(long id, long createdBy, String title, String authors, String abstractText,
        String keywords, String year, String venue, String doi, String sourceUrl,
        String rights, boolean enabled, String filename, long byteSize, String sha256) {
    public LibraryPaper {
        if(id<1 || createdBy<1 || byteSize<0) throw new IllegalArgumentException("论文记录无效");
        Objects.requireNonNull(title); Objects.requireNonNull(authors); Objects.requireNonNull(abstractText);
        Objects.requireNonNull(keywords); Objects.requireNonNull(year); Objects.requireNonNull(venue);
        Objects.requireNonNull(doi); Objects.requireNonNull(sourceUrl); Objects.requireNonNull(rights);
        Objects.requireNonNull(filename); Objects.requireNonNull(sha256);
    }
    public boolean hasPdf() { return byteSize>0 && !sha256.isBlank(); }
    public String encode() {
        return RowCodec.encode(Long.toString(id),Long.toString(createdBy),title,authors,abstractText,keywords,year,venue,
                doi,sourceUrl,rights,Boolean.toString(enabled),filename,Long.toString(byteSize),sha256);
    }
    public static LibraryPaper decode(String row) {
        var f=RowCodec.decode(row);
        if(f.size()!=15 || !java.util.Set.of("true","false").contains(f.get(11)))
            throw new IllegalArgumentException("论文记录格式无效");
        return new LibraryPaper(Long.parseLong(f.get(0)),Long.parseLong(f.get(1)),f.get(2),f.get(3),f.get(4),f.get(5),f.get(6),
                f.get(7),f.get(8),f.get(9),f.get(10),Boolean.parseBoolean(f.get(11)),f.get(12),Long.parseLong(f.get(13)),f.get(14));
    }
}
