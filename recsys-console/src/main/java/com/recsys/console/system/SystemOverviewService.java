package com.recsys.console.system;

import org.springframework.stereotype.Service;

import java.util.List;

import static com.recsys.console.system.SystemOverview.ApiEndpoint;
import static com.recsys.console.system.SystemOverview.CommandGroup;
import static com.recsys.console.system.SystemOverview.CommandItem;
import static com.recsys.console.system.SystemOverview.ModuleInfo;
import static com.recsys.console.system.SystemOverview.SystemLink;

@Service
public class SystemOverviewService {

    public SystemOverview overview() {
        return new SystemOverview(
                "recsys",
                "面向搜索、推荐、广告、离线评测和实时特征的推荐系统多模块单仓。",
                "Java 21 + Spring Boot 3.2 + Spring Cloud Gateway + React 18 + Vite + Ant Design",
                modules(),
                links(),
                apis(),
                commands()
        );
    }

    public List<ModuleInfo> modules() {
        return List.of(
                new ModuleInfo("console", "frontend", "控制台前端 SPA。", 5173, null, List.of("/"), List.of("recsys-gateway"), true, "/overview"),
                new ModuleInfo("recsys-gateway", "app", "统一 API 网关和本地静态路由。", 8080, null, List.of("/api/**"), List.of("recsys-rec-engine", "recsys-behavior", "recsys-advertiser", "recsys-console"), true, "/overview"),
                new ModuleInfo("recsys-rec-engine", "app", "推荐编排主入口，聚合 query、recall、rank、feature、embedding、content、user、ad 等能力。", 8081, null, List.of("/api/recommend", "/api/search", "/api/search-ads", "/api/feed", "/api/query", "/api/user", "/api/ad", "/api/experiment"), List.of("recsys-query", "recsys-recall", "recsys-rank", "recsys-feature", "recsys-content", "recsys-user", "recsys-ad"), true, "/recommend"),
                new ModuleInfo("recsys-behavior", "app", "行为采集服务，接收曝光、点击、播放、评分等行为。", 8082, null, List.of("/api/behavior"), List.of("recsys-common"), true, "/recommend"),
                new ModuleInfo("recsys-advertiser", "app", "广告主后台服务，提供广告主、广告、创意、竞价词和投放报表管理。", 8083, null, List.of("/api/advertiser"), List.of("recsys-ad-common"), true, "/advertiser"),
                new ModuleInfo("recsys-console", "app", "控制台 BFF，提供离线报表读取和系统总览接口。", 8090, null, List.of("/api/console"), List.of("recsys-common"), true, "/reports"),
                new ModuleInfo("recsys-ad-serving", "app", "广告投放内部服务，提供广告召回、排序和计费相关能力。", 8085, 9095, List.of(), List.of("recsys-ad-common", "recsys-proto"), true, null),
                new ModuleInfo("recsys-content-service", "app", "内容服务化模块，提供物品内容和元数据能力。", 8086, 9096, List.of(), List.of("recsys-content", "recsys-proto"), true, null),
                new ModuleInfo("recsys-user-service", "app", "用户服务化模块，提供用户画像和兴趣能力。", 8087, 9097, List.of(), List.of("recsys-user", "recsys-proto"), true, "/user-interests"),
                new ModuleInfo("recsys-offline", "job", "离线导入、评测、A/B 报表、广告报表和模型训练作业。", null, null, List.of(), List.of("recsys-common"), false, "/reports"),
                new ModuleInfo("recsys-streaming", "job", "Flink 实时特征作业，消费行为流并写入 Redis 实时特征。", null, null, List.of(), List.of("recsys-common"), false, null),
                new ModuleInfo("recsys-common", "lib", "推荐系统共享 DTO、常量和契约。", null, null, List.of(), List.of(), false, null),
                new ModuleInfo("recsys-ad-common", "lib", "广告领域共享契约。", null, null, List.of(), List.of(), false, null),
                new ModuleInfo("recsys-proto", "lib", "gRPC / protobuf Published Language 契约。", null, null, List.of(), List.of(), false, null),
                new ModuleInfo("recsys-query", "lib", "Query 解析、归一化、意图识别和改写。", null, null, List.of(), List.of("recsys-common"), false, "/query"),
                new ModuleInfo("recsys-recall", "lib", "热门、协同过滤、内容、向量等多路召回。", null, null, List.of(), List.of("recsys-common"), false, "/recommend"),
                new ModuleInfo("recsys-rank", "lib", "规则排序和模型排序。", null, null, List.of(), List.of("recsys-common"), false, "/recommend"),
                new ModuleInfo("recsys-feature", "lib", "在线和离线特征读写。", null, null, List.of(), List.of("recsys-common"), false, null),
                new ModuleInfo("recsys-embedding", "lib", "文本向量化能力，可使用 Gemini 并支持降级。", null, null, List.of(), List.of("recsys-common"), false, null),
                new ModuleInfo("recsys-content", "lib", "物品元数据和内容领域能力。", null, null, List.of(), List.of("recsys-common"), false, null),
                new ModuleInfo("recsys-user", "lib", "用户画像和兴趣领域能力。", null, null, List.of(), List.of("recsys-common"), false, "/user-interests"),
                new ModuleInfo("recsys-ad", "lib", "广告召回、匹配和排序领域能力。", null, null, List.of(), List.of("recsys-ad-common"), false, "/search-ads")
        );
    }

