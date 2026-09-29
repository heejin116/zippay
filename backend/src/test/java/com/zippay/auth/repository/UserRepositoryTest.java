package com.zippay.auth.repository;

import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import com.zippay.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    @Test
    void inquire_by_email() {
        // given
        User saved = userRepository.save(User.create(new Email("Test@Zippay.com"), "hash"));

        em.flush();   // INSERT(영속성 컨텍스트에 쌓인 변경)를 DB로 보냄 (SQL 실행)
        em.clear();   // 1차 캐시 비우기 → 이후 조회는 DB에서 새로 읽어옴

        // when
        User found = userRepository.findByEmail(new Email("TEST@zippay.com")).orElseThrow();

        // then
        assertThat(found.getId()).isNotNull();
        assertThat(found.getEmail()).isEqualTo(new Email("test@zippay.com"));
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
