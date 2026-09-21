package com.cbs.access;

import com.cbs.foundation.security.IdentityConfiguration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@EnableConfigurationProperties(PortalController.Settings.class)
public class PortalController {
    @ConfigurationProperties("cbs.portal")
    public record Settings(String clientId,String redirectUri,String scope) {
        public Settings {
            scope=scope==null||scope.isBlank()?"openid profile email":scope;
            if(redirectUri!=null&&!redirectUri.isBlank()) {
                URI uri=URI.create(redirectUri);
                boolean local="http".equals(uri.getScheme())&&("127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost()));
                if((!"https".equals(uri.getScheme())&&!local)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null) {
                    throw new IllegalStateException("Portal redirect URI must use HTTPS or localhost HTTP, without query or fragment");
                }
            }
        }
    }
    public record ClientConfiguration(boolean configured,String authority,String clientId,String redirectUri,String scope) { }
    private final Settings settings;
    private final IdentityConfiguration.Settings identity;
    public PortalController(Settings settings,IdentityConfiguration.Settings identity) { this.settings=settings;this.identity=identity; }
    @GetMapping({"/","/admin","/admin/"}) String home() { return "redirect:/admin/index.html"; }
    @GetMapping("/portal/config") @ResponseBody ClientConfiguration config() {
        boolean ready=identity.enabled()&&settings.clientId()!=null&&!settings.clientId().isBlank()
                &&settings.redirectUri()!=null&&!settings.redirectUri().isBlank();
        return new ClientConfiguration(ready,ready?identity.issuer():null,ready?settings.clientId():null,
                ready?settings.redirectUri():null,ready?settings.scope():null);
    }
}
