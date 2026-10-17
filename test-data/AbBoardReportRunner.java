import com.recsys.offline.AbReportJob;
import com.recsys.offline.OfflineJob;
import com.recsys.offline.PublishReportsJob;
import com.recsys.offline.persistence.mapper.primary.AbReportMapper;
import com.recsys.offline.persistence.mapper.primary.ReportPublicationMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 本机演示报表的窄启动器：复用正式作业、Mapper XML和会话工厂。
 * 普通OfflineApplication扫描了Web安全链而无法在无Web上下文启动；
 * 此工具只装配两个报表依赖，不复制统计SQL/算法、不修改在线服务安全配置。
 */
public final class AbBoardReportRunner {
    /** 仅允许生成和发布两个作业；连接凭据从运行环境传入，不打印或写入文件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("ab-report") || args[0].equals("publish-reports"))) {
            throw new IllegalArgumentException("仅支持ab-report / publish-reports");
        }
        var source = new DriverManagerDataSource(
                "jdbc:postgresql://" + required("PG_HOST") + ":" + required("PG_PORT")
                        + "/" + required("PG_DB") + "?ApplicationName=ab-board-demo",
                required("PG_USER"), required("PG_PASSWORD"));
        var sessions = new SqlSessionTemplate(SpringSqlSessions.create(
                source, "classpath*:mapper/offline/primary/**/*.xml"));
        OfflineJob job = args[0].equals("ab-report")
                ? new AbReportJob(sessions.getMapper(AbReportMapper.class))
                : new PublishReportsJob(sessions.getMapper(ReportPublicationMapper.class));
        job.run(new DefaultApplicationArguments());
        System.out.println("JOB_PASS " + args[0]);
    }

    /** 缺配置立即失败，避免连接意外目标或退回内置口令。 */
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量: " + key);
        }
        return value;
    }
}
