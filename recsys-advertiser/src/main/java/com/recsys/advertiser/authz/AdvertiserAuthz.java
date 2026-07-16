package com.recsys.advertiser.authz;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

/**
 * 广告主管理面的细粒度归属判权守卫(接统一权限平台 auth-platform,模型 schemas/recsys.zed)。
 * 补上 P0 的缺口:此前只有角色 RBAC(hasAnyRole ADVERTISER/ADMIN),任一 ADVERTISER 可改任意广告主的
 * 广告——本守卫按 SpiceDB 里的归属关系判 {@code advertiser:{id}} 的 view/edit/manage 与
 * {@code platform:recsys} 的 administrate/review。
 *
 * <p>主体 = 内部令牌 subject(网关验终端 JWT 后经 {@code X-Internal-Auth} 下传,
 * {@code InternalTokenAuthFilter} 落 SecurityContext);资源生命周期双写:建广告主时写
 * owner + platform 归属元组(见 {@link #onAdvertiserCreated})。
 *
 * <p>失败姿态:enforce 下 deny→403、判权依赖故障→503(fail-closed,不静默放行);
 * shadow 下一律放行只记日志。写归属后的判读带最近写 ZedToken(防量化快照漏读刚建的归属)。
 */
public class AdvertiserAuthz {

    public enum Mode { DISABLED, SHADOW, ENFORCE }

    /** 平台锚点对象 id(全局唯一一个 platform:recsys)。 */
    public static final String PLATFORM_ID = "recsys";

    private static final Logger log = LoggerFactory.getLogger(AdvertiserAuthz.class);

    private final Mode mode;
    private final ObjectProvider<AuthzEngine> engineProvider;
    private final AtomicReference<String> lastWriteToken = new AtomicReference<>();

    public AdvertiserAuthz(String mode, ObjectProvider<AuthzEngine> engineProvider) {
        this.mode = parseMode(mode);
        this.engineProvider = engineProvider;
        if (this.mode != Mode.DISABLED) {
            log.info("细粒度归属判权已启用: mode={}", this.mode);
        }
    }

