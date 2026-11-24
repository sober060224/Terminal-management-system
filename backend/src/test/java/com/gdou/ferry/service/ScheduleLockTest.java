package com.gdou.ferry.service;

import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.repository.ScheduleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-8 回归：两个入口都是 {@code Propagation.MANDATORY}，脱离事务调用必须被 Spring 在进入方法前拒绝。
 * <p>
 * 反证：若改回普通 {@code @Transactional}，代理会先替调用方开事务，断言通过、返回即提交，
 * {@code FOR UPDATE} 的锁随之释放——这两个用例会因「没有抛 IllegalTransactionStateException」而红。
 */
@SpringJUnitConfig(ScheduleLockTest.Config.class)
class ScheduleLockTest {

    // 没有它就没有事务代理，MANDATORY 不生效（Spring Boot 主程序会自动开启）
    @Configuration
    @EnableTransactionManagement
    @Import(ScheduleLock.class)
    static class Config {
        @Bean
        ScheduleRepository scheduleRepository() {
            return mock(ScheduleRepository.class);
        }

        // 事务代理要能解析到事务管理器，否则进不了 MANDATORY 判断就报 NoSuchBeanDefinition
        @Bean
        PlatformTransactionManager transactionManager() {
            return new FakeTransactionManager();
        }
    }

    @Autowired
    private ScheduleLock scheduleLock;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 脱离事务调用lock在进入方法前就被拒绝() {
        assertThrows(IllegalTransactionStateException.class, () -> scheduleLock.lock(1L));
    }

    @Test
    void 脱离事务调用lockAll在进入方法前就被拒绝() {
        assertThrows(IllegalTransactionStateException.class, () -> scheduleLock.lockAll(1L, 2L));
    }

    @Test
    void 事务里照常拿到班次行锁() {
        Schedule schedule = new Schedule();
        schedule.setId(1L);
        when(scheduleRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(schedule));

        Schedule locked = new TransactionTemplate(transactionManager)
                .execute(status -> scheduleLock.lock(1L));

        assertSame(schedule, locked);
    }

    /** 只负责让 TransactionSynchronizationManager 认为「有事务」，不碰数据库。 */
    static class FakeTransactionManager implements PlatformTransactionManager {

        private boolean ownTransaction;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            // 真实的事务管理器在 MANDATORY 且没有事务时先抛这个异常；假实现必须照做，否则测不到 MANDATORY
            if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY
                    && !TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalTransactionStateException(
                        "No existing transaction found for transaction marked with propagation 'mandatory'");
            }
            // 已在事务里（MANDATORY 参与外层事务）时别再开一层同步
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                return new SimpleTransactionStatus();
            }
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            ownTransaction = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // 只有本管理器开的这层事务负责收尾；参与外层事务的提交直接跳过
            if (ownTransaction) {
                ownTransaction = false;
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            commit(status);
        }
    }
}
