package com.cbs.access;

import com.cbs.tenancy.TenantException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessPolicyFilter extends OncePerRequestFilter {
    public static final String ME = "cbs.access.me";
    private final ObjectProvider<AccessService> access;
    public AccessPolicyFilter(ObjectProvider<AccessService> access) { this.access=access; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if (request.getRequestURI().startsWith("/api/") &&
                SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            try {
                AccessService service=access.getIfAvailable();
                if(service==null) { write(response,503,"ACCESS_STORE_UNAVAILABLE");return; }
                boolean admin=token.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
                request.setAttribute(ME,service.authenticate(token.getToken(),admin,(request.getRequestURI().startsWith("/api/v1/banks/") && request.getRequestURI().contains("/operations"))));
            } catch(TenantException exception) { write(response,exception.status(),exception.code());return; }
            catch(DataAccessException exception) { write(response,503,"ACCESS_STORE_UNAVAILABLE");return; }
        }
        chain.doFilter(request,response);
    }
    private static void write(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);
        if(status==401) { response.setHeader("WWW-Authenticate","Bearer"); }
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":"+status+",\"title\":\"Access check failed\",\"code\":\""+code+"\"}");
    }
}
