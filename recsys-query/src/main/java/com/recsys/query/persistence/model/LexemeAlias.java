package com.recsys.query.persistence.model;

/** PostgreSQL 词形映射结果，限定单 lexeme 以避免把复合词任意截断。 */
public record LexemeAlias(String raw, String lexeme) {
}
