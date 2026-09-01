package com.fracta.support;

import org.springframework.stereotype.Component;

import com.fracta.audit.api.Auditable;

/** 감사 로그 AOP 검증용 더미 서비스. 테스트 클래스패스에만 존재한다. */
@Component
public class AuditDummyService {

    public record DummyCommand(String accountRef, String memo, String password, String secret, String ciHash) {
    }

    public record DummyResult(String accountRef, String status, String token) {
    }

    @Auditable(action = "DUMMY_UPDATE", targetType = "DUMMY", targetId = "#result.accountRef()")
    public DummyResult update(DummyCommand command) {
        return new DummyResult(command.accountRef(), "UPDATED", "tok-9999");
    }

    @Auditable(action = "DUMMY_MULTI_ARG", targetType = "DUMMY", targetId = "#result.accountRef()")
    public DummyResult updateWithScalarArgs(String accountRef, String memo, String rawPassword) {
        return new DummyResult(accountRef, "UPDATED", "tok-9999");
    }
}