    /** 拼错的 mode 启动即失败(fail-fast),防止 enfoce 之类的笔误静默退化成 disabled。 */
    static Mode parseMode(String s) {
        String v = s == null ? "disabled" : s.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "disabled" -> Mode.DISABLED;
            case "shadow" -> Mode.SHADOW;
            case "enforce" -> Mode.ENFORCE;
            default -> throw new IllegalArgumentException("recsys.authz.mode 仅支持 disabled/shadow/enforce,收到: " + s);
        };
    }

    /** disabled 时调用方可跳过归属解析(避免额外 DB 查询)。 */
    public boolean enabled() {
        return mode != Mode.DISABLED;
    }

    /** 判当前主体对某广告主作用域的权限(view/edit/manage)。 */
    public void requireAdvertiser(String permission, long advertiserId) {
        decide(permission, ResourceRef.of("advertiser", String.valueOf(advertiserId)));
    }

    /** 判当前主体的平台级权限(administrate=开户/全量管理,review=创意人审)。 */
    public void requirePlatform(String permission) {
        decide(permission, ResourceRef.of("platform", PLATFORM_ID));
    }

    /**
     * 列表过滤(enforce 才真过滤;shadow 只记会隐藏几条):按 view 权限 checkBulk。
     * 匿名主体 enforce 下得空列表(fail-closed)。
     */
    public <T> List<T> filterViewable(List<T> items, ToLongFunction<T> advertiserIdOf) {
        if (!enabled() || items.isEmpty()) {
            return items;
        }
        String subject = currentSubject();
        if (subject == null) {
            if (mode == Mode.ENFORCE) {
                return List.of();
            }
            log.info("[authz-shadow] 匿名主体 list, enforce 下将返回空列表({} 条被隐藏)", items.size());
            return items;
        }
        // checkBulk 要求资源去重;多个 item 可能同 advertiserId(理论上列表是广告主本身,防御性去重)
        Map<Long, ResourceRef> refs = new LinkedHashMap<>();
        for (T item : items) {
            long id = advertiserIdOf.applyAsLong(item);
            refs.computeIfAbsent(id, k -> ResourceRef.of("advertiser", String.valueOf(k)));
        }
        Map<ResourceRef, Boolean> allowed;
        try {
            allowed = engine().checkBulk(SubjectRef.user(subject), "view", List.copyOf(refs.values()), consistency());
        } catch (RuntimeException e) {
            if (mode == Mode.ENFORCE) {
                throw dependencyFailure(e, "view", "advertiser:*");
            }
            log.warn("[authz-shadow] 列表判权依赖故障,放行: {}", e.getMessage());
            return items;
        }
        List<T> visible = items.stream()
                .filter(it -> Boolean.TRUE.equals(allowed.get(refs.get(advertiserIdOf.applyAsLong(it)))))
                .toList();
        if (mode == Mode.SHADOW) {
            if (visible.size() != items.size()) {
                log.info("[authz-shadow] subject={} list 将隐藏 {}/{} 条", subject, items.size() - visible.size(), items.size());
            }
            return items;
        }
        return visible;
    }

    /**
     * 资源生命周期双写:建广告主后写归属元组(platform 锚点 + 创建者为 owner)。
     * enforce 下写失败抛 503——调用方在 @Transactional 内,建号一并回滚,不留无归属孤儿;shadow 只告警。
     */
    public void onAdvertiserCreated(long advertiserId) {
        if (!enabled()) {
            return;
        }
        String subject = currentSubject();
        if (subject == null) {
            // enforce 下 requirePlatform("administrate") 已在建号前拦下匿名,走不到这里;shadow 记录缺口。
            log.warn("[authz-{}] 建广告主 {} 无主体,跳过归属双写", mode, advertiserId);
            return;
        }
        ResourceRef adv = ResourceRef.of("advertiser", String.valueOf(advertiserId));
        try {
            String token = engine().writeRelationships(List.of(
                    RelationshipUpdate.touch(adv, "platform", SubjectRef.of("platform", PLATFORM_ID)),
                    RelationshipUpdate.touch(adv, "owner", SubjectRef.user(subject)))).token();
            lastWriteToken.set(token);
            log.info("广告主 {} 归属已写入 (owner={})", advertiserId, subject);
        } catch (RuntimeException e) {
            if (mode == Mode.ENFORCE) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "授权归属写入失败,建号已回滚(fail-closed): " + e.getMessage());
            }
            log.warn("[authz-shadow] 广告主 {} 归属双写失败: {}", advertiserId, e.getMessage());
        }
    }

    private void decide(String permission, ResourceRef resource) {
        if (!enabled()) {
            return;
        }
        String subject = currentSubject();
        if (subject == null) {
            deny("(匿名)", permission, resource);
            return;
        }
        boolean allowed;
        try {
            allowed = engine().check(SubjectRef.user(subject), permission, resource, consistency());
        } catch (RuntimeException e) {
            if (mode == Mode.ENFORCE) {
                throw dependencyFailure(e, permission, resource.ref());
            }
            log.warn("[authz-shadow] 判权依赖故障,放行 {} {} : {}", permission, resource.ref(), e.getMessage());
            return;
        }
        if (!allowed) {
            deny(subject, permission, resource);
        }
    }

    private void deny(String subject, String permission, ResourceRef resource) {
        if (mode == Mode.ENFORCE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权对 " + resource.ref() + " 执行 " + permission);
        }
        log.info("[authz-shadow] would-deny subject={} permission={} resource={}", subject, permission, resource.ref());
    }

    private static ResponseStatusException dependencyFailure(RuntimeException e, String permission, String resource) {
        // 判权依赖故障不折成 deny 也不放行:503 让调用方/网关可区分"无权"与"判权面故障"(fail-closed)。
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "判权服务不可用(fail-closed): " + permission + " " + resource);
    }

    private AuthzEngine engine() {
        AuthzEngine engine = engineProvider.getIfAvailable();
        if (engine == null) {
            throw new IllegalStateException("AuthzEngine 未装配(authz.client.enabled=false?),但 recsys.authz.mode=" + mode);
        }
        return engine;
    }

    /** 判读带最近一次归属写入的 ZedToken:刚建的广告主立刻可被创建者读到(防量化快照漏读)。 */
    private Consistency consistency() {
        String token = lastWriteToken.get();
        return token != null ? Consistency.atLeastAsFresh(token) : Consistency.minimizeLatency();
    }

    private static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }
}
