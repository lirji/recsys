package com.recsys.advertiser.catalog;

/** HTTP总截止时间向本地事务传播；后台单步仍使用正式五秒事务预算。 */
public final class CatalogCallBudget {
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
    private CatalogCallBudget() { }
    public static void begin(){if(DEADLINE.get()!=null)throw new IllegalStateException("目录HTTP预算不能嵌套");DEADLINE.set(System.nanoTime()+5_000_000_000L);}
    public static void end(){DEADLINE.remove();}
    /** 不能等到下一外部动作后才发现前一步已用完预算。 */
    public static long remainingMillis(){Long deadline=DEADLINE.get();long ms=deadline==null?5000:(deadline-System.nanoTime())/1_000_000;
        if(ms<=0)throw CatalogProtocol.failure(CatalogProtocol.Failure.RESULT_UNKNOWN);return Math.min(5000,ms);}
}
