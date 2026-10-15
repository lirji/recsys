package com.recsys.query.persistence.mapper;

import com.recsys.query.persistence.model.LexemeAlias;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 与目录全文索引共享 PostgreSQL 英文词形语义。 */
public interface LexemeMapper {

    /** 整批绑定数组以保持一次数据库往返，停用词或多词形项不返回。 */
    List<LexemeAlias> canonicalize(@Param("terms") List<String> terms);
}
