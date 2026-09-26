package com.example.notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
@Configuration
public class ServiceSecurity {
    @Bean UserDetailsService users(@Value("${service.password}") String password) {
        if (password.isBlank()) throw new IllegalArgumentException("Service password is required");
        return new InMemoryUserDetailsManager(User.withUsername("operator")
            .password(new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }
    @Bean org.springframework.security.crypto.password.PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/admin/dead-letters/**").hasRole("ADMIN").anyRequest().denyAll())
            .httpBasic(org.springframework.security.config.Customizer.withDefaults()).build();
    }
}
