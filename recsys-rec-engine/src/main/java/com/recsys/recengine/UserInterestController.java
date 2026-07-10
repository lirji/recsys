package com.recsys.recengine;

import com.recsys.recengine.user.UserGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 冷启动兴趣 onboarding 入口:新用户提交感兴趣的类目,写入画像,使 TAG 召回立即生效。
 *
 * <pre>
 *   POST /api/user/{id}/interests   body: ["Comedy","Action"]
 *   GET  /api/user/{id}/interests
 * </pre>
 */
@RestController
public class UserInterestController {

    private final UserGateway userGateway;

    public UserInterestController(UserGateway userGateway) {
        this.userGateway = userGateway;
    }

    @PostMapping("/api/user/{id}/interests")
    public Map<String, Object> setInterests(@PathVariable("id") long userId,
                                            @RequestBody List<String> categories) {
        userGateway.updateInterests(userId, categories);
        return Map.of("userId", userId, "categories", categories, "ok", true);
    }

    @GetMapping("/api/user/{id}/interests")
    public Map<String, Object> getInterests(@PathVariable("id") long userId) {
        return Map.of("userId", userId, "categories", userGateway.getInterests(userId));
    }
}
