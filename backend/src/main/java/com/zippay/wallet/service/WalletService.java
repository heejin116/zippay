package com.zippay.wallet.service;

import com.zippay.common.money.Currency;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.dto.WalletResponse;
import com.zippay.wallet.exception.DuplicateWalletException;
import com.zippay.wallet.exception.WalletNotFoundException;
import com.zippay.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public WalletResponse open(Long userId, String currencyCode) {
        Currency currency = Currency.from(currencyCode);

        // 순차 요청은 여기서 409. 동시 요청은 둘 다 통과할 수 있고,
        // 그때는 uk_wallets_user_currency 위반을 GlobalExceptionHandler가 409로 바꾼다
        if (walletRepository.existsByUserIdAndCurrency(userId, currency)) {
            throw new DuplicateWalletException("이미 " + currency.name() + " 지갑이 있습니다.");
        }

        Wallet saved = walletRepository.save(Wallet.create(userId, currency));
        return WalletResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> list(Long userId) {
        return walletRepository.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(WalletResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WalletResponse get(Long userId, Long walletId) {
        return walletRepository.findByIdAndUserId(walletId, userId)
                .map(WalletResponse::from)
                .orElseThrow(() -> new WalletNotFoundException("지갑을 찾을 수 없습니다."));
    }
}