    public List<SystemLink> links() {
        return List.of(
                new SystemLink("推荐链路", "从用户和场景进入推荐编排，经过召回、排序、过滤和可解释结果展示。", List.of("console /recommend", "gateway", "rec-engine", "query / recall / rank / feature", "behavior feedback"), "/recommend"),
                new SystemLink("搜索链路", "Query 解析后驱动搜索结果和可解释召回排序。", List.of("console /search", "gateway", "rec-engine", "query", "recall", "rank"), "/search"),
                new SystemLink("搜索广告链路", "Query 广告匹配、计价和点击转化归因。", List.of("console /search-ads", "gateway", "rec-engine", "recsys-ad", "advertiser data"), "/search-ads"),
                new SystemLink("混排 Feed 链路", "自然推荐和广告结果统一混排。", List.of("console /feed", "gateway", "rec-engine", "recommend", "ads", "blend"), "/feed"),
                new SystemLink("行为采集链路", "前端行为上报到 behavior，支持在线指标和实时特征。", List.of("console actions", "gateway", "behavior", "database / kafka", "streaming"), "/recommend"),
                new SystemLink("离线报表链路", "离线作业产出 CSV 或写库，console BFF 读取后由前端可视化。", List.of("offline jobs", "eval_report / csv", "recsys-console", "console /reports"), "/reports"),
                new SystemLink("实时特征链路", "Flink 作业消费 Kafka 行为流，产出实时热度和用户实时偏好。", List.of("behavior", "kafka behavior-events", "recsys-streaming", "redis", "rec-engine"), null)
        );
    }

    public List<ApiEndpoint> apis() {
        return List.of(
                new ApiEndpoint("GET", "/api/recommend", "recsys-rec-engine", "个性化推荐。", "/recommend"),
                new ApiEndpoint("GET", "/api/search", "recsys-rec-engine", "搜索结果。", "/search"),
                new ApiEndpoint("GET", "/api/search-ads", "recsys-rec-engine", "搜索广告结果。", "/search-ads"),
                new ApiEndpoint("GET", "/api/feed", "recsys-rec-engine", "自然内容和广告混排 Feed。", "/feed"),
                new ApiEndpoint("GET", "/api/query/parse", "recsys-rec-engine", "Query 理解和改写。", "/query"),
                new ApiEndpoint("GET/POST", "/api/user/{id}/interests", "recsys-rec-engine", "冷启动兴趣读取和保存。", "/user-interests"),
                new ApiEndpoint("GET/POST", "/api/experiment/**", "recsys-rec-engine", "实验配置查看和临时覆盖。", "/experiment"),
                new ApiEndpoint("POST", "/api/behavior", "recsys-behavior", "行为事件上报。", "/recommend"),
                new ApiEndpoint("GET/POST/PUT/DELETE", "/api/advertiser/**", "recsys-advertiser", "广告主、广告、创意、竞价词和审核管理。", "/advertiser"),
                new ApiEndpoint("GET", "/api/console/report/**", "recsys-console", "离线报表索引和文件读取。", "/reports"),
                new ApiEndpoint("GET", "/api/console/system/**", "recsys-console", "系统总览、模块、API、命令和健康状态。", "/overview")
        );
    }

    public List<CommandGroup> commands() {
        return List.of(
                new CommandGroup("前端", List.of(
                        new CommandItem("安装依赖", "cd console && npm install"),
                        new CommandItem("启动开发服务", "cd console && npm run dev"),
                        new CommandItem("构建", "cd console && npm run build")
                )),
                new CommandGroup("核心在线服务", List.of(
                        new CommandItem("网关", "mvn -pl recsys-gateway spring-boot:run"),
                        new CommandItem("推荐编排", "mvn -pl recsys-rec-engine spring-boot:run"),
                        new CommandItem("行为采集", "mvn -pl recsys-behavior spring-boot:run"),
                        new CommandItem("广告主后台", "mvn -pl recsys-advertiser spring-boot:run"),
                        new CommandItem("控制台 BFF", "mvn -pl recsys-console spring-boot:run")
                )),
                new CommandGroup("内部服务化模块", List.of(
                        new CommandItem("广告投放服务", "mvn -pl recsys-ad-serving spring-boot:run"),
                        new CommandItem("内容服务", "mvn -pl recsys-content-service spring-boot:run"),
                        new CommandItem("用户服务", "mvn -pl recsys-user-service spring-boot:run")
                )),
                new CommandGroup("离线与实时", List.of(
                        new CommandItem("启动中间件", "docker compose up -d"),
                        new CommandItem("启动观测栈", "docker compose --profile obs up -d"),
                        new CommandItem("启动 Kafka", "docker compose --profile full up -d kafka"),
                        new CommandItem("实时特征作业", "bash recsys-streaming/run-streaming.sh --window-min 10 --slide-sec 20")
                ))
        );
    }

}
