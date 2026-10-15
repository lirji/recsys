package com.recsys.content.persistence.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.recsys.content.Item;
import com.recsys.persistence.typehandler.TextListTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 仅持久层使用的行映射，数据库注解不进入内容服务契约 Item。 */
@Getter
@Setter
@TableName(value = "item", autoResultMap = true)
public class ItemRow {
    @TableId(value = "item_id", type = IdType.INPUT)
    private Long itemId;
    private String title;
    private String category;
    @TableField(typeHandler = TextListTypeHandler.class)
    private List<String> tags;
    private String description;
    private Double popularity;

    /** 保留原 RowMapper 的 NULL 语义：标签为空列表，数值 getDouble(NULL) 为零。 */
    public Item toItem() {
        return new Item(itemId, title, category, tags == null ? List.of() : tags,
                description, popularity == null ? 0 : popularity);
    }
}
