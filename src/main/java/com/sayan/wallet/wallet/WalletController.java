package com.sayan.wallet.wallet;

import com.sayan.wallet.common.util.PageResponse;
import com.sayan.wallet.wallet.WalletDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService walletService;

    @GetMapping("/me")
    public WalletResponse me(Authentication auth) {
        return walletService.getMyWallet(UUID.fromString(auth.getName()));
    }

    @PostMapping("/topup")
    @ResponseStatus(HttpStatus.CREATED)
    public TopUpResponse topUp(Authentication auth, @Valid @RequestBody TopUpRequest req) {
        return walletService.topUp(UUID.fromString(auth.getName()), req.amount());
    }

    @GetMapping("/me/ledger")
    public PageResponse<LedgerEntryResponse> ledger(Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return walletService.history(UUID.fromString(auth.getName()), page, size);
    }
}