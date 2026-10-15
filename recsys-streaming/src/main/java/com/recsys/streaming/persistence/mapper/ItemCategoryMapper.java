package com.recsys.streaming.persistence.mapper;

import org.apache.ibatis.session.ResultHandler;

/** 类目输入独立于Flink算子，SQL由同模块XML审查和打包。 */
public interface ItemCategoryMapper {
    /** 沿数据库返回顺序逐行回调，不添加过滤/去重/排序以改变旧快照语义。 */
    void scan(ResultHandler<ItemCategory> handler);

    /** 64位目录身份与可空类目，不借用JSON或无类型Map表达结果行。 */
    record ItemCategory(long itemId, String category) {
    }
}
