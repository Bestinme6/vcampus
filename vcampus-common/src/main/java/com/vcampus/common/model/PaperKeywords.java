package com.vcampus.common.model;

import java.util.*;

/** Normalizes paper keyword lists for storage, display and exact category filters. */
public final class PaperKeywords {
    private static final int MAX_COUNT=12;
    private static final int MAX_LENGTH=40;
    private PaperKeywords() {}

    public static String normalize(String value) {
        var keywords=new LinkedHashSet<String>();
        for(String raw:Objects.requireNonNullElse(value,"").split("[,，;；、\\r\\n]+")) {
            String keyword=raw.strip();
            if(keyword.isEmpty())continue;
            if(keyword.length()>MAX_LENGTH)throw new IllegalArgumentException("每个关键词不能超过 40 个字符");
            keywords.add(keyword);
            if(keywords.size()>MAX_COUNT)throw new IllegalArgumentException("每篇论文最多填写 12 个关键词");
        }
        String normalized=String.join("；",keywords);
        if(normalized.length()>500)throw new IllegalArgumentException("关键词总长度不能超过 500 个字符");
        return normalized;
    }

    public static List<String> parse(String value) {
        String normalized=normalize(value);
        return normalized.isEmpty()?List.of():List.of(normalized.split("；"));
    }
}
