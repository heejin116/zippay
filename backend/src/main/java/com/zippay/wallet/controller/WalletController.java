package com.zippay.wallet.controller;

import com.zippay.wallet.dto.OpenWalletRequest;
import com.zippay.wallet.dto.WalletResponse;
import com.zippay.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> open(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody OpenWalletRequest request) {
        WalletResponse wallet = walletService.open(userId(jwt), request.currency());
        return ResponseEntity
                .created(URI.create("/api/wallets/" + wallet.id()))
                .body(wallet);
    }

    @GetMapping
    public ResponseEntity<List<WalletResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(walletService.list(userId(jwt)));
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> get(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable Long walletId) {
        return ResponseEntity.ok(walletService.get(userId(jwt), walletId));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
