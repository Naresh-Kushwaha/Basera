package app.basera.security;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.common.web.RateLimitFilter;
import app.basera.common.web.RequestIdFilter;
import app.basera.config.BaseraProperties;
import app.basera.tenancy.TenantBindingFilter;
import app.basera.tenancy.TenantLookupService;
import app.basera.tenancy.TenantResolutionFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Filter order: RequestId -> RateLimit -> TenantResolution -> [Bearer JWT auth] -> TenantBinding.
     * The custom filters are created here with "new" on purpose: if they were Spring beans, Boot would
     * also register them as plain servlet filters and they would run twice.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   TenantLookupService tenants, BaseraProperties props,
                                                   ErrorResponseWriter writer) throws Exception {
        JsonSecurityHandlers handlers = new JsonSecurityHandlers(writer);

        // Public endpoints must ignore a stale/invalid Authorization header (else an expired access token
        // would turn POST /auth/refresh into a 401 instead of letting the refresh happen).
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver resolver = request -> {
            String path = request.getRequestURI();
            if (path.startsWith("/api/v1/auth/") || path.equals("/api/v1/tenant-config") || path.startsWith("/api/v1/media/")) {
                return null;
            }
            return defaultResolver.resolve(request);
        };

        http
                .csrf(AbstractHttpConfigurer::disable)   // bearer-token API; the cookie endpoints do an Origin check
                .cors(AbstractHttpConfigurer::disable)   // same-origin in production (API lives under each tenant host)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenant-config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/dev/**").permitAll()   // controller exists only with the dev profile
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("TENANT_ADMIN")
                        .requestMatchers("/api/v1/owner/**").hasRole("OWNER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(resolver)
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(j -> j
                                .decoder(jwtService.accessTokenDecoder())
                                .jwtAuthenticationConverter(SecurityConfig::toAuthentication)))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterBefore(new RequestIdFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(props.rateLimit(), writer), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new TenantResolutionFilter(tenants, props.tenant(), writer),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantBindingFilter(writer), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    private static AbstractAuthenticationToken toAuthentication(Jwt jwt) {
        try {
            AuthenticatedUser user = AuthenticatedUser.from(jwt);
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())), jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BadJwtException("Token carries invalid claims", e);
        }
    }
}
