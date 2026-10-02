package com.jianjin.assistant.interfaces.http.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Vue history 路由的刷新入口；/api 和静态资源仍由各自处理器负责。 */
@Controller
public class FrontendRouteController {

    @GetMapping({"/chat", "/knowledge", "/documents", "/tools", "/status", "/settings"})
    public String frontend() {
        return "forward:/index.html";
    }
}
