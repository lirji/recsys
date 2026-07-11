package com.recsys.console.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 360 只读端点。走 /api/console/**(网关已路由到 console:8090)。
 * 前端另可直接调 rec-engine {@code /api/user/{id}/interests} 补冷启动兴趣画像。
 */
@RestController
@RequestMapping("/api/console/user")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public UserProfileView get(@PathVariable long id) {
        return service.get(id);
    }
}
