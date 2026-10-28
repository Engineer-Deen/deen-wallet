package com.glr.deenwallet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Lets browsers and the Android app keep logos and icons for 7 days instead of downloading
 * them again on every visit, so they appear instantly after the first load.
 *
 * Only images are cached this long. Pages and scripts (index.html, auth.html, *.js) keep their
 * normal behaviour, so app updates still reach users right away.
 *
 * If you replace a logo file with a new picture under the SAME name, users may see the old
 * picture for up to 7 days. Give the new file a new name to avoid that.
 */
@Configuration
public class StaticAssetCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl sevenDays = CacheControl.maxAge(Duration.ofDays(7)).cachePublic();

        // /assets/** holds only PNG images (app logo, icons, bank-logos/, provider-logos/).
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(sevenDays);

        // The DeenWallet logo shown on the login, app and website pages.
        registry.addResourceHandler("/deenwallet-logo.png", "/deenwallet-logo-sm.png", "/deenwallet-logo-md.png")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(sevenDays);
    }
}
