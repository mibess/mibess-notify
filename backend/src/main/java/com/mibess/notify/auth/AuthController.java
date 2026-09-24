package com.mibess.notify.auth;

import java.util.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.mibess.notify.workspace.WorkspaceAccess;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserDetailsService users; private final PasswordEncoder encoder; private final SecurityContextRepository contexts; private final WorkspaceAccess access; private final JdbcClient db; private final RateLimiter limits;
    private final String dummy;
    public AuthController(UserDetailsService users,PasswordEncoder encoder,SecurityContextRepository contexts,WorkspaceAccess access,JdbcClient db,RateLimiter limits) { this.users=users;this.encoder=encoder;this.contexts=contexts;this.access=access;this.db=db;this.limits=limits;dummy=encoder.encode(UUID.randomUUID().toString()); }
    public record Login(@Email @NotBlank String email,@NotBlank @Size(max=100) String password) {}
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        limits.check("login:"+request.getRemoteAddr(),15);
        UserDetails user; try { user=users.loadUserByUsername(input.email()); } catch(UsernameNotFoundException e) { encoder.matches(input.password(),dummy); throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"E-mail ou senha inválidos"); }
        if(!encoder.matches(input.password(),user.getPassword()) || !user.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"E-mail ou senha inválidos");
        request.getSession();request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities()));SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        return me();
    }
    @GetMapping("/me") public Map<String,Object> me() {
        UUID w=access.id();
        return Map.of("email",access.actor(),"workspaceId",w,"workspace",db.sql("SELECT name FROM workspaces WHERE id=:id").param("id",w).query(String.class).single(),"role",SecurityContextHolder.getContext().getAuthentication().getAuthorities().iterator().next().getAuthority().replace("ROLE_",""));
    }
    public record Password(@NotBlank String currentPassword,@Size(min=14,max=100) String newPassword) {}
    @PostMapping("/password") public void password(@Valid @RequestBody Password input) {
        var user=users.loadUserByUsername(access.actor());if(!encoder.matches(input.currentPassword(),user.getPassword()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Senha atual inválida");
        db.sql("UPDATE users SET password_hash=:p WHERE email=:e").param("p",encoder.encode(input.newPassword())).param("e",access.actor()).update();
    }
}
