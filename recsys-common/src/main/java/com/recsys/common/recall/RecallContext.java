package com.recsys.common.recall;

/**
 * 召回上下文。承载一次召回所需的输入。
 *
 * @param userId 用户 ID
 * @param size   期望候选规模(各路配额之和的目标,实际可超后由排序裁剪)
 * @param scene  场景
 */
public record RecallContext(long userId, int size, String scene) {
}
