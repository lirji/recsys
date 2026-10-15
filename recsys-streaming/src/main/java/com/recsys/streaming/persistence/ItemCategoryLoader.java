package com.recsys.streaming.persistence;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.recsys.persistence.MybatisSettings;
import com.recsys.streaming.persistence.mapper.ItemCategoryMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.IOException;
import java.util.Map;

/** Flink非Spring宿主的一次性类目读取：拥有短生命周期会话，不拥有连接池或算子状态。 */
public final class ItemCategoryLoader {
    private static final String MAPPER_RESOURCE = "mapper/streaming/ItemCategoryMapper.xml";

    private ItemCategoryLoader() {
    }

    /** 逐行更新原快照，保留重复键覆盖及晚失败前已加载的部分结果；异常由算子沿原边界处理。 */
    public static void loadInto(String url, String user, String password, Map<Long, String> categories) throws IOException {
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
        var configuration = MybatisSettings.create();
        configuration.setEnvironment(new Environment("streaming-category", new JdbcTransactionFactory(), dataSource));
        try (var xml = Resources.getResourceAsStream(ItemCategoryLoader.class.getClassLoader(), MAPPER_RESOURCE)) {
            new XMLMapperBuilder(xml, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
        }
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        // 原DriverManager连接自动提交；不引入新事务或将连接保留到整个流作业结束。
        try (var session = factory.openSession(true)) {
            session.getMapper(ItemCategoryMapper.class).scan(context -> {
                var row = context.getResultObject();
                categories.put(row.itemId(), row.category());
            });
        }
    }
}
