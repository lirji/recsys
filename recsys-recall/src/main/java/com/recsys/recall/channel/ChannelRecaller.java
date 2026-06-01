package com.recsys.recall.channel;

import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;

import java.util.List;

/**
 * 单路召回器。每路独立实现,互不影响;任一路失败由上层兜底。
 */
public interface ChannelRecaller {

    List<RecallItem> recall(RecallContext ctx);
}
