package com.glr.deenwallet.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring Boot's default behavior serves static/index.html at "/" automatically (its
 * built-in "welcome page" convention) - which is why visiting the bare domain used to
 * drop a first-time visitor straight into the dashboard shell (index.html), which then
 * immediately redirected to the login form with zero introduction to the product.
 *
 * This explicit mapping takes priority over that default and forwards "/" to the new
 * marketing landing page instead. It does NOT change anything else: /index.html is still
 * reachable directly at that exact path, exactly as before, for the app shell that
 * redirects there after login.
 */
@Controller
public class HomePageController {

    @GetMapping("/")
    public String welcome() {
        return "forward:/welcome.html";
    }
}
