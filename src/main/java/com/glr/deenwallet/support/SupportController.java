package com.glr.deenwallet.support;

import com.glr.deenwallet.transaction.Transaction;
import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Slf4j @RestController @RequestMapping("/api/support") @RequiredArgsConstructor
public class SupportController {
    private final TransactionRepository transactionRepository; private final UserRepository userRepository;
    @GetMapping("/transactions/{identifier}") public ResponseEntity<SupportTransactionView> lookup(@PathVariable String identifier){requireAdmin(); Transaction t=null; try{t=transactionRepository.findById(UUID.fromString(identifier)).orElse(null);}catch(IllegalArgumentException ignored){} if(t==null)t=transactionRepository.findByTransactionCodeIgnoreCase(identifier.trim()).orElse(null); if(t==null)return ResponseEntity.notFound().build(); User u=userRepository.findById(t.getUserId()).orElse(null); return ResponseEntity.ok(SupportTransactionView.from(t,u==null?null:u.getEmail(),u==null?null:u.getAccountNumber()));}
    @PutMapping("/users/{userId}/lock") public ResponseEntity<Void> lock(@PathVariable UUID userId){requireAdmin(); User u=userRepository.findById(userId).orElseThrow(()->new IllegalArgumentException("User not found")); if("SUPER_ADMIN".equals(u.getRole()))throw new SecurityException("Cannot lock super admin"); u.setLocked(true);u.setLockedAt(java.time.Instant.now());u.setLockedBy(null);userRepository.save(u);return ResponseEntity.noContent().build();}
    @PutMapping("/users/{userId}/unlock") public ResponseEntity<Void> unlock(@PathVariable UUID userId){requireAdmin(); User u=userRepository.findById(userId).orElseThrow(()->new IllegalArgumentException("User not found")); u.setLocked(false);u.setPinAttempts(0);u.setLockedAt(null);u.setLockedBy(null);userRepository.save(u);return ResponseEntity.noContent().build();}
    private void requireAdmin(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!a.isAuthenticated())throw new SecurityException("Not authenticated"); User u=userRepository.findById(UUID.fromString((String)a.getPrincipal())).orElseThrow(()->new SecurityException("User not found"));if(!"ADMIN".equals(u.getRole())&&!"SUPER_ADMIN".equals(u.getRole()))throw new SecurityException("Access denied: admin only");}
}

